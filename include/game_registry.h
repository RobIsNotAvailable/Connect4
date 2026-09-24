#ifndef GAME_REGISTRY_H
#define GAME_REGISTRY_H

#include "board.h"
#include "protocol.h"

// The games on the server. Not thread-safe by itself: every function must be
// called with the server's command_mutex held (server.c). The functions that
// check a request return ERR_NONE or the error code to send back
// (docs/protocol.md §1.5); on success they copy the game, as it is after the
// change, into 'out_game'.

// Registry capacity: how many games can exist at once. Exposed here so a
// caller can size a buffer to match, e.g. the LeaveEvent array below: one
// disconnecting client can affect at most one game per occupied slot.
#define MAX_GAMES 256

// How many games a single client can be in at once (docs/protocol.md §5.1): the
// ones it is a player of, in any state - a room waiting for an opponent counts,
// and a finished game keeps its place until the player leaves it. It also
// bounds the reply to LIST_MY_GAMES, so that always fits in a line.
#define MAX_GAMES_PER_PLAYER 5

// WAITING (just created, joinable) -> PLAYING (two players in) -> FINISHED
// (won, lost or drawn). GAME_EMPTY marks a free slot of the registry and is
// never sent over the wire. It MUST stay the first value (0): the registry is
// a zero-initialized static array, so an unused slot starts as GAME_EMPTY.
typedef enum
{
    GAME_EMPTY,
    GAME_WAITING,
    GAME_PLAYING,
    GAME_FINISHED
} RoomState;

// One game. Players are identified by socket only, never by username: a
// username is looked up in the client registry when a message needs it.
// Usernames never change once chosen, so a lookup is always right, while a
// copy stored here would have to be kept in step whenever the owner changes.
typedef struct
{
    int id;
    char name[ROOM_NAME_LEN]; // chosen by the owner at creation, shown in the lobby
    int owner_sock;
    RoomState state;
    int pending_joiner_sock; // -1 if no join request is currently pending
    int player2_sock;        // -1 while there is no second player (WAITING)
    Board board;
    int turn;   // 1 or 2: who moves next. 0 when the game is not being played
    int winner; // valid only once state == GAME_FINISHED: 1, 2, or 0 for a draw
    // Rematch votes (docs/protocol.md §7): meaningful only while
    // state == GAME_FINISHED. Both are cleared whenever a game starts, so a
    // vote never outlives the game it was cast for.
    int owner_wants_rematch;
    int player2_wants_rematch;
} Game;

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
    int other_sock;           // the player who was in the game with the one that left, or -1: their active game stops being this one
} LeaveEvent;

// Creates a new game called 'name', owned by 'owner_sock', state WAITING.
// 'name' must already have passed is_valid_name. Names are not unique: two
// games can share one. Errors: TOO_MANY_GAMES (the owner already is in
// MAX_GAMES_PER_PLAYER games), SERVER_FULL.
ErrorCode game_create(int owner_sock, const char *name, Game *out_game);

// Applies a disconnect to every game where 'sock' is the owner, the pending
// joiner, or player2: a pending request is cancelled, and an owner or
// player2 leaves the game (see LeaveEventType). Fills 'events'
// (caller-allocated, at least MAX_GAMES entries) with what happened to each
// affected game and what to notify about it. Returns how many entries were
// filled.
int game_registry_handle_disconnect(int sock, LeaveEvent *events);

// Makes 'sock' leave game 'game_id' on its own request (LEAVE_GAME): same
// rules as a disconnect, whatever the game's state. On success 'event' says
// what the caller has to notify. Errors: NOT_FOUND, NOT_PLAYER (a pending
// joiner has not joined anything yet).
ErrorCode game_registry_leave(int game_id, int sock, LeaveEvent *event);

// Copies into 'out' up to MAX_GAMES_IN_LIST WAITING games, except the ones
// owned by 'except_sock', by increasing id. Returns how many were copied.
int game_registry_list_waiting(int except_sock, Game *out);

// Copies into 'out' up to 'max' games that 'sock' is a player of, in any
// state, by increasing id. Returns how many were copied. A client never has
// more than MAX_GAMES_PER_PLAYER of them.
int game_registry_list_my_games(int sock, Game *out, int max);

// The other player of 'g' for 'sock', or -1 while the room waits for one.
int game_opponent(const Game *g, int sock);

// A join request: the game remembers 'joiner_sock' until its owner answers.
// Errors: NOT_FOUND, SELF_JOIN, NOT_WAITING, ALREADY_PENDING (the room takes
// one request at a time), TOO_MANY_GAMES (the joiner is full).
ErrorCode game_registry_set_pending(int game_id, int joiner_sock, Game *out_game);

// The owner's answer to the pending request: on accept the game starts
// (PLAYING, player 1 to move), on reject it keeps waiting. Either way the
// request is over, and 'out_game' has the joiner in pending_joiner_sock, so
// the caller knows who to send JOIN_RESULT. Errors: NOT_FOUND, NOT_OWNER,
// NO_PENDING, JOINER_FULL (the joiner reached MAX_GAMES_PER_PLAYER since
// asking: the request is cancelled, and 'out_game' is filled as on success).
ErrorCode game_registry_resolve_join(int game_id, int owner_sock, int accepted, Game *out_game);

// One MOVE (docs/protocol.md §5): drops the disc of 'player_sock' into
// 'column', then the game is won, drawn or goes on with the other player.
// 'is_active' says whether the game is the sender's active game, which only
// the caller knows (it lives in the client registry). Errors, in this order:
// NOT_FOUND, NOT_PLAYER, NOT_PLAYING, NOT_ACTIVE, NOT_YOUR_TURN,
// INVALID_COLUMN, COLUMN_FULL.
ErrorCode game_registry_apply_move(int game_id, int player_sock, int column, int is_active, Game *out_game);

// The state of game 'game_id', or GAME_EMPTY if there is no such game.
RoomState game_registry_state(int game_id);

// Checks that 'sock' may make game 'game_id' its active game
// (docs/protocol.md §5.3). A FINISHED game is fine: the players are still on
// it, deciding about the rematch. Errors: NOT_FOUND, NOT_PLAYER, NOT_PLAYING
// (the game waits for an opponent).
ErrorCode game_registry_check_activate(int game_id, int sock);

// 'sock' votes for a rematch of the FINISHED game 'game_id' (docs/protocol.md
// §7). When both players have voted the game restarts in place: 'out_game' is
// PLAYING then, and still FINISHED while the opponent's vote is missing.
// Errors: NOT_FOUND, NOT_PLAYER, NOT_FINISHED, ALREADY_PENDING (already voted).
ErrorCode game_registry_rematch(int game_id, int sock, Game *out_game);

#endif
