#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include "net.h"

void line_reader_init(LineReader *reader, int sock)
{
    reader->sock = sock;
    reader->len = 0;
}

int recv_line(LineReader *reader, char *out)
{
    while (1)
    {
        // A line may already be sitting in the buffer, left over from a
        // previous recv() that returned more than one line.
        char *newline = memchr(reader->buf, '\n', reader->len);
        if (newline != NULL)
        {
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
            return LINE_OK;
        }

        // Buffer full and still no '\n': the line exceeds MAX_LINE.
        if (reader->len == MAX_LINE)
        {
            return LINE_TOO_LONG;
        }

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
    }
}

int send_line(int sock, const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    int result = vsend_line(sock, fmt, args);
    va_end(args);
    return result;
}

int vsend_line(int sock, const char *fmt, va_list args)
{
    char line[MAX_LINE];

    int len = vsnprintf(line, MAX_LINE, fmt, args);

    // len + 1 for the '\n' that still has to be appended.
    if (len < 0 || len + 1 > MAX_LINE)
    {
        return -1;
    }
    line[len++] = '\n'; // overwrites the '\0': it isn't sent on the wire

    size_t sent = 0;
    while (sent < (size_t)len)
    {
        // MSG_NOSIGNAL: writing to a socket the peer already closed would
        // otherwise raise SIGPIPE, whose default action kills the whole
        // process (i.e. the server, for every client).
        ssize_t n = send(sock, line + sent, len - sent, MSG_NOSIGNAL);
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
