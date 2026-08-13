#ifndef PROTOCOL_H
#define PROTOCOL_H

#define PORT 8080
#define BUFFER_SIZE 256

typedef enum 
{
    CMD_CREATE_GAME,     // no extra payload needed
    CMD_JOIN_GAME,       // payload: join_request (game_id)
    CMD_JOIN_RESPONSE,   // payload: join_response (game_id, accepted) - sent by the game owner
    CMD_MOVE,            // payload TODO: defined when game logic is implemented (Phase 4)
    CMD_GAME_STATE,      // payload TODO: defined when game logic is implemented (Phase 4)
    CMD_ERROR
} CommandType;

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

// Payload varies depending on header.type: only the member matching
// the current command should be read/written. Using a union (instead
// of one generic "int data" field) lets each command carry exactly
// the data it needs, instead of overloading a single integer with
// different meanings depending on context.
typedef union __attribute__((packed))
{
    JoinRequest join_request;
    JoinResponse join_response;
    // MOVE and GAME_STATE payloads will be added here once the actual
    // Connect4 game logic is implemented (grid, turns, etc).
} Payload;

typedef struct __attribute__((packed)) 
{
    Header header;
    Payload payload;
} Packet;

#endif