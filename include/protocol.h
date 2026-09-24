#ifndef PROTOCOL_H
#define PROTOCOL_H

// The constants of the text protocol (docs/protocol.md).

#define PORT 8080

// Buffer size for a username: 20 characters + '\0', the same limit
// docs/protocol.md §1.2 states for every name chosen by a client.
#define USERNAME_LEN 21

// Buffer size for a game's name: 20 characters + '\0'. The 20-character
// limit is the one docs/protocol.md §1.2 states for names.
#define ROOM_NAME_LEN 21

// Maximum length of one protocol line, '\n' included (see docs/protocol.md).
#define MAX_LINE 1024

// At most this many rooms in a GAME_LIST (docs/protocol.md §3).
#define MAX_GAMES_IN_LIST 32

// The error codes of docs/protocol.md §1.5, in the order of its table.
// ERR_NONE (0) means no error; error_name() (net.h) gives the word sent in
// "ERROR <command> <code>".
typedef enum
{
    ERR_NONE,
    ERR_UNKNOWN_COMMAND,
    ERR_BAD_ARGS,
    ERR_INVALID_NAME,
    ERR_SERVER_FULL,
    ERR_TOO_MANY_GAMES,
    ERR_NO_USERNAME,
    ERR_ALREADY_NAMED,
    ERR_USERNAME_TAKEN,
    ERR_NOT_FOUND,
    ERR_NOT_WAITING,
    ERR_SELF_JOIN,
    ERR_ALREADY_PENDING,
    ERR_NOT_OWNER,
    ERR_NO_PENDING,
    ERR_NOT_PLAYER,
    ERR_NOT_PLAYING,
    ERR_NOT_ACTIVE,
    ERR_JOINER_FULL,
    ERR_NOT_YOUR_TURN,
    ERR_INVALID_COLUMN,
    ERR_COLUMN_FULL,
    ERR_NOT_FINISHED
} ErrorCode;

#endif
