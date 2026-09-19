#ifndef GAME_REGISTRY_H
#define GAME_REGISTRY_H

#include "board.h"
#include "protocol.h"

// Registry capacity: how many games can exist at once. Exposed here (not
// just in game_registry.c) so a caller can size a buffer to match, e.g.
// the DisconnectEvent array below - one disconnecting client can affect
// at most one game per occupied slot.
#define MAX_GAMES 256

// How many games a single client can own at once, whatever state they are
// in (WAITING, PLAYING or FINISHED all count until the game is removed).
#define MAX_GAMES_PER_OWNER 3

// Bookkeeping for a single game: identity + owner + current state.
// owner_sock is kept (not just the username) so the join notification can
// be sent directly to the owner's socket without another lookup in the
// client registry.
typedef struct
{
    int id;
    char name[ROOM_NAME_LEN]; // chosen by the owner at creation, shown in the lobby
    int owner_sock;
    char owner_username[USERNAME_LEN];
    RoomState state;
    int pending_joiner_sock; // -1 if no join request is currently pending
    int player2_sock;        // -1 until the game moves to PLAYING
    Board board;
    int turn;   // 1 or 2: who moves next. Unused (left at 0) while WAITING
    int winner; // valid only once state == GAME_FINISHED: 1, 2, or 0 for a draw
} Game;

// Outcomes of game_create(), used by the caller to pick which ERROR code
// (if any) to send back to the creator.
typedef enum
{
    CREATE_OK,
    CREATE_ERR_SERVER_FULL,
    CREATE_ERR_TOO_MANY_GAMES
} CreateResult;

// Outcomes of game_registry_set_pending(), used by the caller to pick
// which ERROR code (if any) to send back to the joiner.
typedef enum
{
    JOIN_OK,
    JOIN_ERR_NOT_FOUND,
    JOIN_ERR_NOT_WAITING,
    JOIN_ERR_SELF_JOIN,
    JOIN_ERR_ALREADY_PENDING,
    JOIN_ERR_ALREADY_PLAYING
} JoinSetResult;

// Outcomes of game_registry_resolve_join().
typedef enum
{
    RESOLVE_OK,
    RESOLVE_ERR_NOT_FOUND,
    RESOLVE_ERR_NOT_OWNER,
    RESOLVE_ERR_NO_PENDING,
    RESOLVE_ERR_ALREADY_PLAYING
} ResolveResult;

// Outcomes of game_registry_apply_move().
typedef enum
{
    MOVE_OK,
    MOVE_ERR_NOT_FOUND,
    MOVE_ERR_NOT_PLAYER,
    MOVE_ERR_NOT_PLAYING,
    MOVE_ERR_NOT_YOUR_TURN,
    MOVE_ERR_INVALID_COLUMN,
    MOVE_ERR_COLUMN_FULL
} MoveResult;

// What happened to one game because a client disconnected
// (docs/protocol.md §8), and which notification(s) the caller needs to
// send because of it. The disconnecting socket itself is always one of
// the sockets a GAME_CLOSED broadcast excludes - the caller already
// knows it, so it isn't repeated here.
typedef enum
{
    DISCONNECT_JOIN_CANCELLED,        // was a pending joiner: notify_sock (the owner) gets JOIN_CANCELLED
    DISCONNECT_GAME_CLOSED,           // was owner of a WAITING game: broadcast GAME_CLOSED, no direct recipient
    DISCONNECT_OPPONENT_LEFT_PLAYING, // was owner/player2 of a PLAYING game: notify_sock gets OPPONENT_LEFT, then broadcast GAME_CLOSED
    DISCONNECT_OPPONENT_LEFT_FINISHED // was owner/player2 of a FINISHED game: notify_sock gets OPPONENT_LEFT only
} DisconnectEventType;

typedef struct
{
    DisconnectEventType type;
    int game_id;
    int notify_sock; // direct-message recipient for this event, or -1 if none
} DisconnectEvent;

// Creates a new game called 'name', owned by (owner_sock, owner_username),
// state WAITING, unless the registry is full or the owner already owns
// MAX_GAMES_PER_OWNER games. 'name' must already have passed
// is_valid_name (at most ROOM_NAME_LEN - 1 characters): it is not checked
// again here. Names are not unique - two games can share one. On
// CREATE_OK, 'out_game' is filled with the new game (needed by the caller
// for its id); on any other result it is left untouched.
CreateResult game_create(int owner_sock, const char *owner_username, const char *name, Game *out_game);

// Removes or updates every game where 'sock' is the owner, the pending
// joiner, or player2, because that client just disconnected. Fills
// 'events' (caller-allocated, at least MAX_GAMES entries) with what
// happened to each affected game and what to notify about it. Returns
// how many entries were filled.
int game_registry_handle_disconnect(int sock, DisconnectEvent *events);

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

// Validates and applies one MOVE (docs/protocol.md §5): checks the game
// exists, 'player_sock' is one of its two players, the game is
// currently PLAYING, and it's that player's turn - then drops the disc
// into 'column' and flips whose turn it is. 'out_game' is filled with
// the game's state after the call (needed by the caller to build
// GAME_STATE).
MoveResult game_registry_apply_move(int game_id, int player_sock, int column, Game *out_game);

#endif
