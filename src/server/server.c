#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <pthread.h>
#include "board.h"
#include "client_registry.h"
#include "game_registry.h"
#include "net.h"
#include "protocol.h"

// How long a send() to a client may stay blocked before the server gives up on it
#define SEND_TIMEOUT_SEC 2


static void handle_stop_signal(int sig);
static void *client_handler(void *sock_id);
static void dispatch_command(int client_sock, Client *me, char *line);
static void handle_set_username(int client_sock, Client *me, int argc, char *argv[]);
static const char *set_username_error_code(SetUsernameResult r);
static const char *log_name(const Client *me);
static void handle_create_game(int client_sock, const Client *me, int argc, char *argv[]);
static const char *create_error_code(CreateResult r);
static void handle_leave_game(int client_sock, const Client *me, int argc, char *argv[]);
static const char *leave_error_code(LeaveResult r);
static void handle_rematch(int client_sock, const Client *me, int argc, char *argv[]);
static const char *rematch_error_code(RematchResult r);
static void handle_list_games(int client_sock);
static void handle_list_my_games(int client_sock);
static const char *room_state_name(RoomState s);
static void handle_join_game(int client_sock, const Client *me, int argc, char *argv[]);
static const char *join_set_error_code(JoinSetResult r);
static void handle_join_response(int client_sock, const Client *me, int argc, char *argv[]);
static const char *resolve_error_code(ResolveResult r);
static void send_game_start(const Game *g);
static void send_game_state(const Game *g);
static void handle_move(int client_sock, const Client *me, int argc, char *argv[]);
static void handle_set_active_game(int client_sock, const Client *me, int argc, char *argv[]);
static const char *activate_error_code(ActivateResult r);
static void activate_if_idle(int sock, int game_id);
static int is_away(int sock, int game_id);
static void announce_presence(int sock, int old_active, int new_active, int skip_game);
static void set_active_game(int sock, int game_id);
static void clear_active_game(int sock, int game_id);
static void send_game_over(const Game *g);
static const char *move_error_code(MoveResult r);
static void handle_disconnect(int client_sock);
static void notify_leave_events(int leaver_sock, const LeaveEvent *events, int n);

int main()
{
    int server_sock, client_sock;
    struct sockaddr_in server_addr, client_addr;
    socklen_t addr_len = sizeof(client_addr);

    if ((server_sock = socket(AF_INET, SOCK_STREAM, 0)) < 0)
    {
        perror("Error in creating the server socket\n");
        return -1;
    }

    // When the server stops while clients are still connected
    // the kernel keeps each of them in TIME_WAIT for about a minute.
    // Those leftovers still hold the server's port, so without the option below
    // a restart right away fails in bind() with "Address already in use" until they expire.
    // SO_REUSEADDR lets bind() succeed despite connections in TIME_WAIT.
    int reuse = 1;
    if (setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) < 0)
    {
        perror("Setsockopt error");
        close(server_sock);
        return -1;
    }

    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(PORT);

    if (bind(server_sock, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0)
    {
        perror("Bind error\n");
        close(server_sock);
        return -1;
    }

    if (listen(server_sock, 10) < 0)
    {
        perror("Listen error");
        close(server_sock);
        return -1;
    }

    //controllare
    // Problem: the default action of SIGTERM (what `docker stop` sends) and
    // SIGINT (Ctrl-C) is to kill the process, but the first process of a
    // container (PID 1) is special: the kernel ignores the signals it has no
    // handler for. The server would then not stop on `docker stop`, which
    // waits 10 seconds and finally kills it with SIGKILL. With a handler
    // installed the signal is delivered like for any other process.
    // sigaction() is used instead of signal() because its behaviour is fully
    // specified by POSIX (signal() varies between systems).
    struct sigaction stop_action;
    memset(&stop_action, 0, sizeof(stop_action));
    stop_action.sa_handler = handle_stop_signal;
    sigemptyset(&stop_action.sa_mask);
    if (sigaction(SIGTERM, &stop_action, NULL) < 0 || sigaction(SIGINT, &stop_action, NULL) < 0)
    {
        perror("Sigaction error");
        close(server_sock);
        return -1;
    }

    printf("=== SERVER STARTED ON PORT %d ===\n", PORT);

    while (1)
    {
        if ((client_sock = accept(server_sock, (struct sockaddr *)&client_addr, &addr_len)) < 0)
        {
            perror("Accept error");
            continue;
        }

        int *new_sock = malloc(sizeof(int));
        *new_sock = client_sock;
        pthread_t thread_id;

        if (pthread_create(&thread_id, NULL, client_handler, (void *)new_sock) < 0)
        {
            perror("Error in creating the thread");
            free(new_sock);
            close(client_sock);
        }
        else
        {
            pthread_detach(thread_id);
        }
    }

    close(server_sock);
    return 0;
}

//controllare
// SIGTERM / SIGINT: the server stops at once. Why not set a flag and let the
// accept() loop in main() notice it? The process has many threads and the
// signal may be delivered to any of them, so main()'s accept() is not sure to
// be interrupted, and a signal arriving just before accept() would leave it
// blocked until the next client. There is also nothing to clean up: all the
// state is in memory, and when the process ends the kernel closes every socket
// (each client sees its connection closed). Only async-signal-safe functions
// may run in a handler: write() and _exit() are, while printf() and exit()
// are not (they could deadlock on a lock that the interrupted code holds).
static void handle_stop_signal(int sig)
{
    (void)sig;
    static const char msg[] = "\n=== SERVER STOPPING ===\n";
    ssize_t written = write(STDOUT_FILENO, msg, sizeof(msg) - 1);
    (void)written;
    _exit(0);
}

static void *client_handler(void *sock_id)
{
    int client_sock = *(int *)sock_id;
    free(sock_id); 

    Client me = client_list_add(client_sock);

    if (me.id == -1)
    {
        printf("[SERVER] Rejecting client on socket %d: registry full (max %d clients)\n", client_sock, MAX_CLIENTS);
        
        close(client_sock);
        pthread_exit(NULL);
    }

    printf("[SERVER] New client connected on socket %d, assigned id=%d\n", client_sock, me.id);

    // A client that stops reading fills its socket buffers, and from then
    // on every send() to it blocks with that client's send mutex held.
    // Then a broadcast would freeze the whole server.
    // With a send timeout the blocked send() fails after SEND_TIMEOUT_SEC,
    // and client_send_line drops the client.
    struct timeval send_timeout = { .tv_sec = SEND_TIMEOUT_SEC, .tv_usec = 0 };
    if (setsockopt(client_sock, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout)) < 0)
    {
        perror("[SERVER] Setsockopt SO_SNDTIMEO error");
    }

    client_send_line(client_sock, "WELCOME %d", me.id);

    LineReader reader;
    line_reader_init(&reader, client_sock);
    char line[MAX_LINE];
    int comm_status;

    while ((comm_status = recv_line(&reader, line)) == LINE_OK)
    {
        dispatch_command(client_sock, &me, line);
    }

    if (comm_status == LINE_CLOSED)
    {
        printf("[SERVER] Client %s (socket %d) disconnected\n", log_name(&me), client_sock);
    }
    else if (comm_status == LINE_TOO_LONG)
    {
        printf("[SERVER] Client %s (socket %d) sent a line longer than %d bytes, closing\n",
               log_name(&me), client_sock, MAX_LINE);
    }
    else
    {
        perror("[SERVER] Error in receiving data from client");
    }

    handle_disconnect(client_sock);
    client_list_remove(me.id);

    close(client_sock);
    pthread_exit(NULL);
}

static void handle_disconnect(int client_sock)
{
    LeaveEvent events[MAX_GAMES];
    int n = game_registry_handle_disconnect(client_sock, events);

    notify_leave_events(client_sock, events, n);
}

// Sends the notifications for what the registry did because 'leaver_sock' left some games
static void notify_leave_events(int leaver_sock, const LeaveEvent *events, int n)
{
    for (int i = 0; i < n; i++)
    {
        int game_id = events[i].game_id;
        int notify_sock = events[i].notify_sock;

        // The game is over for whoever left, and no longer being played for
        // the one who stays (it is WAITING again, or gone): neither has it as
        // active game any more.
        if (events[i].type != LEAVE_JOIN_CANCELLED)
        {
            clear_active_game(leaver_sock, game_id);
            clear_active_game(events[i].other_sock, game_id);
        }

        switch (events[i].type)
        {
            case LEAVE_JOIN_CANCELLED:
                client_send_line(notify_sock, "JOIN_CANCELLED %d", game_id);
                break;
            case LEAVE_ROOM_CLOSED:
                client_broadcast_except(leaver_sock, -1, "GAME_CLOSED %d", game_id);
                break;
            case LEAVE_ROOM_REOPENED:
            {
                client_send_line(notify_sock, "OPPONENT_LEFT %d", game_id);

                char owner_username[USERNAME_LEN];
                if (client_list_find_username(notify_sock, owner_username))
                {
                    client_broadcast_except(notify_sock, -1, "NEW_GAME %d %s %s",
                                            game_id, events[i].name, owner_username);
                }
                break;
            }
        }
    }
}

// Splits one received line into command + arguments and calls the
// matching handler. An unrecognized command, or one that fails its own
// validation, gets "ERROR <command> <code>".
static void dispatch_command(int client_sock, Client *me, char *line)
{
    char *argv[MAX_ARGS];
    int argc = split_args(line, argv, MAX_ARGS);

    if (argc == 0)
    {
        return;
    }

    const char *cmd = argv[0];
    printf("[SERVER] [%s] Received command: %s\n", log_name(me), cmd);

    if (strcmp(cmd, "SET_USERNAME") == 0)
    {
        handle_set_username(client_sock, me, argc, argv);
    }
    else if (me->username[0] == '\0')
    {
        client_send_line(client_sock, "ERROR %.31s NO_USERNAME", cmd);
    }
    else if (strcmp(cmd, "CREATE_GAME") == 0)
    {
        handle_create_game(client_sock, me, argc, argv);
    }
    else if (strcmp(cmd, "LIST_GAMES") == 0)
    {
        handle_list_games(client_sock);
    }
    else if (strcmp(cmd, "LIST_MY_GAMES") == 0)
    {
        handle_list_my_games(client_sock);
    }
    else if (strcmp(cmd, "JOIN_GAME") == 0)
    {
        handle_join_game(client_sock, me, argc, argv);
    }
    else if (strcmp(cmd, "JOIN_RESPONSE") == 0)
    {
        handle_join_response(client_sock, me, argc, argv);
    }
    else if (strcmp(cmd, "MOVE") == 0)
    {
        handle_move(client_sock, me, argc, argv);
    }
    else if (strcmp(cmd, "SET_ACTIVE_GAME") == 0)
    {
        handle_set_active_game(client_sock, me, argc, argv);
    }
    else if (strcmp(cmd, "LEAVE_GAME") == 0)
    {
        handle_leave_game(client_sock, me, argc, argv);
    }
    else if (strcmp(cmd, "REMATCH") == 0)
    {
        handle_rematch(client_sock, me, argc, argv);
    }
    else
    {
        printf("[SERVER] [%s] Unknown command: %s\n", me->username, cmd);
        client_send_line(client_sock, "ERROR - UNKNOWN_COMMAND");
    }
}

// The username is chosen once, right after connecting.
// 'me' is this handler's own copy of the client: the registry's copy
// is the one that changes, so on success 'me' is refreshed to match.
static void handle_set_username(int client_sock, Client *me, int argc, char *argv[])
{
    if (argc != 2)
    {
        client_send_line(client_sock, "ERROR SET_USERNAME BAD_ARGS");
        return;
    }

    if (!is_valid_name(argv[1], USERNAME_LEN - 1))
    {
        client_send_line(client_sock, "ERROR SET_USERNAME INVALID_NAME");
        return;
    }

    SetUsernameResult sr = client_list_set_username(me->id, argv[1]);

    if (sr == SET_USERNAME_OK)
    {
        strncpy(me->username, argv[1], USERNAME_LEN);
        printf("[SERVER] Client id=%d chose username %s\n", me->id, me->username);
        client_send_line(client_sock, "USERNAME_SET %s", me->username);
        return;
    }

    const char *code = set_username_error_code(sr);
    printf("[SERVER] Client id=%d username %s rejected: %s\n", me->id, argv[1], code);
    client_send_line(client_sock, "ERROR SET_USERNAME %s", code);
}

// Translates SetUsernameResult (internal to client_registry.c) into the ERROR codes
static const char *set_username_error_code(SetUsernameResult r)
{
    switch (r)
    {
        case SET_USERNAME_ERR_ALREADY_NAMED: return "ALREADY_NAMED";
        case SET_USERNAME_ERR_TAKEN:         return "USERNAME_TAKEN";
        default:                             return "ALREADY_NAMED";
    }
}

// What to call a client in the server's log: its username, or "(no name)"
// while it hasn't chosen one yet.
static const char *log_name(const Client *me)
{
    return me->username[0] != '\0' ? me->username : "(no name)";
}

static void handle_create_game(int client_sock, const Client *me, int argc, char *argv[])
{
    if (argc != 2)
    {
        client_send_line(client_sock, "ERROR CREATE_GAME BAD_ARGS");
        return;
    }

    if (!is_valid_name(argv[1], ROOM_NAME_LEN - 1))
    {
        printf("[SERVER] [%s] Create game rejected: invalid name\n", me->username);
        client_send_line(client_sock, "ERROR CREATE_GAME INVALID_NAME");
        return;
    }

    Game g;
    CreateResult cr = game_create(client_sock, argv[1], &g);

    if (cr == CREATE_OK)
    {
        client_send_line(client_sock, "GAME_CREATED %d %s", g.id, g.name);
        client_broadcast_except(client_sock, -1, "NEW_GAME %d %s %s", g.id, g.name, me->username);
        return;
    }

    const char *code = create_error_code(cr);
    printf("[SERVER] [%s] Create game rejected: %s\n", me->username, code);
    client_send_line(client_sock, "ERROR CREATE_GAME %s", code);
}

// Translates CreateResult (internal to game_registry.c) into ERROR codes
static const char *create_error_code(CreateResult r)
{
    switch (r)
    {
        case CREATE_ERR_SERVER_FULL:    return "SERVER_FULL";
        case CREATE_ERR_TOO_MANY_GAMES: return "TOO_MANY_GAMES";
        default:                        return "SERVER_FULL";
    }
}

static void handle_list_games(int client_sock)
{
    GameInfo games[MAX_GAMES_IN_LIST];
    int count = game_registry_list_waiting(client_sock, games);

    char entries[MAX_LINE - sizeof("GAME_LIST 00")];
    size_t len = 0;
    int listed = 0;

    for (int i = 0; i < count; i++)
    {
        char owner_username[USERNAME_LEN];
        if (!client_list_find_username(games[i].owner_sock, owner_username))
        {
            continue; // the owner disconnected since the snapshot: this game is about to be removed
        }

        int n = snprintf(entries + len, sizeof(entries) - len, " %d %s %s",
                         games[i].game_id, games[i].name, owner_username);
        if (n < 0 || (size_t)n >= sizeof(entries) - len)
        {
            break; // this entry would be cut off: stop before it
        }
        len += n;
        listed++;
    }
    entries[len] = '\0'; // drops the partial entry snprintf may have written past 'len'

    client_send_line(client_sock, "GAME_LIST %d%s", listed, entries);
}

static void handle_list_my_games(int client_sock)
{
    MyGameInfo games[MAX_GAMES_PER_PLAYER];
    int n = game_registry_list_my_games(client_sock, games, MAX_GAMES_PER_PLAYER);

    char entries[MAX_GAMES_PER_PLAYER * 96];
    size_t len = 0;
    int count = 0;
    entries[0] = '\0';

    for (int i = 0; i < n; i++)
    {
        char opponent[USERNAME_LEN];
        
        if (games[i].opponent_sock == -1)
        {
            strcpy(opponent, "-");
        }
        else if (!client_list_find_username(games[i].opponent_sock, opponent))
        {
            continue; 
        }

        len += snprintf(entries + len, sizeof(entries) - len, " %d %s %s %d %s %d %s",
                        games[i].game_id, games[i].name, opponent, games[i].my_player,
                        room_state_name(games[i].state), games[i].turn,
                        (games[i].opponent_sock != -1 && is_away(games[i].opponent_sock, games[i].game_id)) ? "AWAY" : "HERE");
        count++;
    }

    client_send_line(client_sock, "MY_GAME_LIST %d%s", count, entries);
}

static const char *room_state_name(RoomState s)
{
    switch (s)
    {
        case GAME_WAITING:  return "WAITING";
        case GAME_PLAYING:  return "PLAYING";
        case GAME_FINISHED: return "FINISHED";
        default:            return "WAITING";
    }
}


static void handle_join_game(int client_sock, const Client *me, int argc, char *argv[])
{
    int game_id;
    if (argc != 2 || !parse_int(argv[1], &game_id))
    {
        client_send_line(client_sock, "ERROR JOIN_GAME BAD_ARGS");
        return;
    }

    Game g;
    JoinSetResult jr = game_registry_set_pending(game_id, client_sock, &g);

    if (jr == JOIN_OK)
    {
        // Push notification straight to the owner's socket: they are not
        // the one who sent this command (see docs/protocol.md §4). No
        // reply to the joiner here: the outcome (JOIN_RESULT) arrives
        // later, once the owner answers.
        client_send_line(g.owner_sock, "JOIN_NOTIFY %d %s", game_id, me->username);
        return;
    }

    const char *code = join_set_error_code(jr);
    printf("[SERVER] [%s] Join game %d rejected: %s\n", me->username, game_id, code);
    client_send_line(client_sock, "ERROR JOIN_GAME %s", code);
}

// Translates JoinSetResult (internal to game_registry.c) into the ERROR
// codes of docs/protocol.md §1.5. Kept here rather than in
// game_registry.c: the registry only needs to reason about game state, not
// about wire-format strings.
static const char *join_set_error_code(JoinSetResult r)
{
    switch (r)
    {
        case JOIN_ERR_NOT_FOUND:       return "NOT_FOUND";
        case JOIN_ERR_NOT_WAITING:     return "NOT_WAITING";
        case JOIN_ERR_SELF_JOIN:       return "SELF_JOIN";
        case JOIN_ERR_ALREADY_PENDING: return "ALREADY_PENDING";
        case JOIN_ERR_TOO_MANY_GAMES:  return "TOO_MANY_GAMES";
        default:                       return "NOT_FOUND"; // JOIN_OK never reaches here
    }
}

static void handle_join_response(int client_sock, const Client *me, int argc, char *argv[])
{
    int game_id, accepted;
    if (argc != 3 || !parse_int(argv[1], &game_id) || !parse_int(argv[2], &accepted) ||
        (accepted != 0 && accepted != 1))
    {
        // docs/protocol.md §4: <accepted> is 0 or 1, nothing else counts as "yes"
        client_send_line(client_sock, "ERROR JOIN_RESPONSE BAD_ARGS");
        return;
    }

    Game g;
    ResolveResult rr = game_registry_resolve_join(game_id, client_sock, accepted, &g);

    if (rr == RESOLVE_OK)
    {
        // g.pending_joiner_sock is restored by resolve_join to the
        // joiner's socket regardless of accept/reject, so it's always the
        // right destination for the result.
        client_send_line(g.pending_joiner_sock, "JOIN_RESULT %d %d", game_id, accepted);
        if (accepted)
        {
            client_broadcast_except(-1, -1, "GAME_IN_PROGRESS %d", game_id);
            send_game_start(&g);
        }
        return;
    }

    if (rr == RESOLVE_ERR_JOINER_FULL)
    {
        // The request is over, as if the owner had refused it: the joiner
        // has to be told (the owner gets the error below).
        client_send_line(g.pending_joiner_sock, "JOIN_RESULT %d 0", game_id);
    }

    const char *code = resolve_error_code(rr);
    printf("[SERVER] [%s] Join response for game %d rejected: %s\n", me->username, game_id, code);
    client_send_line(client_sock, "ERROR JOIN_RESPONSE %s", code);
}

// Translates ResolveResult (internal to game_registry.c) into the ERROR
// codes of docs/protocol.md §1.5, same reasoning as join_set_error_code.
static const char *resolve_error_code(ResolveResult r)
{
    switch (r)
    {
        case RESOLVE_ERR_NOT_FOUND:   return "NOT_FOUND";
        case RESOLVE_ERR_NOT_OWNER:   return "NOT_OWNER";
        case RESOLVE_ERR_NO_PENDING:  return "NO_PENDING";
        case RESOLVE_ERR_JOINER_FULL: return "JOINER_FULL";
        default:                      return "NOT_FOUND"; // RESOLVE_OK never reaches here
    }
}

// Sends GAME_START (telling each player their own number and the
// opponent's username) followed by the initial GAME_STATE, to both
// players of 'g'. Called whenever a game starts: from an accepted join, and
// when both players have asked for a rematch (docs/protocol.md §7).
static void send_game_start(const Game *g)
{
    char owner_username[USERNAME_LEN];
    char player2_username[USERNAME_LEN];

    // A player that disconnected since 'g' was read from the registry no
    // longer has a username, and its handler is about to close the game
    // and tell the other player. Send nothing rather than a GAME_START
    // with an empty opponent name, which would be a malformed line.
    if (!client_list_find_username(g->owner_sock, owner_username) ||
        !client_list_find_username(g->player2_sock, player2_username))
    {
        return;
    }

    // Before anything is sent: the player can answer GAME_START with a MOVE at
    // once, and it must find the game active.
    int owner_was_active = client_list_get_active_game(g->owner_sock);
    int player2_was_active = client_list_get_active_game(g->player2_sock);
    activate_if_idle(g->owner_sock, g->id);
    activate_if_idle(g->player2_sock, g->id);

    client_send_line(g->owner_sock, "GAME_START %d 1 %s", g->id, player2_username);
    client_send_line(g->player2_sock, "GAME_START %d 2 %s", g->id, owner_username);

    send_game_state(g);

    // Presence. The activation above may have changed what the opponents of
    // the two players' other games see. For this game GAME_START means HERE
    // (on a rematch too, whatever was said before), so the only thing worth
    // telling is a player that is AWAY, because it was already playing
    // another game.
    announce_presence(g->owner_sock, owner_was_active, client_list_get_active_game(g->owner_sock), g->id);
    announce_presence(g->player2_sock, player2_was_active, client_list_get_active_game(g->player2_sock), g->id);
    if (is_away(g->owner_sock, g->id))
    {
        client_send_line(g->player2_sock, "OPPONENT_STATUS %d AWAY", g->id);
    }
    if (is_away(g->player2_sock, g->id))
    {
        client_send_line(g->owner_sock, "OPPONENT_STATUS %d AWAY", g->id);
    }
}

// Sends GAME_STATE (the board and whose turn it is) to both players of
// 'g'. Used right after a game starts and after every valid move.
static void send_game_state(const Game *g)
{
    char board_str[BOARD_ROWS * BOARD_COLS + 1];
    board_to_string(&g->board, board_str);
    client_send_line(g->owner_sock, "GAME_STATE %d %d %s", g->id, g->turn, board_str);
    client_send_line(g->player2_sock, "GAME_STATE %d %d %s", g->id, g->turn, board_str);
}

static void handle_move(int client_sock, const Client *me, int argc, char *argv[])
{
    int game_id, column;
    if (argc != 3 || !parse_int(argv[1], &game_id) || !parse_int(argv[2], &column))
    {
        client_send_line(client_sock, "ERROR MOVE BAD_ARGS");
        return;
    }

    // The active game lives in the client registry, so it is looked up here and
    // handed to the registry, which puts the check in its place among the
    // others (docs/protocol.md §5.3).
    int is_active = (client_list_get_active_game(client_sock) == game_id);

    Game g;
    MoveResult mr = game_registry_apply_move(game_id, client_sock, column, is_active, &g);

    if (mr == MOVE_OK)
    {
        send_game_state(&g);
        if (g.state == GAME_FINISHED)
        {
            send_game_over(&g);
            // Other clients only ever saw this game as GAME_IN_PROGRESS
            // (docs/protocol.md §6); now that it's really over, tell
            // them to drop it from whatever list they're keeping.
            client_broadcast_except(g.owner_sock, g.player2_sock, "GAME_CLOSED %d", g.id);
        }
        return;
    }

    const char *code = move_error_code(mr);
    printf("[SERVER] [%s] Move on game %d rejected: %s\n", me->username, game_id, code);
    client_send_line(client_sock, "ERROR MOVE %s", code);
}

// Sends GAME_OVER to both players of 'g', personalized per docs/protocol.md
// §5: WIN to the winner, LOSE to the loser, or DRAW to both. Only valid
// once g->state is GAME_FINISHED (i.e. right after send_game_state, from
// handle_move above).
static void send_game_over(const Game *g)
{
    if (g->winner == 0)
    {
        client_send_line(g->owner_sock, "GAME_OVER %d DRAW", g->id);
        client_send_line(g->player2_sock, "GAME_OVER %d DRAW", g->id);
        return;
    }

    int winner_sock = (g->winner == 1) ? g->owner_sock : g->player2_sock;
    int loser_sock = (g->winner == 1) ? g->player2_sock : g->owner_sock;
    client_send_line(winner_sock, "GAME_OVER %d WIN", g->id);
    client_send_line(loser_sock, "GAME_OVER %d LOSE", g->id);
}

// LEAVE_GAME: the sender leaves a game it is a player of (docs/protocol.md
// §7). The registry applies the same rules as for a disconnect; the sender
// gets GAME_LEFT first and then, like everyone else, whatever
// notify_leave_events says about the game it left.
static void handle_leave_game(int client_sock, const Client *me, int argc, char *argv[])
{
    int game_id;
    if (argc != 2 || !parse_int(argv[1], &game_id))
    {
        client_send_line(client_sock, "ERROR LEAVE_GAME BAD_ARGS");
        return;
    }

    LeaveEvent event;
    LeaveResult lr = game_registry_leave(game_id, client_sock, &event);

    if (lr == LEAVE_OK)
    {
        client_send_line(client_sock, "GAME_LEFT %d", game_id);
        notify_leave_events(client_sock, &event, 1);
        return;
    }

    const char *code = leave_error_code(lr);
    printf("[SERVER] [%s] Leave game %d rejected: %s\n", me->username, game_id, code);
    client_send_line(client_sock, "ERROR LEAVE_GAME %s", code);
}

// Translates LeaveResult (internal to game_registry.c) into the ERROR
// codes of docs/protocol.md §1.5, same reasoning as join_set_error_code.
static const char *leave_error_code(LeaveResult r)
{
    switch (r)
    {
        case LEAVE_ERR_NOT_FOUND:  return "NOT_FOUND";
        case LEAVE_ERR_NOT_PLAYER: return "NOT_PLAYER";
        default:                   return "NOT_FOUND"; // LEAVE_OK never reaches here
    }
}

// REMATCH: the sender wants to play again in a finished game (docs/protocol.md
// §7). Nothing is sent back to the sender for a vote that has to wait: the
// answer is the game starting, or an ERROR. The opponent is told a rematch
// was asked for. When both have asked, the game restarts and both get
// GAME_START + GAME_STATE, exactly as when a join is accepted. Other clients
// hear nothing: they were told GAME_CLOSED when the game ended, and it is
// not in their list.
static void handle_rematch(int client_sock, const Client *me, int argc, char *argv[])
{
    int game_id;
    if (argc != 2 || !parse_int(argv[1], &game_id))
    {
        client_send_line(client_sock, "ERROR REMATCH BAD_ARGS");
        return;
    }

    Game g;
    RematchResult rr = game_registry_rematch(game_id, client_sock, &g);

    if (rr == REMATCH_STARTED)
    {
        send_game_start(&g);
        return;
    }

    if (rr == REMATCH_WAITING)
    {
        int opponent_sock = (g.owner_sock == client_sock) ? g.player2_sock : g.owner_sock;
        client_send_line(opponent_sock, "REMATCH_NOTIFY %d", game_id);
        return;
    }

    const char *code = rematch_error_code(rr);
    printf("[SERVER] [%s] Rematch on game %d rejected: %s\n", me->username, game_id, code);
    client_send_line(client_sock, "ERROR REMATCH %s", code);
}

// Translates RematchResult (internal to game_registry.c) into the ERROR
// codes of docs/protocol.md §1.5, same reasoning as join_set_error_code.
static const char *rematch_error_code(RematchResult r)
{
    switch (r)
    {
        case REMATCH_ERR_NOT_FOUND:       return "NOT_FOUND";
        case REMATCH_ERR_NOT_PLAYER:      return "NOT_PLAYER";
        case REMATCH_ERR_NOT_FINISHED:    return "NOT_FINISHED";
        case REMATCH_ERR_ALREADY_VOTED:   return "ALREADY_PENDING";
        default:                          return "NOT_FOUND"; // REMATCH_WAITING/STARTED never reach here
    }
}

// Translates MoveResult (internal to game_registry.c) into the ERROR
// codes of docs/protocol.md §1.5, same reasoning as join_set_error_code.
static const char *move_error_code(MoveResult r)
{
    switch (r)
    {
        case MOVE_ERR_NOT_FOUND:      return "NOT_FOUND";
        case MOVE_ERR_NOT_PLAYER:     return "NOT_PLAYER";
        case MOVE_ERR_NOT_PLAYING:    return "NOT_PLAYING";
        case MOVE_ERR_NOT_ACTIVE:     return "NOT_ACTIVE";
        case MOVE_ERR_NOT_YOUR_TURN:  return "NOT_YOUR_TURN";
        case MOVE_ERR_INVALID_COLUMN: return "INVALID_COLUMN";
        case MOVE_ERR_COLUMN_FULL:    return "COLUMN_FULL";
        default:                      return "NOT_FOUND"; // MOVE_OK never reaches here
    }
}

// SET_ACTIVE_GAME: the sender chooses which of its games it is playing right
// now (docs/protocol.md §5.3). 0 means none. A success gets no reply: the
// next MOVE shows it, and an error says why it did not work.
static void handle_set_active_game(int client_sock, const Client *me, int argc, char *argv[])
{
    int game_id;
    if (argc != 2 || !parse_int(argv[1], &game_id))
    {
        client_send_line(client_sock, "ERROR SET_ACTIVE_GAME BAD_ARGS");
        return;
    }

    if (game_id != 0)
    {
        ActivateResult ar = game_registry_check_activate(game_id, client_sock);
        if (ar != ACTIVATE_OK)
        {
            const char *code = activate_error_code(ar);
            printf("[SERVER] [%s] Active game %d rejected: %s\n", me->username, game_id, code);
            client_send_line(client_sock, "ERROR SET_ACTIVE_GAME %s", code);
            return;
        }
    }

    set_active_game(client_sock, game_id);
}

static const char *activate_error_code(ActivateResult r)
{
    switch (r)
    {
        case ACTIVATE_ERR_NOT_FOUND:   return "NOT_FOUND";
        case ACTIVATE_ERR_NOT_PLAYER:  return "NOT_PLAYER";
        case ACTIVATE_ERR_NOT_PLAYING: return "NOT_PLAYING";
        default:                       return "NOT_FOUND"; // ACTIVATE_OK never reaches here
    }
}

// A game that starts becomes the active game of a player who has none, or
// whose active game is no longer being played (it finished): that player has
// nothing else to be busy with. A player in the middle of another game keeps
// it and switches on its own with SET_ACTIVE_GAME. This is what lets a client
// that plays one game at a time never hear of SET_ACTIVE_GAME.
static void activate_if_idle(int sock, int game_id)
{
    int current = client_list_get_active_game(sock);

    if (current != game_id && (current == 0 || game_registry_state(current) != GAME_PLAYING))
    {
        client_list_set_active_game(sock, game_id);
    }
}

// 1 if the active game of 'sock' is not 'game_id': the player is in the lobby
// or in another game, so it is not looking at this one and whoever plays
// 'game_id' against it is told it is AWAY (docs/protocol.md §5.3). Being in
// 'game_id' itself is HERE.
static int is_away(int sock, int game_id)
{
    return client_list_get_active_game(sock) != game_id;
}

// The active game of 'sock' went from 'old_active' to 'new_active': tells the
// opponent in each of its games whose status changed because of it
// (OPPONENT_STATUS). Nothing is sent to the ones that see no difference. The
// game 'skip_game' is left out: a game that has just started is handled by
// send_game_start.
static void announce_presence(int sock, int old_active, int new_active, int skip_game)
{
    MyGameInfo matches[MAX_GAMES_PER_PLAYER];
    int n = game_registry_list_my_games(sock, matches, MAX_GAMES_PER_PLAYER);

    for (int i = 0; i < n; i++)
    {
        int id = matches[i].game_id;
        if (id == skip_game)
        {
            continue;
        }

        int was_away = (old_active != id);
        int now_away = (new_active != id);
        if (was_away != now_away)
        {
            client_send_line(matches[i].opponent_sock, "OPPONENT_STATUS %d %s",
                             id, now_away ? "AWAY" : "HERE");
        }
    }
}

// Changes the active game of 'sock' and tells the opponents affected.
static void set_active_game(int sock, int game_id)
{
    int old_active = client_list_get_active_game(sock);

    if (old_active != game_id)
    {
        client_list_set_active_game(sock, game_id);
        announce_presence(sock, old_active, game_id, 0);
    }
}

// The active game of 'sock' is 'game_id' no more (it left it, or it is gone):
// none is active, if that was the one.
static void clear_active_game(int sock, int game_id)
{
    if (client_list_get_active_game(sock) == game_id)
    {
        set_active_game(sock, 0);
    }
}
