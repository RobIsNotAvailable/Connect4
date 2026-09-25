#ifndef COMMANDS_H
#define COMMANDS_H

#include "client_registry.h"

// The commands of the clients. Like everything that touches the registries,
// these run with the server's command_mutex held.

// Handles one line received from client 'me', connected on 'sock': the
// command it names, or "ERROR <command> <code>".
// 'line' is split in place.
void dispatch_command(int sock, Client *me, char *line);

// Applies the disconnect of 'sock' to every game it was involved in (as
// owner, pending joiner, or player2), and tells the others what changed.
void handle_disconnect(int sock);

// What to call a client in the server's log: its username, or "(no name)"
// while it hasn't chosen one yet.
const char *log_name(const Client *me);

#endif
