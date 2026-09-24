#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include "client_registry.h"
#include "net.h"

// The public functions below are already declared in client_registry.h.
// These are the private helpers defined in this file, forward-declared
// here so each can be defined after its first caller: username_in_use after
// client_list_set_username, find_client_by_sock after client_send_line,
// client_by_sock_locked after client_list_find_username.
static int username_in_use(const char *username);
static int find_client_by_sock(int sock, int *out_id);
static Client *client_by_sock_locked(int sock);

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
//
// send_mutexes[id - 1] serializes writes to that client's socket: several
// threads may send to the same client (its own handler replying, another
// handler pushing a notification), and without a lock a partial send()
// from one could be interleaved with another's line. They live in their
// own array, not inside Client, because client_list_add overwrites the
// whole Client slot and would clobber a mutex stored there.
//
// Lock order: a send mutex may be held while taking 'mutex', never the
// reverse. That way a send() blocked on a slow client only ever holds its
// own send mutex, and never stalls the whole registry.
typedef struct
{
    Client clients[MAX_CLIENTS]; // slot map: clients[id - 1] is the client with that id (id == 0 = free slot)
    int count;                   // how many slots are currently occupied
    int next_id;                 // bump allocator: next never-before-used id, used when free_ids is empty
    int free_ids[MAX_CLIENTS];   // stack of freed ids waiting to be reused
    int free_count;              // how many entries in free_ids are currently valid
    pthread_mutex_t mutex;       // guards every field above
    pthread_mutex_t send_mutexes[MAX_CLIENTS]; // send_mutexes[id - 1] guards writes to that client's socket
} ClientList;

static ClientList clients = {
    .count = 0,
    .next_id = 1,
    .free_count = 0,
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    // GCC range designator: initializes every element of the array.
    .send_mutexes = { [0 ... MAX_CLIENTS - 1] = PTHREAD_MUTEX_INITIALIZER }
};

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
        clients.clients[id - 1] = new_client; // direct slot write, no scan
        clients.count++;
    }

    pthread_mutex_unlock(&clients.mutex);

    return new_client;
}

SetUsernameResult client_list_set_username(int id, const char *username)
{
    SetUsernameResult result;

    pthread_mutex_lock(&clients.mutex);

    // 'id' is the caller's own id, and the caller is that client's own
    // handler thread, which only removes the client after it stops
    // calling this: the slot is always occupied here.
    Client *client = &clients.clients[id - 1];

    if (client->username[0] != '\0')
    {
        result = SET_USERNAME_ERR_ALREADY_NAMED;
    }
    else if (username_in_use(username))
    {
        result = SET_USERNAME_ERR_TAKEN;
    }
    else
    {
        strncpy(client->username, username, USERNAME_LEN);
        result = SET_USERNAME_OK;
    }

    pthread_mutex_unlock(&clients.mutex);

    return result;
}

// Returns 1 if a connected client already uses 'username', ignoring case
// ("Anna" and "anna" would be indistinguishable in a lobby list). A client
// with no username yet has "" here, which never matches a valid name.
// Caller must hold 'mutex'.
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
    if (id < 1 || id > MAX_CLIENTS)
    {
        return;
    }

    // Takes the client's send mutex first, so it waits for any send already
    // in progress to this client; once it returns, client_send_line won't
    // find the client anymore, and the caller can safely close() the socket.
    pthread_mutex_lock(&clients.send_mutexes[id - 1]);
    pthread_mutex_lock(&clients.mutex);

    if (clients.clients[id - 1].id == id)
    {
        clients.clients[id - 1].id = 0; // 0 marks the slot free
        clients.free_ids[clients.free_count++] = id;
        clients.count--;
    }

    pthread_mutex_unlock(&clients.mutex);
    pthread_mutex_unlock(&clients.send_mutexes[id - 1]);
}

int client_send_line(int sock, const char *fmt, ...)
{
    int id;
    int slot = find_client_by_sock(sock, &id);
    if (slot == -1)
    {
        return -1;
    }

    pthread_mutex_lock(&clients.send_mutexes[slot]);

    // The registry lock was released above, so the client may have been
    // removed before we got its send mutex: check it's still the same one.
    pthread_mutex_lock(&clients.mutex);
    int still_connected = clients.clients[slot].id == id && clients.clients[slot].sock == sock;
    pthread_mutex_unlock(&clients.mutex);

    int result = -1;
    if (still_connected)
    {
        va_list args;
        va_start(args, fmt);
        result = vsend_line(sock, fmt, args);
        va_end(args);

        if (result == -1)
        {
            // The peer is gone, or it stopped reading and the send timed out
            // (client_handler sets the timeout). Either way the stream to it
            // is no good any more: a half-written line would corrupt it. Cut
            // the connection: the handler thread of that client, blocked in
            // recv(), sees the end and runs the normal disconnect. shutdown()
            // rather than close(): the fd belongs to the handler, and closing
            // it here could hand its number to a new connection.
            // (Every line the server sends fits in MAX_LINE, so -1 is never
            // a line that was too long.)
            shutdown(sock, SHUT_RDWR);
        }
    }

    pthread_mutex_unlock(&clients.send_mutexes[slot]);

    return result;
}

// Scans the registry for the client connected on 'sock' (keyed by socket,
// not id: callers here only have sockets - e.g. a Game only remembers
// owner_sock/player2_sock, not client ids). Returns its slot index and
// fills 'out_id' with its id, or -1 if none is connected on that socket
// right now. Only used by client_send_line, which re-checks the client is
// still there (under the send mutex) before actually using the slot - see
// its comment for why that recheck is needed.
static int find_client_by_sock(int sock, int *out_id)
{
    int slot = -1;

    pthread_mutex_lock(&clients.mutex);
    for (int i = 0; i < clients.next_id - 1; i++)
    {
        if (clients.clients[i].id != 0 && clients.clients[i].sock == sock)
        {
            slot = i;
            *out_id = clients.clients[i].id;
            break;
        }
    }
    pthread_mutex_unlock(&clients.mutex);

    return slot;
}

// Snapshots the sockets to notify under 'mutex', then sends to each one
// outside the lock: client_send_line takes 'mutex' itself (briefly) to
// re-check the client is still connected, so still holding it here while
// calling client_send_line would deadlock.
void client_broadcast_except(int except_sock1, int except_sock2, const char *fmt, ...)
{
    char line[MAX_LINE];
    va_list args;
    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);

    int targets[MAX_CLIENTS];
    int n = 0;
    
    pthread_mutex_lock(&clients.mutex);
    
    for (int i = 0; i < clients.next_id - 1; i++)
    {
        int sock = clients.clients[i].sock;
        if (clients.clients[i].id != 0 && sock != except_sock1 && sock != except_sock2)
        {
            targets[n++] = sock;
        }
    }
    pthread_mutex_unlock(&clients.mutex);

    for (int i = 0; i < n; i++)
    {
        client_send_line(targets[i], "%s", line);
    }
}

// Not reused via find_client_by_sock: this needs the scan and the
// strncpy to happen under the same, continuously-held lock (otherwise the
// slot could be freed and reassigned to a different client in between,
// and this would silently copy the wrong username instead of reporting
// "not found").
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

// Returns the client connected on 'sock', or NULL if there is none. The
// caller must hold 'mutex' for as long as it uses the pointer: the slot can
// be reassigned to another client as soon as the lock is released.
static Client *client_by_sock_locked(int sock)
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

int client_list_get_active_game(int sock)
{
    pthread_mutex_lock(&clients.mutex);

    Client *client = client_by_sock_locked(sock);
    int game_id = client ? client->active_game : 0;

    pthread_mutex_unlock(&clients.mutex);

    return game_id;
}

void client_list_set_active_game(int sock, int game_id)
{
    pthread_mutex_lock(&clients.mutex);

    Client *client = client_by_sock_locked(sock);
    if (client)
    {
        client->active_game = game_id;
    }

    pthread_mutex_unlock(&clients.mutex);
}
