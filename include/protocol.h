#ifndef PROTOCOL_H
#define PROTOCOL_H

// MIGRAZIONE: questo header conteneva anche CommandType/Header/Packet e gli
// struct dei singoli payload (JoinRequest, Welcome, GameList, ...), usati
// per spedire un Packet binario intero con send()/recv(). Ora che client e
// server parlano il protocollo testuale di docs/protocol.md (una riga
// ASCII per messaggio, vedi net.h), quei tipi non servono più e sono stati
// rimossi: il comando è il primo token della riga, non un campo di uno
// struct. Qui restano solo le costanti e il GameInfo usato internamente
// dal server per costruire la risposta a LIST_GAMES.

#define PORT 8080

// Buffer size for a username: 20 characters + '\0', the same limit
// docs/protocol.md §1.2 states for every name chosen by a client.
#define USERNAME_LEN 21

// Buffer size for a game's name: 20 characters + '\0'. The 20-character
// limit is the one docs/protocol.md §1.2 states for names.
#define ROOM_NAME_LEN 21

// Maximum length of one protocol line, '\n' included (see docs/protocol.md).
#define MAX_LINE 1024

// State machine for a single game registry slot: EMPTY (no game there,
// either never used or freed) -> WAITING (just created, joinable) ->
// PLAYING (two players in, moves being made) -> FINISHED (won/lost/draw).
// GAME_EMPTY is never sent over the wire (docs/protocol.md never mentions
// it - GAME_LIST only ever lists WAITING games); it exists purely so the
// server's game registry can tell "no game here" apart from an actual
// game's state using this one field, instead of a separate id-based
// sentinel. It MUST stay the first value (0): the registry's slot array
// is a zero-initialized static array, so an unused slot's state starts as
// GAME_EMPTY automatically, with no explicit initialization needed.
typedef enum
{
    GAME_EMPTY,
    GAME_WAITING,
    GAME_PLAYING,
    GAME_FINISHED
} RoomState;

#define MAX_GAMES_IN_LIST 32

// One entry used by the server to build a GAME_LIST reply (see
// docs/protocol.md §3). Purely in-memory bookkeeping now, not a wire
// struct, so no __attribute__((packed)) needed anymore. The owner is
// identified by socket: the caller looks its username up in the client
// registry when it builds the line.
typedef struct
{
    int game_id;
    char name[ROOM_NAME_LEN];
    int owner_sock;
    RoomState state;
} GameInfo;

#endif
