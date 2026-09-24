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

// Formats a line printf-style from a va_list (so that the variadic
// client_send_line can forward its arguments), appends '\n' and sends all of
// it, looping over partial send()s. Returns 0 on success, -1 if the formatted
// line doesn't fit in MAX_LINE or send() fails.
//
// Not synchronized: if several threads may write to the same socket, the
// caller must hold a lock around the call so lines don't interleave.
int vsend_line(int sock, const char *fmt, va_list args) __attribute__((format(printf, 2, 0)));

// Maximum number of space-separated tokens split_args() extracts from one
// line, command included: the longest command has 3 (e.g. MOVE <game_id>
// <column>), and one more lets a handler see an extra argument and answer
// BAD_ARGS instead of silently ignoring it.
#define MAX_ARGS 4

// Splits 'line' in place (each separating space becomes '\0') into up to
// 'max_args' tokens, argv[0] being the command name. Returns how many
// tokens were found (0 for a blank line).
int split_args(char *line, char *argv[], int max_args);

// Parses 's' as a base-10 integer written the way docs/protocol.md §1.3 says
// (optional '-', no '+', no leading zeros) with nothing left over: unlike
// plain strtol/atoi, rejects "", " ", "12abc", "+3" and "03" instead of
// silently accepting them. Returns 1 and fills 'out' on success, 0 otherwise. Used to turn
// a BAD_ARGS case (non-numeric argument) into an actual protocol error
// instead of silently treating garbage as 0.
int parse_int(const char *s, int *out);

// Checks 's' is a legal name token (docs/protocol.md §1.2): between 1 and
// 'max_len' characters, each a printable ASCII character other than the
// space (0x21-0x7E). Returns 1 if valid, 0 otherwise. Meant for text the
// client picks (room names and usernames), so the caller can reject it
// before copying it into a fixed-size buffer.
int is_valid_name(const char *s, size_t max_len);

#endif
