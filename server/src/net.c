#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include "net.h"

// Forward declarations of every function defined below, so the
// definitions can be ordered "caller before callee" (each public
// function right above the private helper(s) it uses) instead of the
// usual bottom-up C order.
static int try_extract_line(LineReader *reader, char *out);
static int fill_buffer(LineReader *reader);
static int format_line(const char *fmt, va_list args, char *out);
static int send_all(int sock, const char *buf, size_t len);

void line_reader_init(LineReader *reader, int sock)
{
    reader->sock = sock;
    reader->len = 0;
}

int recv_line(LineReader *reader, char *out)
{
    while (1)
    {
        if (try_extract_line(reader, out))
        {
            return LINE_OK;
        }

        // Buffer full and still no '\n': the line exceeds MAX_LINE.
        if (reader->len == MAX_LINE)
        {
            return LINE_TOO_LONG;
        }

        int status = fill_buffer(reader);
        if (status != LINE_OK)
        {
            return status; // LINE_CLOSED or LINE_ERROR
        }
        // Otherwise more bytes just landed in the buffer: loop back and
        // look for a '\n' again.
    }
}

// Looks for a '\n' already sitting in reader->buf (left over from a
// previous recv() that returned more than one line, or more than a line).
// If found: copies the line into 'out' (without '\n', and without a '\r'
// right before it), shifts whatever comes after the '\n' to the front of
// the buffer, and returns 1. Returns 0 if no full line is buffered yet -
// recv_line() then knows it has to go read more bytes from the socket.
static int try_extract_line(LineReader *reader, char *out)
{
    char *newline = memchr(reader->buf, '\n', reader->len);
    if (newline == NULL)
    {
        return 0;
    }

    size_t line_len = newline - reader->buf;
    size_t consumed = line_len + 1; // '\n' included

    if (line_len > 0 && reader->buf[line_len - 1] == '\r')
    {
        line_len--;
    }

    memcpy(out, reader->buf, line_len);
    out[line_len] = '\0';

    // Shift what's left (the start of the next line) to the front.
    reader->len -= consumed;
    memmove(reader->buf, newline + 1, reader->len);
    return 1;
}

// Reads whatever bytes are available into reader->buf, appending after the
// bytes already there, and updates reader->len. Retries on its own when a
// signal interrupts recv() before any data arrived (EINTR); any other
// outcome (peer closed, real error, or bytes received) is reported back to
// recv_line() as one of LINE_OK/LINE_CLOSED/LINE_ERROR.
static int fill_buffer(LineReader *reader)
{
    while (1)
    {
        ssize_t n = recv(reader->sock, reader->buf + reader->len, MAX_LINE - reader->len, 0);
        if (n == 0)
        {
            return LINE_CLOSED;
        }
        if (n < 0)
        {
            if (errno == EINTR)
            {
                continue; // interrupted by a signal before any data: retry
            }
            return LINE_ERROR;
        }
        reader->len += n;
        return LINE_OK;
    }
}

int vsend_line(int sock, const char *fmt, va_list args)
{
    char line[MAX_LINE];

    int len = format_line(fmt, args, line);
    if (len < 0)
    {
        return -1;
    }

    return send_all(sock, line, (size_t)len);
}

// Formats 'fmt'/'args' printf-style into 'out' (which must be at least
// MAX_LINE bytes) and appends '\n'. Returns the number of bytes written
// (line + '\n'), or -1 if the formatted line doesn't fit in MAX_LINE.
static int format_line(const char *fmt, va_list args, char *out)
{
    int len = vsnprintf(out, MAX_LINE, fmt, args);

    // len + 1 for the '\n' that still has to be appended.
    if (len < 0 || len + 1 > MAX_LINE)
    {
        return -1;
    }
    out[len++] = '\n'; // overwrites the '\0': it isn't sent on the wire
    return len;
}

// Sends exactly 'len' bytes from 'buf', looping over partial send()s (a
// single send() may accept only part of the buffer) and retrying on
// EINTR. Returns 0 once everything is sent, -1 on a real error.
static int send_all(int sock, const char *buf, size_t len)
{
    size_t sent = 0;
    while (sent < len)
    {
        // MSG_NOSIGNAL: writing to a socket the peer already closed would
        // otherwise raise SIGPIPE, whose default action kills the whole
        // process (i.e. the server, for every client).
        ssize_t n = send(sock, buf + sent, len - sent, MSG_NOSIGNAL);
        if (n < 0)
        {
            if (errno == EINTR)
            {
                continue;
            }
            return -1;
        }
        sent += n;
    }
    return 0;
}

int split_args(char *line, char *argv[], int max_args)
{
    int argc = 0;
    char *saveptr;
    char *tok = strtok_r(line, " ", &saveptr);

    while (tok != NULL && argc < max_args)
    {
        argv[argc++] = tok;
        tok = strtok_r(NULL, " ", &saveptr);
    }
    return argc;
}

int parse_int(const char *s, int *out)
{
    if (s == NULL)
    {
        return 0;
    }

    // An optional '-', then digits with no leading zero: strtol alone would
    // also take "+3", "03", "-0" and leading spaces.
    const char *digits = (*s == '-') ? s + 1 : s;
    if (*digits < '0' || *digits > '9')
    {
        return 0; // "", "-", "+3", " 3", "--3"
    }
    if (*digits == '0' && (digits != s || digits[1] != '\0'))
    {
        return 0; // "03", "-0", "-03": only a lone "0" may start with a zero
    }

    char *end;
    errno = 0;
    long v = strtol(s, &end, 10);
    if (*end != '\0')
    {
        return 0; // trailing garbage, e.g. "12abc"
    }
    if (errno == ERANGE || v < INT_MIN || v > INT_MAX)
    {
        return 0; // doesn't fit in an int: casting it would wrap around (2^32 -> 0)
    }

    *out = (int)v;
    return 1;
}

int is_valid_name(const char *s, size_t max_len)
{
    if (s == NULL || *s == '\0')
    {
        return 0;
    }

    size_t len = 0;
    for (; s[len] != '\0'; len++)
    {
        unsigned char c = (unsigned char)s[len];
        if (len >= max_len || c < 0x21 || c > 0x7E)
        {
            return 0; // too long, or a space/control/non-ASCII byte
        }
    }
    return 1;
}

const char *error_name(ErrorCode err)
{
    static const char *const names[] = {
        [ERR_NONE]            = "NONE",
        [ERR_UNKNOWN_COMMAND] = "UNKNOWN_COMMAND",
        [ERR_BAD_ARGS]        = "BAD_ARGS",
        [ERR_INVALID_NAME]    = "INVALID_NAME",
        [ERR_SERVER_FULL]     = "SERVER_FULL",
        [ERR_TOO_MANY_GAMES]  = "TOO_MANY_GAMES",
        [ERR_NO_USERNAME]     = "NO_USERNAME",
        [ERR_ALREADY_NAMED]   = "ALREADY_NAMED",
        [ERR_USERNAME_TAKEN]  = "USERNAME_TAKEN",
        [ERR_NOT_FOUND]       = "NOT_FOUND",
        [ERR_NOT_WAITING]     = "NOT_WAITING",
        [ERR_SELF_JOIN]       = "SELF_JOIN",
        [ERR_ALREADY_PENDING] = "ALREADY_PENDING",
        [ERR_NOT_OWNER]       = "NOT_OWNER",
        [ERR_NO_PENDING]      = "NO_PENDING",
        [ERR_NOT_PLAYER]      = "NOT_PLAYER",
        [ERR_NOT_PLAYING]     = "NOT_PLAYING",
        [ERR_NOT_ACTIVE]      = "NOT_ACTIVE",
        [ERR_JOINER_FULL]     = "JOINER_FULL",
        [ERR_NOT_YOUR_TURN]   = "NOT_YOUR_TURN",
        [ERR_INVALID_COLUMN]  = "INVALID_COLUMN",
        [ERR_COLUMN_FULL]     = "COLUMN_FULL",
        [ERR_NOT_FINISHED]    = "NOT_FINISHED"
    };
    return names[err];
}
