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

// Looks up the username of the client owning the given socket. Returns 1
// and fills 'out' if found, 0 otherwise (e.g. the client disconnected
// between the lookup that gave the caller this socket and this call).
int client_list_find_username(int sock, char *out);

#endif
