#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include "client_registry.h"
#include "net.h"

static Client *find_by_sock(int sock);
static int username_in_use(const char *username);

// The clients connected right now, with no lock of its own: the server calls
// every function of this file with its command_mutex held (server.c). That also
// keeps two threads from ever writing to the same socket at once, so the lines
// of different threads can't get mixed up.
//
// Slot map, not a packed list: clients[id - 1] IS the client with that id
// (id == 0 in a slot means it's free). Ids are handed out from free_ids (a
// stack of freed ids) first, falling back to next_id (a bump allocator) only
// once every id ever handed out is still in use.
typedef struct
{
    Client clients[MAX_CLIENTS]; // slot map: clients[id - 1] is the client with that id (id == 0 = free slot)
    int count;                   // how many slots are currently occupied
    int next_id;                 // bump allocator: next never-before-used id, used when free_ids is empty
    int free_ids[MAX_CLIENTS];   // stack of freed ids waiting to be reused
    int free_count;              // how many entries in free_ids are currently valid
} ClientList;

static ClientList clients = { .next_id = 1 };

Client client_list_add(int sock)
{
    Client new_client = { .id = -1, .sock = sock, .username = {0} };

    if (clients.count < MAX_CLIENTS)
    {
        int id = (clients.free_count > 0)
            ? clients.free_ids[--clients.free_count]
            : clients.next_id++;

        new_client.id = id;
        clients.clients[id - 1] = new_client;
        clients.count++;
    }

    return new_client;
}

ErrorCode client_list_set_username(int id, const char *username)
{
    // 'id' is the caller's own id: its slot is occupied.
    Client *client = &clients.clients[id - 1];

    if (client->username[0] != '\0')
    {
        return ERR_ALREADY_NAMED;
    }
    if (username_in_use(username))
    {
        return ERR_USERNAME_TAKEN;
    }
    strncpy(client->username, username, USERNAME_LEN);
    return ERR_NONE;
}

// Returns 1 if a connected client already uses 'username', ignoring case
// ("Anna" and "anna" would be indistinguishable in a lobby list). A client
// with no username yet has "" here, which never matches a valid name.
static int username_in_use(const char *username)
{
    for (int i = 0; i < clients.next_id - 1; i++)
    {
        if (clients.clients[i].id != 0 && strcasecmp(clients.clients[i].username, username) == 0)
        {
            return 1;
        }
    }
    return 0;
}

void client_list_remove(int id)
{
    if (id >= 1 && id <= MAX_CLIENTS && clients.clients[id - 1].id == id)
    {
        clients.clients[id - 1].id = 0; // 0 marks the slot free
        clients.free_ids[clients.free_count++] = id;
        clients.count--;
    }
}

int client_send_line(int sock, const char *fmt, ...)
{
    if (find_by_sock(sock) == NULL)
    {
        return -1;
    }

    va_list args;
    va_start(args, fmt);
    int result = vsend_line(sock, fmt, args);
    va_end(args);

    if (result == -1)
    {
        // The peer is gone, or it stopped reading and the send timed out
        // (client_handler sets the timeout). Either way the stream to it is no
        // good any more: a half-written line would corrupt it. Cut the
        // connection: the handler thread of that client, blocked in recv(),
        // sees the end and runs the normal disconnect. shutdown() rather than
        // close(): the fd belongs to the handler, and closing it here could
        // hand its number to a new connection. (Every line the server sends
        // fits in MAX_LINE, so -1 is never a line that was too long.)
        shutdown(sock, SHUT_RDWR);
    }
    return result;
}

void client_broadcast_except(int except_sock1, int except_sock2, const char *fmt, ...)
{
    char line[MAX_LINE];
    va_list args;
    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);

    for (int i = 0; i < clients.next_id - 1; i++)
    {
        int sock = clients.clients[i].sock;
        if (clients.clients[i].id != 0 && sock != except_sock1 && sock != except_sock2)
        {
            client_send_line(sock, "%s", line);
        }
    }
}

const char *client_list_username(int sock)
{
    Client *client = find_by_sock(sock);
    return client ? client->username : "";
}

int client_list_get_active_game(int sock)
{
    Client *client = find_by_sock(sock);
    return client ? client->active_game : 0;
}

void client_list_set_active_game(int sock, int game_id)
{
    Client *client = find_by_sock(sock);
    if (client)
    {
        client->active_game = game_id;
    }
}

// The client connected on 'sock', or NULL. A scan, because the games know
// their players only by socket.
static Client *find_by_sock(int sock)
{
    for (int i = 0; i < clients.next_id - 1; i++)
    {
        if (clients.clients[i].id != 0 && clients.clients[i].sock == sock)
        {
            return &clients.clients[i];
        }
    }
    return NULL;
}
