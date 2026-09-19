#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include "net.h"
#include "protocol.h"

// Tracks a join request the server notified us about (we are the game
// owner) that hasn't been answered yet. Only one at a time: a second
// JOIN_NOTIFY while one is already pending would just overwrite it,
// but the server never sends one before the previous is resolved (see
// game_registry_set_pending in game_registry.c), so this is safe as-is.
typedef struct
{
    int valid;
    int game_id;
    char joiner_username[USERNAME_LEN];
    pthread_mutex_t mutex;
} PendingNotify;

static PendingNotify pending_notify = {
    .valid = 0,
    .mutex = PTHREAD_MUTEX_INITIALIZER
};

// The LineReader is shared between main() (which reads the initial
// WELCOME) and receiver_thread (which keeps reading after): bytes that
// arrive "early" stay buffered inside it, so it has to be the same object
// for the whole connection. File-scope, like pending_notify, rather than a
// local in main(): the detached thread may still be running after main()
// returns.
static LineReader g_reader;

// Forward declarations of every function defined below, in the order
// main() reaches them: first the receiver thread it spawns (and
// everything that thread calls, down to the individual message
// handlers), then the menu commands main() calls directly. Keeping only
// signatures here lets the definitions further down read top-down,
// starting from main().
static void *receiver_thread(void *sock_ptr);
static void dispatch_reply(char *line);
static void handle_game_list(char *line);
static void handle_game_created(char *argv[]);
static void handle_join_notify(char *argv[]);
static void set_pending_notify(int game_id, const char *joiner_username);
static void handle_join_result(char *argv[]);
static void handle_error(char *argv[]);
static void choose_username(int sock);
static void handle_username_set(char *argv[]);
static void create_game(int sock, const char *name);
static void list_games(int sock);
static void join_game(int sock, int game_id);
static void respond_to_join(int sock, int accepted);
static int take_pending_notify(int *out_game_id);

int main()
{
    int sock = 0;
    struct sockaddr_in serv_addr;

    if ((sock = socket(AF_INET, SOCK_STREAM, 0)) < 0)
    {
        perror("Error in creating the client socket\n");
        return -1;
    }

    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(PORT);

    // Explicitly set the server IP. Without this, sin_addr is left
    // uninitialized; it happened to work on Linux because a fresh stack
    // is usually zeroed and connect() treats 0.0.0.0 as localhost, but
    // that behavior is not guaranteed (e.g. across containers in Docker).
    if (inet_pton(AF_INET, "127.0.0.1", &serv_addr.sin_addr) <= 0)
    {
        perror("Invalid server address\n");
        return -1;
    }

    if (connect(sock, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0)
    {
        perror("Connection failed\n");
        return -1;
    }

    // The WELCOME is a text line like any other, read on the same
    // LineReader that receiver_thread will use for the rest of the
    // connection.
    line_reader_init(&g_reader, sock);
    char line[MAX_LINE];
    char *argv[MAX_ARGS];
    int argc;

    if (recv_line(&g_reader, line) == LINE_OK &&
        (argc = split_args(line, argv, MAX_ARGS)) == 2 &&
        strcmp(argv[0], "WELCOME") == 0)
    {
        printf("[CLIENT] Connected (id=%s)\n", argv[1]);
    }
    else
    {
        fprintf(stderr, "[CLIENT] Did not receive a valid welcome message from the server\n");
        close(sock);
        return -1;
    }

    // Start the receiver thread now: from this point on, every reply
    // (including the synchronous-looking ones for create/list) arrives
    // asynchronously and is printed by receiver_thread.
    pthread_t recv_tid;
    if (pthread_create(&recv_tid, NULL, receiver_thread, &sock) != 0)
    {
        perror("[CLIENT] Error creating receiver thread\n");
        close(sock);
        return -1;
    }
    pthread_detach(recv_tid);

    // Until a username is chosen the server rejects every other command
    // (docs/protocol.md §2), so ask for it before showing the menu.
    choose_username(sock);

    // Minimal menu to exercise the commands implemented so far. Will be
    // replaced by the full textual menu + grid rendering in Phase 7.
    int choice = 0;
    do
    {
        printf("\n1) Create game\n2) List games\n3) Join game\n4) Respond to join request\n5) Choose username\n0) Exit\n> ");
        if (scanf("%d", &choice) != 1)
        {
            break;
        }

        int game_id, accept;
        char name[64]; // wider than ROOM_NAME_LEN on purpose: lets the server reject a too-long name
        switch (choice)
        {
            case 1:
                printf("Game name: ");
                if (scanf("%63s", name) == 1)
                {
                    create_game(sock, name);
                }
                break;
            case 2:
                list_games(sock);
                break;
            case 3:
                printf("Game id to join: ");
                if (scanf("%d", &game_id) == 1)
                {
                    join_game(sock, game_id);
                }
                break;
            case 4:
                printf("Accept? (1=yes, 0=no): ");
                if (scanf("%d", &accept) == 1)
                {
                    respond_to_join(sock, accept);
                }
                break;
            case 5:
                choose_username(sock); // retry after a rejected name
                break;
        }
    } while (choice != 0);

    close(sock);
    return 0;
}

// Runs in its own thread for the whole lifetime of the connection: the
// only place that calls recv_line() on the socket, since command replies
// are no longer synchronous (a join request notifies a socket that isn't
// the one who sent the command, so blocking send()+recv() pairs don't
// work anymore for every command).
static void *receiver_thread(void *sock_ptr)
{
    (void)sock_ptr; // the real socket lives inside g_reader by now
    char line[MAX_LINE];
    int status;

    while ((status = recv_line(&g_reader, line)) == LINE_OK)
    {
        dispatch_reply(line);
    }

    if (status == LINE_CLOSED)
    {
        printf("\n[CLIENT] Server closed the connection\n");
    }
    else if (status == LINE_TOO_LONG)
    {
        printf("\n[CLIENT] Server sent a line longer than %d bytes\n", MAX_LINE);
    }
    else
    {
        perror("\n[CLIENT] Error in receiving data from server\n");
    }
    exit(0); // the menu loop can't do anything useful once the socket is gone
}

// Reads the command name off 'line' and calls the matching handler.
static void dispatch_reply(char *line)
{
    char cmd[32];
    if (sscanf(line, "%31s", cmd) != 1)
    {
        return; // blank line
    }

    if (strcmp(cmd, "GAME_LIST") == 0)
    {
        handle_game_list(line);
    }
    else
    {
        char *argv[MAX_ARGS];
        int argc = split_args(line, argv, MAX_ARGS);

        if (strcmp(cmd, "GAME_CREATED") == 0 && argc == 3)
        {
            handle_game_created(argv);
        }
        else if (strcmp(cmd, "USERNAME_SET") == 0 && argc == 2)
        {
            handle_username_set(argv);
        }
        else if (strcmp(cmd, "JOIN_NOTIFY") == 0 && argc == 3)
        {
            handle_join_notify(argv);
        }
        else if (strcmp(cmd, "JOIN_RESULT") == 0 && argc == 3)
        {
            handle_join_result(argv);
        }
        else if (strcmp(cmd, "ERROR") == 0 && argc == 3)
        {
            handle_error(argv);
        }
        else
        {
            printf("\n[CLIENT] Unhandled or malformed reply: %s\n> ", line);
        }
    }

    fflush(stdout);
}

// GAME_LIST has a variable-length tail (up to 32 games): parsed here on
// its own instead of through split_args()/MAX_ARGS, which would truncate
// it. Takes the raw line, not pre-split argv.
static void handle_game_list(char *line)
{
    char *saveptr;
    strtok_r(line, " ", &saveptr); // consume "GAME_LIST"
    char *count_tok = strtok_r(NULL, " ", &saveptr);
    int count = (count_tok != NULL) ? atoi(count_tok) : 0;

    if (count == 0)
    {
        printf("\n[CLIENT] No games waiting for players\n> ");
        return;
    }

    printf("\n[CLIENT] Games waiting for players:\n");
    for (int i = 0; i < count; i++)
    {
        char *id_tok = strtok_r(NULL, " ", &saveptr);
        char *name_tok = strtok_r(NULL, " ", &saveptr);
        char *owner_tok = strtok_r(NULL, " ", &saveptr);
        if (id_tok == NULL || name_tok == NULL || owner_tok == NULL)
        {
            break; // malformed line: show what we got instead of crashing
        }
        printf("  id=%s name=%s owner=%s\n", id_tok, name_tok, owner_tok);
    }
    printf("> ");
}

static void handle_game_created(char *argv[])
{
    printf("\n[CLIENT] Game created, id=%s name=%s\n> ", argv[1], argv[2]);
}

static void handle_username_set(char *argv[])
{
    printf("\n[CLIENT] Username set to %s\n> ", argv[1]);
}

static void handle_join_notify(char *argv[])
{
    set_pending_notify(atoi(argv[1]), argv[2]);
    printf("\n[CLIENT] %s wants to join your game (id=%s) - use menu option 4 to respond\n> ",
           argv[2], argv[1]);
}

// Records the join request we were just notified about.
static void set_pending_notify(int game_id, const char *joiner_username)
{
    pthread_mutex_lock(&pending_notify.mutex);
    pending_notify.valid = 1;
    pending_notify.game_id = game_id;
    strncpy(pending_notify.joiner_username, joiner_username, USERNAME_LEN);
    pthread_mutex_unlock(&pending_notify.mutex);
}

static void handle_join_result(char *argv[])
{
    if (strcmp(argv[2], "1") == 0)
    {
        printf("\n[CLIENT] Join request for game %s accepted\n> ", argv[1]);
    }
    else
    {
        printf("\n[CLIENT] Join request for game %s refused\n> ", argv[1]);
    }
}

static void handle_error(char *argv[])
{
    printf("\n[CLIENT] Error on %s: %s\n> ", argv[1], argv[2]);
}

// Asks for a username and sends SET_USERNAME. Like the other commands, the
// reply (USERNAME_SET or an ERROR) is printed by the receiver thread. The
// buffer is wider than the 20-character limit on purpose, so the server is
// the one that rejects a name that is too long.
static void choose_username(int sock)
{
    char name[64];

    printf("Username: ");
    if (scanf("%63s", name) == 1)
    {
        send_line(sock, "SET_USERNAME %s", name);
    }
}

// Sends CREATE_GAME with the chosen name. The reply is printed by the
// receiver thread, not here: only one thread may recv() on the socket (see
// receiver_thread).
static void create_game(int sock, const char *name)
{
    send_line(sock, "CREATE_GAME %s", name);
}

// Sends LIST_GAMES. Reply printed by the receiver thread.
static void list_games(int sock)
{
    send_line(sock, "LIST_GAMES");
}

// Sends JOIN_GAME for the given game id. Reply printed by the
// receiver thread once the owner answers.
static void join_game(int sock, int game_id)
{
    send_line(sock, "JOIN_GAME %d", game_id);
}

// Sends JOIN_RESPONSE for whichever join request is currently
// pending (see pending_notify). No-op with a message if none is pending.
static void respond_to_join(int sock, int accepted)
{
    int game_id;
    if (!take_pending_notify(&game_id))
    {
        printf("[CLIENT] No pending join request to respond to\n");
        return;
    }

    send_line(sock, "JOIN_RESPONSE %d %d", game_id, accepted);
}

// If a join request is currently pending, clears it and returns 1 with
// 'out_game_id' filled in. Returns 0 (leaving 'out_game_id' untouched) if
// there's none pending.
static int take_pending_notify(int *out_game_id)
{
    pthread_mutex_lock(&pending_notify.mutex);

    int had_one = pending_notify.valid;
    if (had_one)
    {
        *out_game_id = pending_notify.game_id;
        pending_notify.valid = 0;
    }

    pthread_mutex_unlock(&pending_notify.mutex);

    return had_one;
}
