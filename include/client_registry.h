#ifndef CLIENT_REGISTRY_H
#define CLIENT_REGISTRY_H

#include "protocol.h"

// Maximum number of clients that can be connected to the server at once.
#define MAX_CLIENTS 64

// Bookkeeping for a single connected client: identity + its socket.
typedef struct
{
    int id;
    int sock;
    char username[USERNAME_LEN];
} Client;

// Registers a new client and assigns it an id/username. Returns the
// assigned Client, or a Client with id == -1 if the registry is full.
Client client_list_add(int sock);

// Removes the client with the given id from the registry, if present, and
// returns its id to the free-id stack for reuse.
void client_list_remove(int id);

// Sends one protocol line to the client connected on 'sock', serialized
// against any other thread sending to the same client so their lines never
// interleave. Returns 0 on success, -1 if no client is connected on 'sock'
// anymore (e.g. it disconnected) or the send fails.
int client_send_line(int sock, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

// Sends one protocol line to every currently connected client except up
// to two sockets (pass -1 for either/both to exclude no one). Used for
// the docs/protocol.md notifications, which never reach the game's
// own owner/player2. Best-effort per recipient, like client_send_line: a
// client that disconnects mid-broadcast is silently skipped.
void client_broadcast_except(int except_sock1, int except_sock2, const char *fmt, ...) __attribute__((format(printf, 3, 4)));

// Looks up the username of the client owning the given socket. Returns 1
// and fills 'out' if found, 0 otherwise (e.g. the client disconnected
// between the lookup that gave the caller this socket and this call).
int client_list_find_username(int sock, char *out);

#endif
