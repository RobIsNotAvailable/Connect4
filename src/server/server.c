#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include "protocol.h"

#define MAX_CLIENTS 64

// Bookkeeping for a single connected client: identity + its socket.
typedef struct
{
    int id;
    int sock;
    char username[USERNAME_LEN];
} Client;

// Thread-safe registry of currently connected clients. Fixed-size array
// (no dynamic list) so add/remove don't need per-node malloc/free; a
// single mutex protects both the array and the id counter, since they
// are always modified together.
typedef struct
{
    Client clients[MAX_CLIENTS];
    int count;
    int next_id;
    pthread_mutex_t mutex;
} ClientList;

static ClientList g_clients = {
    .count = 0,
    .next_id = 1,
    .mutex = PTHREAD_MUTEX_INITIALIZER
};

// Registers a new client and assigns it an id/username. Returns the
// assigned Client, or a Client with id == -1 if the registry is full.
Client client_list_add(int sock)
{
    Client new_client = { .id = -1, .sock = sock, .username = {0} };

    pthread_mutex_lock(&g_clients.mutex);

    if (g_clients.count < MAX_CLIENTS)
    {
        new_client.id = g_clients.next_id++;
        snprintf(new_client.username, USERNAME_LEN, "Player%d", new_client.id);
        g_clients.clients[g_clients.count++] = new_client;
    }

    pthread_mutex_unlock(&g_clients.mutex);

    return new_client;
}

// Removes the client owning the given socket from the registry, if present.
void client_list_remove(int sock)
{
    pthread_mutex_lock(&g_clients.mutex);

    for (int i = 0; i < g_clients.count; i++)
    {
        if (g_clients.clients[i].sock == sock)
        {
            // Swap-with-last removal: order doesn't matter for this list,
            // so this avoids shifting every following element.
            g_clients.clients[i] = g_clients.clients[g_clients.count - 1];
            g_clients.count--;
            break;
        }
    }

    pthread_mutex_unlock(&g_clients.mutex);
}

void *client_handler(void *sock_id)
{
    int client_sock = *(int *)sock_id;
    free(sock_id); // was never freed before: leaked one int per connection

    Client me = client_list_add(client_sock);
    if (me.id == -1)
    {
        printf("[SERVER] Rejecting client on socket %d: registry full (max %d clients)\n",
               client_sock, MAX_CLIENTS);
        close(client_sock);
        pthread_exit(NULL);
    }

    printf("[SERVER] New client connected on socket %d, assigned id=%d username=%s\n",
           client_sock, me.id, me.username);

    // Tell the client which identity it was assigned.
    Packet welcome_pkt;
    welcome_pkt.header.type = CMD_WELCOME;
    welcome_pkt.header.payload_size = sizeof(Welcome);
    welcome_pkt.payload.welcome.client_id = me.id;
    strncpy(welcome_pkt.payload.welcome.username, me.username, USERNAME_LEN);
    send(client_sock, &welcome_pkt, sizeof(Packet), 0);

    Packet* pktptr = malloc(sizeof(Packet));
    int comm_status;

    while ((comm_status = recv(client_sock, pktptr, sizeof(Packet), 0)) > 0)
    {
        printf("[SERVER] [%s] Received command type: %d\n", me.username, pktptr->header.type);

        send(client_sock, pktptr, sizeof(Packet), 0);
    }
    if (comm_status == 0)
    {
        printf("[SERVER] Client %s (socket %d) disconnected\n", me.username, client_sock);
    }
    else
    {
        perror("[SERVER] Error in receiving data from client\n");
    }

    client_list_remove(client_sock);
    close(client_sock);
    free(pktptr);
    pthread_exit(NULL);
}

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