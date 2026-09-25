#ifndef PROTOCOL_H
#define PROTOCOL_H

// The constants of the text protocol between the server and its clients.

#define PORT 8080

// Buffer size for a username: 20 characters + '\0', the limit of every name
// chosen by a client (see is_valid_name in net.h).
#define USERNAME_LEN 21

// Buffer size for a game's name: 20 characters + '\0', as for a username.
#define ROOM_NAME_LEN 21

// Maximum length of one protocol line, '\n' included.
#define MAX_LINE 1024

// At most this many rooms in a GAME_LIST.
#define MAX_GAMES_IN_LIST 32

// The errors the server can answer with. ERR_NONE (0) means no error;
// error_name() (net.h) gives the word sent in "ERROR <command> <code>".
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
