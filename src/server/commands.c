#include <stdio.h>
#include <string.h>
#include "commands.h"
#include "game_registry.h"
#include "net.h"
#include "notify.h"

static void handle_set_username(int sock, Client *me, char *argv[]);
static void handle_create_game(int sock, Client *me, char *argv[]);
static void handle_list_games(int sock, Client *me, char *argv[]);
static void handle_list_my_games(int sock, Client *me, char *argv[]);
static void handle_join_game(int sock, Client *me, char *argv[]);
static void handle_join_response(int sock, Client *me, char *argv[]);
static void handle_move(int sock, Client *me, char *argv[]);
static void handle_set_active_game(int sock, Client *me, char *argv[]);
static void handle_leave_game(int sock, Client *me, char *argv[]);
static void handle_rematch(int sock, Client *me, char *argv[]);
static void send_error(int sock, const Client *me, const char *command, ErrorCode err);
static const char *room_state_name(RoomState s);

// Every command: its name, how many tokens it has (the name included), and
// the function that handles it once the number of tokens is right.
static const struct
{
    const char *name;
    int argc;
    void (*handle)(int sock, Client *me, char *argv[]);
} COMMANDS[] = {
    { "SET_USERNAME",    2, handle_set_username },
    { "CREATE_GAME",     2, handle_create_game },
    { "LIST_GAMES",      1, handle_list_games },
    { "LIST_MY_GAMES",   1, handle_list_my_games },
    { "JOIN_GAME",       2, handle_join_game },
    { "JOIN_RESPONSE",   3, handle_join_response },
    { "MOVE",            3, handle_move },
    { "SET_ACTIVE_GAME", 2, handle_set_active_game },
    { "LEAVE_GAME",      2, handle_leave_game },
    { "REMATCH",         2, handle_rematch },
};

void dispatch_command(int sock, Client *me, char *line)
{
    char *argv[MAX_ARGS];
    int argc = split_args(line, argv, MAX_ARGS);
    if (argc == 0)
    {
        return; // a blank line: the protocol doesn't forbid it, just ignore it
    }

    const char *name = argv[0];
    printf("[SERVER] [%s] Received command: %s\n", log_name(me), name);

    // Until a username is chosen, SET_USERNAME is the only command accepted
    // (docs/protocol.md §2), so every name a game or a notification shows is
    // already final.
    if (me->username[0] == '\0' && strcmp(name, "SET_USERNAME") != 0)
    {
        send_error(sock, me, name, ERR_NO_USERNAME);
        return;
    }

    for (size_t i = 0; i < sizeof(COMMANDS) / sizeof(COMMANDS[0]); i++)
    {
        if (strcmp(name, COMMANDS[i].name) == 0)
        {
            // A name with a space in it arrives as extra tokens, so it gets
            // BAD_ARGS here rather than INVALID_NAME.
            if (argc != COMMANDS[i].argc)
            {
                send_error(sock, me, name, ERR_BAD_ARGS);
            }
            else
            {
                COMMANDS[i].handle(sock, me, argv);
            }
            return;
        }
    }
    send_error(sock, me, "-", ERR_UNKNOWN_COMMAND);
}

void handle_disconnect(int sock)
{
    LeaveEvent events[MAX_GAMES];
    int n = game_registry_handle_disconnect(sock, events);
    notify_leave_events(sock, events, n);
}

const char *log_name(const Client *me)
{
    return me->username[0] != '\0' ? me->username : "(no name)";
}

// The username is chosen once, right after connecting. 'me' is this handler's
// own copy of the client: on success it is updated like the registry's.
static void handle_set_username(int sock, Client *me, char *argv[])
{
    ErrorCode err = is_valid_name(argv[1], USERNAME_LEN - 1)
        ? client_list_set_username(me->id, argv[1])
        : ERR_INVALID_NAME;
    if (err != ERR_NONE)
    {
        send_error(sock, me, "SET_USERNAME", err);
        return;
    }

    strncpy(me->username, argv[1], USERNAME_LEN);
    printf("[SERVER] Client id=%d chose username %s\n", me->id, me->username);
    client_send_line(sock, "USERNAME_SET %s", me->username);
}

static void handle_create_game(int sock, Client *me, char *argv[])
{
    Game g;
    ErrorCode err = is_valid_name(argv[1], ROOM_NAME_LEN - 1)
        ? game_create(sock, argv[1], &g)
        : ERR_INVALID_NAME;
    if (err != ERR_NONE)
    {
        send_error(sock, me, "CREATE_GAME", err);
        return;
    }

    client_send_line(sock, "GAME_CREATED %d %s", g.id, g.name);
    client_broadcast_except(sock, -1, "NEW_GAME %d %s %s", g.id, g.name, me->username);
}

static void handle_list_games(int sock, Client *me, char *argv[])
{
    (void)me;
    (void)argv;
    Game games[MAX_GAMES_IN_LIST];
    int count = game_registry_list_waiting(sock, games);

    // The entries are written first and counted as they go, so <count> always
    // matches the entries actually in the line: with long names 32 entries
    // can exceed MAX_LINE, and an entry that doesn't fit is left out (with
    // every one after it). The buffer leaves room for "GAME_LIST <count>"
    // (at most 2 digits, see MAX_GAMES_IN_LIST) and the '\n'.
    char entries[MAX_LINE - sizeof("GAME_LIST 00")];
    size_t len = 0;
    int listed = 0;

    for (int i = 0; i < count; i++)
    {
        int n = snprintf(entries + len, sizeof(entries) - len, " %d %s %s",
                         games[i].id, games[i].name, client_list_username(games[i].owner_sock));
        if (n < 0 || (size_t)n >= sizeof(entries) - len)
        {
            break; // this entry would be cut off: stop before it
        }
        len += n;
        listed++;
    }
    entries[len] = '\0'; // drops the partial entry snprintf may have written past 'len'

    client_send_line(sock, "GAME_LIST %d%s", listed, entries);
}

// Every room and game of the sender. A room that waits has "-" as opponent.
// A client has at most MAX_GAMES_PER_PLAYER of them, so the line always fits.
static void handle_list_my_games(int sock, Client *me, char *argv[])
{
    (void)me;
    (void)argv;
    Game games[MAX_GAMES_PER_PLAYER];
    int n = game_registry_list_my_games(sock, games, MAX_GAMES_PER_PLAYER);

    char entries[MAX_GAMES_PER_PLAYER * 96];
    size_t len = 0;
    entries[0] = '\0';

    for (int i = 0; i < n; i++)
    {
        int opponent_sock = game_opponent(&games[i], sock);
        len += snprintf(entries + len, sizeof(entries) - len, " %d %s %s %d %s %d %s",
                        games[i].id, games[i].name,
                        (opponent_sock == -1) ? "-" : client_list_username(opponent_sock),
                        (games[i].owner_sock == sock) ? 1 : 2,
                        room_state_name(games[i].state), games[i].turn,
                        (opponent_sock != -1 && is_away(opponent_sock, games[i].id)) ? "AWAY" : "HERE");
    }

    client_send_line(sock, "MY_GAME_LIST %d%s", n, entries);
}

static void handle_join_game(int sock, Client *me, char *argv[])
{
    int game_id;
    Game g;
    ErrorCode err = parse_int(argv[1], &game_id)
        ? game_registry_set_pending(game_id, sock, &g)
        : ERR_BAD_ARGS;
    if (err != ERR_NONE)
    {
        send_error(sock, me, "JOIN_GAME", err);
        return;
    }

    // Only the owner is told (docs/protocol.md §4). The joiner gets its
    // answer, JOIN_RESULT, once the owner decides.
    client_send_line(g.owner_sock, "JOIN_NOTIFY %d %s", game_id, me->username);
}

static void handle_join_response(int sock, Client *me, char *argv[])
{
    int game_id, accepted;
    Game g;
    ErrorCode err = ERR_BAD_ARGS;

    // <accepted> is 0 or 1, nothing else counts as "yes" (docs/protocol.md §4).
    if (parse_int(argv[1], &game_id) && parse_int(argv[2], &accepted) && (accepted == 0 || accepted == 1))
    {
        err = game_registry_resolve_join(game_id, sock, accepted, &g);
    }

    // The request is over, and the joiner is told: a request cancelled
    // because the joiner is full counts as refused.
    if (err == ERR_NONE || err == ERR_JOINER_FULL)
    {
        client_send_line(g.pending_joiner_sock, "JOIN_RESULT %d %d", game_id, (err == ERR_NONE) ? accepted : 0);
    }
    if (err != ERR_NONE)
    {
        send_error(sock, me, "JOIN_RESPONSE", err);
        return;
    }

    if (accepted)
    {
        client_broadcast_except(-1, -1, "GAME_IN_PROGRESS %d", game_id);
        send_game_start(&g);
    }
}

static void handle_move(int sock, Client *me, char *argv[])
{
    int game_id, column;
    Game g;
    ErrorCode err = ERR_BAD_ARGS;

    if (parse_int(argv[1], &game_id) && parse_int(argv[2], &column))
    {
        // The active game lives in the client registry, so it is looked up
        // here and handed to the game registry (docs/protocol.md §5.3).
        int is_active = (client_list_get_active_game(sock) == game_id);
        err = game_registry_apply_move(game_id, sock, column, is_active, &g);
    }
    if (err != ERR_NONE)
    {
        send_error(sock, me, "MOVE", err);
        return;
    }

    send_game_state(&g);
    if (g.state == GAME_FINISHED)
    {
        send_game_over(&g);
        // The other clients only ever saw this game as GAME_IN_PROGRESS
        // (docs/protocol.md §6): now that it is over, it leaves their lists.
        client_broadcast_except(g.owner_sock, g.player2_sock, "GAME_CLOSED %d", g.id);
    }
}

// The sender chooses which of its games it is playing right now (0 = none).
// A success gets no reply: the next MOVE shows it.
static void handle_set_active_game(int sock, Client *me, char *argv[])
{
    int game_id;
    ErrorCode err = ERR_BAD_ARGS;

    if (parse_int(argv[1], &game_id))
    {
        err = (game_id == 0) ? ERR_NONE : game_registry_check_activate(game_id, sock);
    }
    if (err != ERR_NONE)
    {
        send_error(sock, me, "SET_ACTIVE_GAME", err);
        return;
    }

    set_active_game(sock, game_id);
}

// The sender leaves a game it is a player of (docs/protocol.md §7), with the
// same rules as a disconnect. It gets GAME_LEFT first and then, like everyone
// else, what the others are told about the game it left.
static void handle_leave_game(int sock, Client *me, char *argv[])
{
    int game_id;
    LeaveEvent event;
    ErrorCode err = parse_int(argv[1], &game_id)
        ? game_registry_leave(game_id, sock, &event)
        : ERR_BAD_ARGS;
    if (err != ERR_NONE)
    {
        send_error(sock, me, "LEAVE_GAME", err);
        return;
    }

    client_send_line(sock, "GAME_LEFT %d", game_id);
    notify_leave_events(sock, &event, 1);
}

// A vote for a rematch (docs/protocol.md §7). The first vote only tells the
// opponent; the second starts the game again, as an accepted join does. The
// other clients hear nothing: they were told GAME_CLOSED when the game ended.
static void handle_rematch(int sock, Client *me, char *argv[])
{
    int game_id;
    Game g;
    ErrorCode err = parse_int(argv[1], &game_id)
        ? game_registry_rematch(game_id, sock, &g)
        : ERR_BAD_ARGS;
    if (err != ERR_NONE)
    {
        send_error(sock, me, "REMATCH", err);
        return;
    }

    if (g.state == GAME_PLAYING)
    {
        send_game_start(&g);
    }
    else
    {
        client_send_line(game_opponent(&g, sock), "REMATCH_NOTIFY %d", game_id);
    }
}

// "ERROR <command> <code>" (docs/protocol.md §1.5) to the sender, and a line
// in the log. The command is echoed as sent, cut to a length that always fits
// in a line.
static void send_error(int sock, const Client *me, const char *command, ErrorCode err)
{
    printf("[SERVER] [%s] %.31s refused: %s\n", log_name(me), command, error_name(err));
    client_send_line(sock, "ERROR %.31s %s", command, error_name(err));
}

// The state's name as it appears on the wire (docs/protocol.md §3).
static const char *room_state_name(RoomState s)
{
    switch (s)
    {
        case GAME_PLAYING:  return "PLAYING";
        case GAME_FINISHED: return "FINISHED";
        default:            return "WAITING"; // GAME_EMPTY is never listed
    }
}
