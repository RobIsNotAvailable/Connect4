#ifndef CLIENT_REGISTRY_H
#define CLIENT_REGISTRY_H

#include "protocol.h"

// Maximum number of clients that can be connected to the server at once.
#define MAX_CLIENTS 64

// Bookkeeping for a single connected client: identity + its socket. The
// username is empty ("") until the client picks one with SET_USERNAME
// (docs/protocol.md §2), and never changes after that.
typedef struct
{
    int id;
    int sock;
    char username[USERNAME_LEN];
} Client;

// Outcomes of client_list_set_username(), used by the caller to pick which
// ERROR code (if any) to send back.
typedef enum
{
    SET_USERNAME_OK,
    SET_USERNAME_ERR_ALREADY_NAMED,
    SET_USERNAME_ERR_TAKEN
} SetUsernameResult;

// Registers a new client and assigns it an id, with no username yet.
// Returns the assigned Client, or a Client with id == -1 if the registry
// is full.
Client client_list_add(int sock);

// Gives the client with the given id its username, once: fails if it
// already has one, or if another connected client already uses the same
// name (compared ignoring case). 'username' must already have passed
// is_valid_name. The check and the assignment happen under one lock, so two
// clients asking for the same name at once can't both get it.
SetUsernameResult client_list_set_username(int id, const char *username);

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
