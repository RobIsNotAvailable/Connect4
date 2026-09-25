#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <pthread.h>
#include "client_registry.h"
#include "commands.h"
#include "net.h"

// How long a send() to a client may stay blocked before the server gives up on it
#define SEND_TIMEOUT_SEC 2

// The one lock of the server. Every change to the registries (clients and
// games) and every line sent happens with it held: while a thread registers a
// client, handles a command or applies a disconnect, no other thread does
// anything else. So the notifications always follow the order of the changes
// they are about. Without it, two threads could apply their changes in one
// order and notify them in the other: both players hang up together,
// GAME_CLOSED goes out and then the NEW_GAME of the first hang-up, and the
// lobby shows a room that no longer exists (tests/stress_disconnect_race.py).
// The registries have no lock of their own. A command takes microseconds, and
// the threads keep waiting for their clients in parallel, outside the lock.
static pthread_mutex_t command_mutex = PTHREAD_MUTEX_INITIALIZER;

static void handle_stop_signal(int sig);
static void *client_handler(void *sock_id);

int main()
{
    int server_sock, client_sock;
    struct sockaddr_in server_addr, client_addr;
    socklen_t addr_len = sizeof(client_addr);

    if ((server_sock = socket(AF_INET, SOCK_STREAM, 0)) < 0)
    {
        perror("Error in creating the server socket\n");
        return -1;
    }

    // When the server stops while clients are still connected
    // the kernel keeps each of them in TIME_WAIT for about a minute.
    // Those leftovers still hold the server's port, so without the option below
    // a restart right away fails in bind() with "Address already in use" until they expire.
    // SO_REUSEADDR lets bind() succeed despite connections in TIME_WAIT.
    int reuse = 1;
    if (setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) < 0)
    {
        perror("Setsockopt error");
        close(server_sock);
        return -1;
    }

    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(PORT);

    if (bind(server_sock, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0)
    {
        perror("Bind error\n");
        close(server_sock);
        return -1;
    }

    if (listen(server_sock, 10) < 0)
    {
        perror("Listen error");
        close(server_sock);
        return -1;
    }

    // Problem: the default action of SIGTERM (what `docker stop` sends) and
    // SIGINT (Ctrl-C) is to kill the process, but the first process of a
    // container (PID 1) is special: the kernel ignores the signals it has no
    // handler for. The server would then not stop on `docker stop`, which
    // waits 10 seconds and finally kills it with SIGKILL. With a handler
    // installed the signal is delivered like for any other process.
    // sigaction() is used instead of signal() because its behaviour is fully
    // specified by POSIX (signal() varies between systems).
    struct sigaction stop_action;
    memset(&stop_action, 0, sizeof(stop_action));
    stop_action.sa_handler = handle_stop_signal;
    sigemptyset(&stop_action.sa_mask);
    if (sigaction(SIGTERM, &stop_action, NULL) < 0 || sigaction(SIGINT, &stop_action, NULL) < 0)
    {
        perror("Sigaction error");
        close(server_sock);
        return -1;
    }

    printf("=== SERVER STARTED ON PORT %d ===\n", PORT);

    while (1)
    {
        if ((client_sock = accept(server_sock, (struct sockaddr *)&client_addr, &addr_len)) < 0)
        {
            perror("Accept error");
            continue;
        }

        int *new_sock = malloc(sizeof(int));
        *new_sock = client_sock;
        pthread_t thread_id;

        if (pthread_create(&thread_id, NULL, client_handler, (void *)new_sock) < 0)
        {
            perror("Error in creating the thread");
            free(new_sock);
            close(client_sock);
        }
        else
        {
            pthread_detach(thread_id);
        }
    }

    close(server_sock);
    return 0;
}

// SIGTERM / SIGINT: the server stops at once. Why not set a flag and let the
// accept() loop in main() notice it? The process has many threads and the
// signal may be delivered to any of them, so main()'s accept() is not sure to
// be interrupted, and a signal arriving just before accept() would leave it
// blocked until the next client. There is also nothing to clean up: all the
// state is in memory, and when the process ends the kernel closes every socket
// (each client sees its connection closed). Only async-signal-safe functions
// may run in a handler: write() and _exit() are, while printf() and exit()
// are not (they could deadlock on a lock that the interrupted code holds).
static void handle_stop_signal(int sig)
{
    (void)sig;
    static const char msg[] = "\n=== SERVER STOPPING ===\n";
    ssize_t written = write(STDOUT_FILENO, msg, sizeof(msg) - 1);
    (void)written;
    _exit(0);
}

static void *client_handler(void *sock_id)
{
    int client_sock = *(int *)sock_id;
    free(sock_id);

    // A client that stops reading fills its socket buffers, and from then on
    // every send() to it blocks, with command_mutex held: the whole server
    // would freeze. With a send timeout the blocked send() fails after
    // SEND_TIMEOUT_SEC, and client_send_line drops the client.
    struct timeval send_timeout = { .tv_sec = SEND_TIMEOUT_SEC, .tv_usec = 0 };
    if (setsockopt(client_sock, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout)) < 0)
    {
        perror("[SERVER] Setsockopt SO_SNDTIMEO error");
    }

    pthread_mutex_lock(&command_mutex);
    Client me = client_list_add(client_sock);
    if (me.id != -1)
    {
        client_send_line(client_sock, "WELCOME %d", me.id);
    }
    pthread_mutex_unlock(&command_mutex);

    if (me.id == -1)
    {
        printf("[SERVER] Rejecting client on socket %d: registry full (max %d clients)\n", client_sock, MAX_CLIENTS);
        close(client_sock);
        pthread_exit(NULL);
    }
    printf("[SERVER] New client connected on socket %d, assigned id=%d\n", client_sock, me.id);

    LineReader reader;
    line_reader_init(&reader, client_sock);
    char line[MAX_LINE];
    int comm_status;

    while ((comm_status = recv_line(&reader, line)) == LINE_OK)
    {
        pthread_mutex_lock(&command_mutex);
        dispatch_command(client_sock, &me, line);
        pthread_mutex_unlock(&command_mutex);
    }

    if (comm_status == LINE_CLOSED)
    {
        printf("[SERVER] Client %s (socket %d) disconnected\n", log_name(&me), client_sock);
    }
    else if (comm_status == LINE_TOO_LONG)
    {
        printf("[SERVER] Client %s (socket %d) sent a line longer than %d bytes, closing\n",
               log_name(&me), client_sock, MAX_LINE);
    }
    else
    {
        perror("[SERVER] Error in receiving data from client");
    }

    pthread_mutex_lock(&command_mutex);
    handle_disconnect(client_sock);
    client_list_remove(me.id);
    pthread_mutex_unlock(&command_mutex);

    close(client_sock);
    pthread_exit(NULL);
}
