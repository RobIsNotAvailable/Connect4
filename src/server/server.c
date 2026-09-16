#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include "client_registry.h"
#include "game_registry.h"
#include "net.h"
#include "protocol.h"

// Forward declarations of every function defined below, in the order
// main() reaches them: main -> client_handler -> dispatch_command ->
// one handle_* per command (which may call an *_error_code translator).
// Keeping only signatures here lets the definitions further down read
// top-down, starting from main().
static void *client_handler(void *sock_id);
static void dispatch_command(int client_sock, const Client *me, char *line);
static void handle_create_game(int client_sock, const Client *me);
static void handle_list_games(int client_sock);
static void handle_join_game(int client_sock, const Client *me, int argc, char *argv[]);
static const char *join_set_error_code(JoinSetResult r);
static void handle_join_response(int client_sock, const Client *me, int argc, char *argv[]);
static const char *resolve_error_code(ResolveResult r);

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

    printf("[SERVER] New client connected on socket %d, assigned id=%d username=%s\n", client_sock, me.id, me.username);

    client_send_line(client_sock, "WELCOME %d %s", me.id, me.username);

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
        printf("[SERVER] Client %s (socket %d) disconnected\n", me.username, client_sock);
    }
    else if (comm_status == LINE_TOO_LONG)
    {
        // docs/protocol.md §1.2: a client that sends a line longer than
        // MAX_LINE gets disconnected, with no error message.
        printf("[SERVER] Client %s (socket %d) sent a line longer than %d bytes, closing\n",
               me.username, client_sock, MAX_LINE);
    }
    else
    {
        perror("[SERVER] Error in receiving data from client");
    }

    client_list_remove(me.id);
    game_registry_remove_by_owner(client_sock);
    close(client_sock);
    pthread_exit(NULL);
}

// Splits one received line into command + arguments and calls the
// matching handler. An unrecognized command, or one that fails its own
// validation, gets "ERROR <comando> <codice>" per docs/protocol.md §1.5.
static void dispatch_command(int client_sock, const Client *me, char *line)
{
    char *argv[MAX_ARGS];
    int argc = split_args(line, argv, MAX_ARGS);

    if (argc == 0)
    {
        return; // blank line: the protocol doesn't forbid it, just ignore it
    }

    const char *cmd = argv[0];
    printf("[SERVER] [%s] Received command: %s\n", me->username, cmd);

    if (strcmp(cmd, "CREATE_GAME") == 0)
    {
        handle_create_game(client_sock, me);
    }
    else if (strcmp(cmd, "LIST_GAMES") == 0)
    {
        handle_list_games(client_sock);
    }
    else if (strcmp(cmd, "JOIN_GAME") == 0)
    {
        handle_join_game(client_sock, me, argc, argv);
    }
    else if (strcmp(cmd, "JOIN_RESPONSE") == 0)
    {
        handle_join_response(client_sock, me, argc, argv);
    }
    else
    {
        printf("[SERVER] [%s] Unknown command: %s\n", me->username, cmd);
        client_send_line(client_sock, "ERROR - UNKNOWN_COMMAND");
    }
}

static void handle_create_game(int client_sock, const Client *me)
{
    Game g = game_create(client_sock, me->username);

    if (g.id == -1)
    {
        client_send_line(client_sock, "ERROR CREATE_GAME SERVER_FULL");
    }
    else
    {
        client_send_line(client_sock, "GAME_CREATED %d", g.id);
    }
}

static void handle_list_games(int client_sock)
{
    GameInfo games[MAX_GAMES_IN_LIST];
    int count = game_registry_list_waiting(games);

    char reply[MAX_LINE];
    int len = snprintf(reply, sizeof(reply), "GAME_LIST %d", count);
    for (int i = 0; i < count && len < (int)sizeof(reply); i++)
    {
        len += snprintf(reply + len, sizeof(reply) - len, " %d %s",
                         games[i].game_id, games[i].owner_username);
    }
    client_send_line(client_sock, "%s", reply);
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
        default:                       return "NOT_FOUND"; // JOIN_OK never reaches here
    }
}

static void handle_join_response(int client_sock, const Client *me, int argc, char *argv[])
{
    int game_id, accepted;
    if (argc != 3 || !parse_int(argv[1], &game_id) || !parse_int(argv[2], &accepted))
    {
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
        return;
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
        case RESOLVE_ERR_NOT_FOUND:  return "NOT_FOUND";
        case RESOLVE_ERR_NOT_OWNER:  return "NOT_OWNER";
        case RESOLVE_ERR_NO_PENDING: return "NO_PENDING";
        default:                     return "NOT_FOUND"; // RESOLVE_OK never reaches here
    }
}
