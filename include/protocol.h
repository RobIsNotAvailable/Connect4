#ifndef PROTOCOL_H
#define PROTOCOL_H

#define PORT 8080
#define BUFFER_SIZE 256

typedef enum 
{
    CMD_CREATE_GAME,
    CMD_JOIN_GAME,
    CMD_MOVE,
    CMD_GAME_STATE,
    CMD_ERROR
} CommandType;

typedef struct __attribute__((packed)) 
{
    CommandType type;
    int payload_size;
} Header;

typedef struct __attribute__((packed)) 
{
    Header header;
    int data;
} Packet;

#endif