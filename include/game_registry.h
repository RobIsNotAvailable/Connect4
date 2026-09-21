#ifndef GAME_REGISTRY_H
#define GAME_REGISTRY_H

#include "board.h"
#include "protocol.h"

// Registry capacity: how many games can exist at once. Exposed here (not
// just in game_registry.c) so a caller can size a buffer to match, e.g.
// the LeaveEvent array below - one disconnecting client can affect at most
// one game per occupied slot.
#define MAX_GAMES 256

// How many games a single client can own at once, whatever state they are
// in (WAITING, PLAYING or FINISHED all count until the game is removed).
#define MAX_GAMES_PER_OWNER 3

// Bookkeeping for a single game: identity + owner + current state.
// Players are identified by socket only, never by username: a username is
// looked up in the client registry when a message needs it. Usernames
// never change once chosen, so a lookup is always right, while a copy
// stored here would have to be kept in step whenever the owner changes.
typedef struct
{
    int id;
    char name[ROOM_NAME_LEN]; // chosen by the owner at creation, shown in the lobby
    int owner_sock;
    RoomState state;
    int pending_joiner_sock; // -1 if no join request is currently pending
    int player2_sock;        // -1 while there is no second player (WAITING)
    Board board;
    int turn;   // 1 or 2: who moves next. Unused (left at 0) while WAITING
    int winner; // valid only once state == GAME_FINISHED: 1, 2, or 0 for a draw
    // Rematch votes (docs/protocol.md §7): meaningful only while
    // state == GAME_FINISHED. Both are cleared whenever a game starts, so a
    // vote never outlives the game it was cast for.
    int owner_wants_rematch;
    int player2_wants_rematch;
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

// Outcomes of game_registry_rematch(). The first two are successes: the
// caller picks what to send from which one it got.
typedef enum
{
    REMATCH_WAITING,             // vote recorded, the opponent has not voted yet
    REMATCH_STARTED,             // both players want it: the game restarted
    REMATCH_ERR_NOT_FOUND,
    REMATCH_ERR_NOT_PLAYER,
    REMATCH_ERR_NOT_FINISHED,
    REMATCH_ERR_ALREADY_VOTED    // this player already asked for the rematch
} RematchResult;

// What happened to one game because a client left it, and which
// notification(s) the caller needs to send because of it (docs/protocol.md
// §8). The client that left is not repeated here: the caller already knows
// who it is.
//
// A room outlives the players in it: when one of two players leaves, the
// other stays and the game goes back to WAITING with a fresh board (if the
// owner is the one who left, the other player becomes the owner). It is only
// removed when nobody is left to own it.
typedef enum
{
    LEAVE_JOIN_CANCELLED, // was a pending joiner: notify_sock (the owner) gets JOIN_CANCELLED
    LEAVE_ROOM_CLOSED,    // the game is gone: broadcast GAME_CLOSED, no direct recipient
    LEAVE_ROOM_REOPENED   // notify_sock stays as the owner, game WAITING again: notify_sock gets OPPONENT_LEFT, everyone else NEW_GAME
} LeaveEventType;

typedef struct
{
    LeaveEventType type;
    int game_id;
    char name[ROOM_NAME_LEN]; // the game's name, for the NEW_GAME of a reopened room
    int notify_sock;          // direct-message recipient for this event, or -1 if none
} LeaveEvent;

// Outcomes of game_registry_leave(), used by the caller to pick which ERROR
// code (if any) to send back.
typedef enum
{
    LEAVE_OK,
    LEAVE_ERR_NOT_FOUND,
    LEAVE_ERR_NOT_PLAYER
} LeaveResult;

// Creates a new game called 'name', owned by 'owner_sock', state WAITING,
// unless the registry is full or the owner already owns
// MAX_GAMES_PER_OWNER games. 'name' must already have passed
// is_valid_name (at most ROOM_NAME_LEN - 1 characters): it is not checked
// again here. Names are not unique - two games can share one. On
// CREATE_OK, 'out_game' is filled with the new game (needed by the caller
// for its id); on any other result it is left untouched.
CreateResult game_create(int owner_sock, const char *name, Game *out_game);

// Applies a disconnect to every game where 'sock' is the owner, the pending
// joiner, or player2: a pending request is cancelled, and an owner or
// player2 leaves the game (see LeaveEventType). Fills 'events'
// (caller-allocated, at least MAX_GAMES entries) with what happened to each
// affected game and what to notify about it. Returns how many entries were
// filled.
int game_registry_handle_disconnect(int sock, LeaveEvent *events);

// Makes 'sock' leave game 'game_id' on its own request (LEAVE_GAME): same
// rules as a disconnect, whatever the game's state. 'sock' must be the
// game's owner or player2 - a pending joiner has not joined anything yet.
// On LEAVE_OK, 'event' is filled with what the caller has to notify (see
// LeaveEventType); on any other result it is left untouched.
LeaveResult game_registry_leave(int game_id, int sock, LeaveEvent *event);

// Fills 'out' with up to MAX_GAMES_IN_LIST currently WAITING games.
// Returns how many were copied.
int game_registry_list_waiting(GameInfo *out);

// Fills 'out' (caller-allocated, at least MAX_GAMES_PER_OWNER entries) with
// every game owned by 'owner_sock', whatever its state. Returns how many
// were copied.
int game_registry_list_owned(int owner_sock, GameInfo *out);

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

// Records that 'sock' wants a rematch of the FINISHED game 'game_id'
// (docs/protocol.md §7). When both players have asked, the game restarts in
// place: PLAYING, empty board, player 1 to move, votes cleared. 'out_game' is
// filled with the game's state after the call (needed by the caller to know
// who to notify), for every result except NOT_FOUND, where it is left
// untouched.
RematchResult game_registry_rematch(int game_id, int sock, Game *out_game);

#endif
