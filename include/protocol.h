#ifndef PROTOCOL_H
#define PROTOCOL_H

#define PORT 8080
#define BUFFER_SIZE 256
#define USERNAME_LEN 32

typedef enum 
{
    CMD_CREATE_GAME,     // no extra payload needed
    CMD_GAME_CREATED,    // payload: game_created (game_id) - reply to CMD_CREATE_GAME
    CMD_LIST_GAMES,      // no extra payload needed
    CMD_GAME_LIST,       // payload: game_list - reply to CMD_LIST_GAMES
    CMD_JOIN_GAME,       // payload: join_request (game_id) - client asking to join
    CMD_JOIN_NOTIFY,     // payload: join_notify (game_id, joiner_username) - server push to the owner
    CMD_JOIN_RESPONSE,   // payload: join_response (game_id, accepted) - sent by the game owner
    CMD_JOIN_RESULT,     // payload: join_result (game_id, accepted) - server push to the joiner
    CMD_MOVE,            // payload TODO: defined when game logic is implemented (Phase 4)
    CMD_GAME_STATE,      // payload TODO: defined when game logic is implemented (Phase 4)
    CMD_ERROR,
    CMD_WELCOME          // sent by the server right after accept(); payload: welcome (assigned client_id/username)
} CommandType;

// State machine for a single game: WAITING (just created, joinable) ->
// PLAYING (two players in, moves being made) -> FINISHED (won/lost/draw).
typedef enum
{
    GAME_WAITING,
    GAME_PLAYING,
    GAME_FINISHED
} GameState;

typedef struct __attribute__((packed)) 
{
    CommandType type;
    int payload_size;
} Header;

// Sent by a client asking to join an existing game.
typedef struct __attribute__((packed))
{
    int game_id;
} JoinRequest;

// Sent by the game owner accepting/refusing a join request.
typedef struct __attribute__((packed))
{
    int game_id;
    int accepted; // 0 = refused, 1 = accepted
} JoinResponse;

// Server push to the owner: someone wants to join their game.
typedef struct __attribute__((packed))
{
    int game_id;
    char joiner_username[USERNAME_LEN];
} JoinNotify;

// Server push to the joiner: outcome of their join request.
typedef struct __attribute__((packed))
{
    int game_id;
    int accepted; // 0 = refused, 1 = accepted
} JoinResult;

// Sent by the server right after accept(), informing the client of the
// id/username it has been assigned. Used to know who is "in game" and
// who isn't in later phases.
typedef struct __attribute__((packed))
{
    int client_id;
    char username[USERNAME_LEN];
} Welcome;

// Sent by the server in reply to CMD_CREATE_GAME, telling the creator
// the id assigned to their new game.
typedef struct __attribute__((packed))
{
    int game_id;
} GameCreated;

#define MAX_GAMES_IN_LIST 32

// One entry of a CMD_GAME_LIST reply.
typedef struct __attribute__((packed))
{
    int game_id;
    char owner_username[USERNAME_LEN];
    GameState state;
} GameInfo;

// Sent by the server in reply to CMD_LIST_GAMES. Only WAITING games are
// included: PLAYING/FINISHED games aren't joinable, so there's nothing
// a listing client could do with them at this stage.
typedef struct __attribute__((packed))
{
    int count;
    GameInfo games[MAX_GAMES_IN_LIST];
} GameList;

// Payload varies depending on header.type: only the member matching
// the current command should be read/written. Using a union (instead
// of one generic "int data" field) lets each command carry exactly
// the data it needs, instead of overloading a single integer with
// different meanings depending on context.
typedef union __attribute__((packed))
{
    JoinRequest join_request;
    JoinResponse join_response;
    JoinNotify join_notify;
    JoinResult join_result;
    Welcome welcome;
    GameCreated game_created;
    GameList game_list;
    // MOVE and GAME_STATE payloads will be added here once the actual
    // Connect4 game logic is implemented (grid, turns, etc).
} Payload;

typedef struct __attribute__((packed)) 
{
    Header header;
    Payload payload;
} Packet;

#endif