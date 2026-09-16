#ifndef GAME_REGISTRY_H
#define GAME_REGISTRY_H

#include "protocol.h"

// Bookkeeping for a single game: identity + owner + current state.
// owner_sock is kept (not just the username) so the join notification can
// be sent directly to the owner's socket without another lookup in the
// client registry.
typedef struct
{
    int id;
    int owner_sock;
    char owner_username[USERNAME_LEN];
    RoomState state;
    int pending_joiner_sock; // -1 if no join request is currently pending
    int player2_sock;        // -1 until the game moves to PLAYING
} Game;

// Outcomes of game_registry_set_pending(), used by the caller to pick
// which ERROR code (if any) to send back to the joiner.
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

// Creates a new game owned by (owner_sock, owner_username), state WAITING.
// Returns the created Game, or a Game with id == -1 if the registry is
// full. No check on whether owner already owns another game: not required
// yet, to be decided/added when join/accept semantics are defined.
Game game_create(int owner_sock, const char *owner_username);

// Removes every game owned by owner_sock from the registry (e.g. when the
// owner disconnects), returning their ids to the free-id stack for reuse.
void game_registry_remove_by_owner(int owner_sock);

// Fills 'out' with up to MAX_GAMES_IN_LIST currently WAITING games.
// Returns how many were copied.
int game_registry_list_waiting(GameInfo *out);

// Atomically validates a join request and, if valid, marks the game as
// having a pending joiner. 'out_game' is filled with the game's state
// after the call (needed by the caller to know the owner_sock to notify).
JoinSetResult game_registry_set_pending(int game_id, int joiner_sock, Game *out_game);

// Atomically validates and applies the owner's accept/reject decision.
// On accept: state -> PLAYING, player2_sock set, pending cleared. On
// reject: state stays WAITING, pending cleared. 'out_game' is filled with
// the game's state after the call, with pending_joiner_sock always equal
// to the joiner that was just resolved (accepted or not), so the caller
// knows who to notify with JOIN_RESULT.
ResolveResult game_registry_resolve_join(int game_id, int owner_sock, int accepted, Game *out_game);

#endif
