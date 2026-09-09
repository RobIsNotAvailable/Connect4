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
//
// Slot map, not a packed list: clients[id - 1] IS the client with that id
// (id == 0 in a slot means it's free). Ids are handed out from free_ids
// (a stack of freed ids) first, falling back to next_id (a bump allocator)
// only once every id ever handed out is still in use. Because ids are
// always in 1..MAX_CLIENTS, this gives O(1) add/remove/lookup-by-id with
// direct indexing instead of scanning the array for a matching id or sock.
// free_ids never needs more than MAX_CLIENTS slots: at most MAX_CLIENTS
// ids can be "in use" at once, so at most MAX_CLIENTS can be free too.
typedef struct
{
    Client clients[MAX_CLIENTS]; // slot map: clients[id - 1] is the client with that id (id == 0 = free slot)
    int count;                   // how many slots are currently occupied
    int next_id;                 // bump allocator: next never-before-used id, used when free_ids is empty
    int free_ids[MAX_CLIENTS];   // stack of freed ids waiting to be reused
    int free_count;              // how many entries in free_ids are currently valid
    pthread_mutex_t mutex;       // guards every field above
} ClientList;

static ClientList clients = {
    .count = 0,
    .next_id = 1,
    .free_count = 0,
    .mutex = PTHREAD_MUTEX_INITIALIZER
};

// Registers a new client and assigns it an id/username. Returns the
// assigned Client, or a Client with id == -1 if the registry is full.
Client client_list_add(int sock)
{
    Client new_client = { .id = -1, .sock = sock, .username = {0} };

    pthread_mutex_lock(&clients.mutex);

    if (clients.count < MAX_CLIENTS)
    {
        int id = (clients.free_count > 0)
            ? clients.free_ids[--clients.free_count]
            : clients.next_id++;

        new_client.id = id;
        snprintf(new_client.username, USERNAME_LEN, "Player%d", id);
        clients.clients[id - 1] = new_client; // direct slot write, no scan
        clients.count++;
    }

    pthread_mutex_unlock(&clients.mutex);

    return new_client;
}
// Removes the client with the given id from the registry, if present, and
// returns its id to the free-id stack for reuse. Callers already know the
// id (it's handed back by client_list_add), so this indexes straight into
// its slot instead of scanning for it.
void client_list_remove(int id)
{
    pthread_mutex_lock(&clients.mutex);

    if (id >= 1 && id <= MAX_CLIENTS && clients.clients[id - 1].id == id)
    {
        clients.clients[id - 1].id = 0; // 0 marks the slot free
        clients.free_ids[clients.free_count++] = id;
        clients.count--;
    }


    pthread_mutex_unlock(&clients.mutex);
}

// Looks up the username of the client owning the given socket. Returns
// 1 and fills 'out' if found, 0 otherwise (e.g. client disconnected
// between the lookup that gave us the socket and this call). Unlike
// add/remove this is genuinely keyed by socket, not id, so it still has
// to scan; it's currently unused but kept correct against the slot map
// (iterates only up to the highest id ever handed out, skipping free slots).
int client_list_find_username(int sock, char *out)
{
    int found = 0;

    pthread_mutex_lock(&clients.mutex);

    for (int i = 0; i < clients.next_id - 1; i++)
    {
        if (clients.clients[i].id != 0 && clients.clients[i].sock == sock)
        {
            strncpy(out, clients.clients[i].username, USERNAME_LEN);
            found = 1;
            break;
        }
    }

    pthread_mutex_unlock(&clients.mutex);

    return found;
}

#define MAX_GAMES 256

// Bookkeeping for a single game: identity + owner + current state.
// owner_sock is kept (not just the username) so a future phase can send
// the join notification directly to the owner's socket without another
// lookup in the client registry.
typedef struct
{
    int id;
    int owner_sock;
    char owner_username[USERNAME_LEN];
    GameState state;
    int pending_joiner_sock; // -1 if no join request is currently pending
    int player2_sock;        // -1 until the game moves to PLAYING
} Game;

// Outcomes of game_registry_set_pending(), used to pick which CMD_ERROR
// (if any) to send back to the joiner.
typedef enum
{
    JOIN_OK,
    JOIN_ERR_NOT_FOUND,
    JOIN_ERR_NOT_WAITING,
    JOIN_ERR_SELF_JOIN,
    JOIN_ERR_ALREADY_PENDING
} JoinSetResult;

// Outcomes of game_registry_resolve_join().
typedef enum
{
    RESOLVE_OK,
    RESOLVE_ERR_NOT_FOUND,
    RESOLVE_ERR_NOT_OWNER,
    RESOLVE_ERR_NO_PENDING
} ResolveResult;

// Thread-safe registry of games, mirroring ClientList: a slot map indexed
// directly by id (games[id - 1] IS the game with that id, id == 0 means
// free), with the same free-id stack scheme so ids get reused instead of
// growing forever, and lookups by id are O(1) instead of a scan.
typedef struct
{
    Game games[MAX_GAMES];      // slot map: games[id - 1] is the game with that id (id == 0 = free slot)
    int count;                  // how many slots are currently occupied
    int next_id;                // bump allocator: next never-before-used id, used when free_ids is empty
    int free_ids[MAX_GAMES];    // stack of freed ids waiting to be reused
    int free_count;             // how many entries in free_ids are currently valid
    pthread_mutex_t mutex;      // guards every field above
} GameRegistry;

static GameRegistry g_games = {
    .count = 0,
    .next_id = 1,
    .free_count = 0,
    .mutex = PTHREAD_MUTEX_INITIALIZER
};

// Creates a new game owned by (owner_sock, owner_username), state WAITING.
// Returns the created Game, or a Game with id == -1 if the registry is full.
// No check on whether owner already owns another game: not required yet
// (Phase 2), to be decided/added when join/accept semantics are defined.
Game game_registry_create(int owner_sock, const char *owner_username)
{
    Game new_game = {
        .id = -1,
        .owner_sock = owner_sock,
        .state = GAME_WAITING,
        .pending_joiner_sock = -1,
        .player2_sock = -1
    };
    strncpy(new_game.owner_username, owner_username, USERNAME_LEN);

    pthread_mutex_lock(&g_games.mutex);

    if (g_games.count < MAX_GAMES)
    {
        int id = (g_games.free_count > 0)
            ? g_games.free_ids[--g_games.free_count]
            : g_games.next_id++;

        new_game.id = id;
        g_games.games[id - 1] = new_game; // direct slot write, no scan
        g_games.count++;
    }

    pthread_mutex_unlock(&g_games.mutex);

    return new_game;
}

// Removes every game owned by owner_sock from the registry (e.g. when the
// owner disconnects), returning their ids to the free-id stack for reuse.
// A disconnected owner can no longer accept joins or play, so its games
// (WAITING or PLAYING) are dropped rather than kept around forever.
//
// A single owner can hold several games (create doesn't cap that - see its
// comment), so this still has to check each one; unlike a single-game
// lookup by id it isn't a single-slot operation. It's bounded by the
// highest id ever handed out rather than MAX_GAMES, and only runs once per
// disconnect (not per request), so the scan is cheap in practice.
void game_registry_remove_by_owner(int owner_sock)
{
    pthread_mutex_lock(&g_games.mutex);

    for (int i = 0; i < g_games.next_id - 1; i++)
    {
        if (g_games.games[i].id != 0 && g_games.games[i].owner_sock == owner_sock)
        {
            g_games.free_ids[g_games.free_count++] = g_games.games[i].id;
            g_games.games[i].id = 0; // 0 marks the slot free; no compaction needed
            g_games.count--;
        }
    }

    pthread_mutex_unlock(&g_games.mutex);
}

// Fills 'out' with up to MAX_GAMES_IN_LIST currently WAITING games.
// Returns how many were copied. Listing "all games in state X" is
// inherently a full pass (there's no single slot to jump to for that),
// so this is the one place the registry still walks its range - bounded
// by the highest id ever handed out and skipping free slots.
int game_registry_list_waiting(GameInfo *out)
{
    int n = 0;

    pthread_mutex_lock(&g_games.mutex);

    for (int i = 0; i < g_games.next_id - 1 && n < MAX_GAMES_IN_LIST; i++)
    {
        if (g_games.games[i].id != 0 && g_games.games[i].state == GAME_WAITING)
        {
            out[n].game_id = g_games.games[i].id;
            strncpy(out[n].owner_username, g_games.games[i].owner_username, USERNAME_LEN);
            out[n].state = g_games.games[i].state;
            n++;
        }
    }

    pthread_mutex_unlock(&g_games.mutex);

    return n;
}

// Atomically validates a join request and, if valid, marks the game as
// having a pending joiner. 'out_game' is filled with the game's state
// after the call (needed by the caller to know the owner_sock to notify).
// game_id comes straight from the client, so it's bounds-checked before
// being used as an index; games[game_id - 1].id == game_id confirms that
// slot is both in use and actually holds that game (not stale/free).
JoinSetResult game_registry_set_pending(int game_id, int joiner_sock, Game *out_game)
{
    JoinSetResult result = JOIN_ERR_NOT_FOUND;

    pthread_mutex_lock(&g_games.mutex);

    if (game_id >= 1 && game_id <= MAX_GAMES && g_games.games[game_id - 1].id == game_id)
    {
        int i = game_id - 1;

        if (g_games.games[i].owner_sock == joiner_sock)
        {
            result = JOIN_ERR_SELF_JOIN;
        }
        else if (g_games.games[i].state != GAME_WAITING)
        {
            result = JOIN_ERR_NOT_WAITING;
        }
        else if (g_games.games[i].pending_joiner_sock != -1)
        {
            result = JOIN_ERR_ALREADY_PENDING;
        }
        else
        {
            g_games.games[i].pending_joiner_sock = joiner_sock;
            result = JOIN_OK;
        }

        *out_game = g_games.games[i];
    }

    pthread_mutex_unlock(&g_games.mutex);

    return result;
}

// Atomically validates and applies the owner's accept/reject decision.
// On accept: state -> PLAYING, player2_sock set, pending cleared.
// On reject: state stays WAITING, pending cleared (free to accept a new join).
// 'out_game' is filled with the game's state after the call (needed by the
// caller to know which socket to notify with the CMD_JOIN_RESULT).
// game_id comes straight from the client: same bounds/occupancy check as
// game_registry_set_pending before it's used as a direct index.
ResolveResult game_registry_resolve_join(int game_id, int owner_sock, int accepted, Game *out_game)
{
    ResolveResult result = RESOLVE_ERR_NOT_FOUND;

    pthread_mutex_lock(&g_games.mutex);

    if (game_id >= 1 && game_id <= MAX_GAMES && g_games.games[game_id - 1].id == game_id)
    {
        int i = game_id - 1;

        if (g_games.games[i].owner_sock != owner_sock)
        {
            result = RESOLVE_ERR_NOT_OWNER;
        }
        else if (g_games.games[i].pending_joiner_sock == -1)
        {
            result = RESOLVE_ERR_NO_PENDING;
        }
        else
        {
            int joiner_sock = g_games.games[i].pending_joiner_sock;

            if (accepted)
            {
                g_games.games[i].state = GAME_PLAYING;
                g_games.games[i].player2_sock = joiner_sock;
            }
            g_games.games[i].pending_joiner_sock = -1;
            result = RESOLVE_OK;

            *out_game = g_games.games[i];
            // Restore the joiner socket in the copy handed back to the
            // caller: on reject it was just cleared above, but the
            // caller still needs it to notify that same joiner.
            out_game->pending_joiner_sock = joiner_sock;
        }

        if (result != RESOLVE_OK)
        {
            *out_game = g_games.games[i];
        }
    }

    pthread_mutex_unlock(&g_games.mutex);

    return result;
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

        // Each case sends directly to whichever socket(s) need the result:
        // replies aren't always to the sender anymore (e.g. a join request
        // is answered by pushing a notification to the game owner).
        switch (pktptr->header.type)
        {
            case CMD_CREATE_GAME:
            {
                Game g = game_registry_create(client_sock, me.username);

                Packet reply;
                reply.header.type = CMD_GAME_CREATED;
                reply.header.payload_size = sizeof(GameCreated);
                reply.payload.game_created.game_id = g.id;
                send(client_sock, &reply, sizeof(Packet), 0);
                break;
            }
            case CMD_LIST_GAMES:
            {
                GameList list;
                list.count = game_registry_list_waiting(list.games);

                Packet reply;
                reply.header.type = CMD_GAME_LIST;
                reply.header.payload_size = sizeof(GameList);
                reply.payload.game_list = list;
                send(client_sock, &reply, sizeof(Packet), 0);
                break;
            }
            case CMD_JOIN_GAME:
            {
                int game_id = pktptr->payload.join_request.game_id;
                Game g;
                JoinSetResult jr = game_registry_set_pending(game_id, client_sock, &g);

                if (jr == JOIN_OK)
                {
                    // Push the notification straight to the owner's socket:
                    // the owner is not the one who sent this command.
                    Packet notify;
                    notify.header.type = CMD_JOIN_NOTIFY;
                    notify.header.payload_size = sizeof(JoinNotify);
                    notify.payload.join_notify.game_id = game_id;
                    strncpy(notify.payload.join_notify.joiner_username, me.username, USERNAME_LEN);
                    send(g.owner_sock, &notify, sizeof(Packet), 0);
                    // No reply to the joiner yet: the outcome (CMD_JOIN_RESULT)
                    // arrives later, once the owner answers.
                }
                else
                {
                    printf("[SERVER] [%s] Join game %d rejected: reason=%d\n", me.username, game_id, jr);
                    Packet reply;
                    reply.header.type = CMD_ERROR;
                    reply.header.payload_size = 0;
                    send(client_sock, &reply, sizeof(Packet), 0);
                }
                break;
            }
            case CMD_JOIN_RESPONSE:
            {
                int game_id = pktptr->payload.join_response.game_id;
                int accepted = pktptr->payload.join_response.accepted;
                Game g;
                ResolveResult rr = game_registry_resolve_join(game_id, client_sock, accepted, &g);

                if (rr == RESOLVE_OK)
                {
                    Packet result_pkt;
                    result_pkt.header.type = CMD_JOIN_RESULT;
                    result_pkt.header.payload_size = sizeof(JoinResult);
                    result_pkt.payload.join_result.game_id = game_id;
                    result_pkt.payload.join_result.accepted = accepted;
                    // g.pending_joiner_sock is restored by resolve_join to
                    // the joiner's socket regardless of accept/reject, so
                    // it's always the right destination for the result.
                    send(g.pending_joiner_sock, &result_pkt, sizeof(Packet), 0);
                }
                else
                {
                    printf("[SERVER] [%s] Join response for game %d rejected: reason=%d\n", me.username, game_id, rr);
                    Packet reply;
                    reply.header.type = CMD_ERROR;
                    reply.header.payload_size = 0;
                    send(client_sock, &reply, sizeof(Packet), 0);
                }
                break;
            }
            default:
            {
                printf("[SERVER] [%s] Unhandled command type: %d\n", me.username, pktptr->header.type);
                Packet reply;
                reply.header.type = CMD_ERROR;
                reply.header.payload_size = 0;
                send(client_sock, &reply, sizeof(Packet), 0);
                break;
            }
        }
    }
    if (comm_status == 0)
    {
        printf("[SERVER] Client %s (socket %d) disconnected\n", me.username, client_sock);
    }
    else
    {
        perror("[SERVER] Error in receiving data from client\n");
    }

    client_list_remove(me.id);
    game_registry_remove_by_owner(client_sock);
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