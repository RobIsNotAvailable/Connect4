#ifndef NET_H
#define NET_H

#include <stdarg.h>
#include <stddef.h>
#include "protocol.h"

// Return values of recv_line().
#define LINE_OK        1  // a full line was copied into 'out'
#define LINE_CLOSED    0  // the peer closed the connection
#define LINE_ERROR    -1  // recv() failed (errno is set)
#define LINE_TOO_LONG -2  // MAX_LINE bytes arrived without a '\n'

// Per-socket receive buffer. TCP is a byte stream, not a message stream:
// one recv() can return half a line, or several lines at once. Bytes that
// arrived after the current line are kept here and handed out by the next
// recv_line() call instead of being lost.
//
// Only one thread may use a given LineReader (and recv() on its socket).
typedef struct
{
    int sock;
    char buf[MAX_LINE];
    size_t len; // how many bytes of buf are currently valid
} LineReader;

void line_reader_init(LineReader *reader, int sock);

// Blocks until a whole line is available and copies it into 'out' as a
// C string, without the trailing '\n' (and without a '\r' before it).
// 'out' must be at least MAX_LINE bytes.
int recv_line(LineReader *reader, char *out);

// Formats a line printf-style, appends '\n' and sends all of it, looping
// over partial send()s. Returns 0 on success, -1 if the formatted line
// doesn't fit in MAX_LINE or send() fails.
//
// Not synchronized: if several threads may write to the same socket, the
// caller must hold a lock around the call so lines don't interleave.
int send_line(int sock, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

// Same as send_line, taking a va_list: lets other variadic functions
// (e.g. the server's locked client_send_line) forward their arguments.
int vsend_line(int sock, const char *fmt, va_list args) __attribute__((format(printf, 2, 0)));

// AGGIUNTA (migrazione al protocollo testuale): client.c e server.c devono
// entrambi spezzare una riga ricevuta in token e validare gli argomenti
// numerici allo stesso modo, quindi questi due helper vivono qui invece di
// essere duplicati nei due file.

// Maximum number of whitespace-separated tokens split_args() extracts from
// one line, command included. Covers every fixed-arity message in
// docs/protocol.md (the longest is "ERROR <comando> <codice>", 3 tokens);
// GAME_LIST's variable-length tail is parsed separately by whoever needs it.
#define MAX_ARGS 4

// Splits 'line' in place (each separating space becomes '\0') into up to
// 'max_args' tokens, argv[0] being the command name. Returns how many
// tokens were found (0 for a blank line).
int split_args(char *line, char *argv[], int max_args);

// Parses 's' as a base-10 integer with nothing left over: unlike plain
// strtol/atoi, rejects "", " " and "12abc" instead of silently accepting a
// prefix. Returns 1 and fills 'out' on success, 0 otherwise. Used to turn
// a BAD_ARGS case (non-numeric argument) into an actual protocol error
// instead of silently treating garbage as 0.
int parse_int(const char *s, int *out);

// Checks 's' is a legal name token (docs/protocol.md §1.2): between 1 and
// 'max_len' characters, each a printable ASCII character other than the
// space (0x21-0x7E). Returns 1 if valid, 0 otherwise. Meant for text the
// client picks (game names, later usernames), so the caller can reject it
// before copying it into a fixed-size buffer.
int is_valid_name(const char *s, size_t max_len);

#endif
