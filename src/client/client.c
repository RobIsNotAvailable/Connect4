#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include "protocol.h"

// Tracks a join request the server notified us about (we are the game
// owner) that hasn't been answered yet. Only one at a time: a second
// CMD_JOIN_NOTIFY while one is already pending would just overwrite it,
// but the server never sends one before the previous is resolved (see
// game_registry_set_pending in server.c), so this is safe as-is.
typedef struct
{
    int valid;
    int game_id;
    char joiner_username[USERNAME_LEN];
    pthread_mutex_t mutex;
} PendingNotify;

static PendingNotify g_pending_notify = {
    .valid = 0,
    .mutex = PTHREAD_MUTEX_INITIALIZER
};

// Sends CMD_CREATE_GAME. The reply is printed by the receiver thread,
// not here: only one thread may recv() on the socket (see receiver_thread).
void create_game(int sock)
{
    Packet req;
    req.header.type = CMD_CREATE_GAME;
    req.header.payload_size = 0;
    send(sock, &req, sizeof(Packet), 0);
}

// Sends CMD_LIST_GAMES. Reply printed by the receiver thread.
void list_games(int sock)
{
    Packet req;
    req.header.type = CMD_LIST_GAMES;
    req.header.payload_size = 0;
    send(sock, &req, sizeof(Packet), 0);
}

// Sends CMD_JOIN_GAME for the given game id. Reply printed by the
// receiver thread once the owner answers.
void join_game(int sock, int game_id)
{
    Packet req;
    req.header.type = CMD_JOIN_GAME;
    req.header.payload_size = sizeof(JoinRequest);
    req.payload.join_request.game_id = game_id;
    send(sock, &req, sizeof(Packet), 0);
}

// Sends CMD_JOIN_RESPONSE for whichever join request is currently
// pending (see g_pending_notify). No-op with a message if none is pending.
void respond_to_join(int sock, int accepted)
{
    pthread_mutex_lock(&g_pending_notify.mutex);

    if (!g_pending_notify.valid)
    {
        pthread_mutex_unlock(&g_pending_notify.mutex);
        printf("[CLIENT] No pending join request to respond to\n");
        return;
    }

    int game_id = g_pending_notify.game_id;
    g_pending_notify.valid = 0;

    pthread_mutex_unlock(&g_pending_notify.mutex);

    Packet req;
    req.header.type = CMD_JOIN_RESPONSE;
    req.header.payload_size = sizeof(JoinResponse);
    req.payload.join_response.game_id = game_id;
    req.payload.join_response.accepted = accepted;
    send(sock, &req, sizeof(Packet), 0);
}

// Runs in its own thread for the whole lifetime of the connection: the
// only place that calls recv() on the socket, since command replies are
// no longer synchronous (a join request notifies a socket that isn't
// the one who sent the command, so blocking send()+recv() pairs don't
// work anymore for every command).
void *receiver_thread(void *sock_ptr)
{
    int sock = *(int *)sock_ptr;
    Packet pkt;
    int status;

    while ((status = recv(sock, &pkt, sizeof(Packet), 0)) > 0)
    {
        switch (pkt.header.type)
        {
            case CMD_GAME_CREATED:
                if (pkt.payload.game_created.game_id == -1)
                {
                    printf("\n[CLIENT] Server could not create the game (registry full)\n> ");
                }
                else
                {
                    printf("\n[CLIENT] Game created, id=%d\n> ", pkt.payload.game_created.game_id);
                }
                fflush(stdout);
                break;
            case CMD_GAME_LIST:
            {
                GameList *list = &pkt.payload.game_list;
                if (list->count == 0)
                {
                    printf("\n[CLIENT] No games waiting for players\n> ");
                }
                else
                {
                    printf("\n[CLIENT] Games waiting for players:\n");
                    for (int i = 0; i < list->count; i++)
                    {
                        printf("  id=%d owner=%s\n", list->games[i].game_id, list->games[i].owner_username);
                    }
                    printf("> ");
                }
                fflush(stdout);
                break;
            }
            case CMD_JOIN_NOTIFY:
            {
                pthread_mutex_lock(&g_pending_notify.mutex);
                g_pending_notify.valid = 1;
                g_pending_notify.game_id = pkt.payload.join_notify.game_id;
                strncpy(g_pending_notify.joiner_username, pkt.payload.join_notify.joiner_username, USERNAME_LEN);
                pthread_mutex_unlock(&g_pending_notify.mutex);

                printf("\n[CLIENT] %s wants to join your game (id=%d) - use menu option 4 to respond\n> ",
                       pkt.payload.join_notify.joiner_username, pkt.payload.join_notify.game_id);
                fflush(stdout);
                break;
            }
            case CMD_JOIN_RESULT:
                if (pkt.payload.join_result.accepted)
                {
                    printf("\n[CLIENT] Join request for game %d accepted\n> ", pkt.payload.join_result.game_id);
                }
                else
                {
                    printf("\n[CLIENT] Join request for game %d refused\n> ", pkt.payload.join_result.game_id);
                }
                fflush(stdout);
                break;
            case CMD_ERROR:
                printf("\n[CLIENT] Server returned an error for the last command\n> ");
                fflush(stdout);
                break;
            default:
                printf("\n[CLIENT] Unhandled reply type: %d\n> ", pkt.header.type);
                fflush(stdout);
                break;
        }
    }

    if (status == 0)
    {
        printf("\n[CLIENT] Server closed the connection\n");
    }
    else
    {
        perror("\n[CLIENT] Error in receiving data from server\n");
    }
    exit(0); // the menu loop can't do anything useful once the socket is gone
}

int main()
{
    int sock = 0;
    struct sockaddr_in serv_addr;

    if ((sock = socket(AF_INET, SOCK_STREAM, 0)) < 0)
    {
        perror("Error in creating the client socket\n");
        return -1;
    }

    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(PORT);

    // Explicitly set the server IP. Without this, sin_addr is left
    // uninitialized; it happened to work on Linux because a fresh stack
    // is usually zeroed and connect() treats 0.0.0.0 as localhost, but
    // that behavior is not guaranteed (e.g. across containers in Docker).
    if (inet_pton(AF_INET, "127.0.0.1", &serv_addr.sin_addr) <= 0)
    {
        perror("Invalid server address\n");
        return -1;
    }

    if (connect(sock, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0)
    {
        perror("Connection failed\n");
        return -1;
    }

    Packet* pktptr = malloc(sizeof(Packet));

    // The server assigns an id/username right after accept(); this is the
    // first message we expect to receive.
    if (recv(sock, pktptr, sizeof(Packet), 0) > 0 && pktptr->header.type == CMD_WELCOME)
    {
        printf("[CLIENT] Connected as %s (id=%d)\n",
               pktptr->payload.welcome.username, pktptr->payload.welcome.client_id);
    }
    else
    {
        fprintf(stderr, "[CLIENT] Did not receive a valid welcome message from the server\n");
        free(pktptr);
        close(sock);
        return -1;
    }

    free(pktptr);

    // Start the receiver thread now: from this point on, every reply
    // (including the synchronous-looking ones for create/list) arrives
    // asynchronously and is printed by receiver_thread.
    pthread_t recv_tid;
    if (pthread_create(&recv_tid, NULL, receiver_thread, &sock) != 0)
    {
        perror("[CLIENT] Error creating receiver thread\n");
        close(sock);
        return -1;
    }
    pthread_detach(recv_tid);

    // Minimal menu to exercise the commands implemented so far. Will be
    // replaced by the full textual menu + grid rendering in Phase 7.
    int choice = 0;
    do
    {
        printf("\n1) Create game\n2) List games\n3) Join game\n4) Respond to join request\n0) Exit\n> ");
        if (scanf("%d", &choice) != 1)
        {
            break;
        }

        int game_id, accept;
        switch (choice)
        {
            case 1:
                create_game(sock);
                break;
            case 2:
                list_games(sock);
                break;
            case 3:
                printf("Game id to join: ");
                if (scanf("%d", &game_id) == 1)
                {
                    join_game(sock, game_id);
                }
                break;
            case 4:
                printf("Accept? (1=yes, 0=no): ");
                if (scanf("%d", &accept) == 1)
                {
                    respond_to_join(sock, accept);
                }
                break;
        }
    } while (choice != 0);

    close(sock);
    return 0;
}