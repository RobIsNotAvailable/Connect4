#include <string.h>
#include "game_registry.h"

static Game *find_game(int game_id);
static int is_player(const Game *g, int sock);
static int count_games(int sock);
static void start_round(Game *g);
static void leave_game(Game *g, int sock, LeaveEvent *event);

// The games, with no lock of their own: the server calls every function of
// this file with its command_mutex held (server.c). A slot map indexed
// directly by id, like ClientList (client_registry.c): games[id - 1] IS the
// game with that id when its state isn't GAME_EMPTY, and freed ids are reused.
typedef struct
{
    Game games[MAX_GAMES];      // slot map: games[id - 1] is the game with that id, or GAME_EMPTY if free
    int count;                  // how many slots are currently occupied
    int next_id;                // bump allocator: next never-before-used id, used when free_ids is empty
    int free_ids[MAX_GAMES];    // stack of freed ids waiting to be reused
    int free_count;             // how many entries in free_ids are currently valid
} GameRegistry;

static GameRegistry registry = { .next_id = 1 };

ErrorCode game_create(int owner_sock, const char *name, Game *out_game)
{
    if (count_games(owner_sock) >= MAX_GAMES_PER_PLAYER)
    {
        return ERR_TOO_MANY_GAMES;
    }
    if (registry.count >= MAX_GAMES)
    {
        return ERR_SERVER_FULL;
    }

    int id = (registry.free_count > 0)
        ? registry.free_ids[--registry.free_count]
        : registry.next_id++;
    registry.count++;

    // What is not named here is zero: an empty board, no turn, no winner,
    // no votes.
    Game *g = &registry.games[id - 1];
    *g = (Game) {
        .id = id,
        .owner_sock = owner_sock,
        .state = GAME_WAITING,
        .pending_joiner_sock = -1,
        .player2_sock = -1
    };
    strncpy(g->name, name, ROOM_NAME_LEN);

    *out_game = *g;
    return ERR_NONE;
}

// A single client can be involved (as owner, pending joiner, or player2) in
// several games at once, so this checks every occupied slot. Bounded by the
// highest id ever handed out rather than MAX_GAMES, and only runs once per
// disconnect, so the scan is cheap.
int game_registry_handle_disconnect(int sock, LeaveEvent *events)
{
    int n = 0;

    for (int i = 0; i < registry.next_id - 1; i++)
    {
        Game *g = &registry.games[i];
        if (g->state == GAME_EMPTY)
        {
            continue;
        }

        if (g->pending_joiner_sock == sock)
        {
            events[n++] = (LeaveEvent) {
                .type = LEAVE_JOIN_CANCELLED,
                .game_id = g->id,
                .notify_sock = g->owner_sock,
                .other_sock = -1
            };
            g->pending_joiner_sock = -1;
        }
        else if (is_player(g, sock))
        {
            leave_game(g, sock, &events[n++]);
        }
    }

    return n;
}

ErrorCode game_registry_leave(int game_id, int sock, LeaveEvent *event)
{
    Game *g = find_game(game_id);
    if (g == NULL)
    {
        return ERR_NOT_FOUND;
    }
    if (!is_player(g, sock))
    {
        return ERR_NOT_PLAYER;
    }

    leave_game(g, sock, event);
    return ERR_NONE;
}

// 'sock', the owner or player2 of 'g', leaves it, whatever the game's state.
// Fills 'event' with what the caller has to notify.
//
// Whoever is left in the game keeps it. If that is the owner, the game goes
// back to WAITING with a fresh board. If the owner is the one who left, the
// other player takes over as owner (always possible: the game already counts
// toward their MAX_GAMES_PER_PLAYER). With nobody left, the game is removed
// and its id goes back to the free-id stack.
static void leave_game(Game *g, int sock, LeaveEvent *event)
{
    int remaining_sock = game_opponent(g, sock); // -1 if nobody is left

    *event = (LeaveEvent) {
        .type = (remaining_sock == -1) ? LEAVE_ROOM_CLOSED : LEAVE_ROOM_REOPENED,
        .game_id = g->id,
        .notify_sock = remaining_sock,
        .other_sock = remaining_sock
    };
    strncpy(event->name, g->name, ROOM_NAME_LEN);

    if (remaining_sock == -1)
    {
        registry.free_ids[registry.free_count++] = g->id;
        registry.count--;
        g->state = GAME_EMPTY;
        return;
    }

    g->owner_sock = remaining_sock;
    g->player2_sock = -1;
    g->state = GAME_WAITING;
    board_init(&g->board);
    g->turn = 0;
    g->winner = 0;
}

int game_registry_list_waiting(int except_sock, Game *out)
{
    int n = 0;

    for (int i = 0; i < registry.next_id - 1 && n < MAX_GAMES_IN_LIST; i++)
    {
        if (registry.games[i].state == GAME_WAITING && registry.games[i].owner_sock != except_sock)
        {
            out[n++] = registry.games[i];
        }
    }
    return n;
}

int game_registry_list_my_games(int sock, Game *out, int max)
{
    int n = 0;

    for (int i = 0; i < registry.next_id - 1 && n < max; i++)
    {
        if (registry.games[i].state != GAME_EMPTY && is_player(&registry.games[i], sock))
        {
            out[n++] = registry.games[i];
        }
    }
    return n;
}

int game_opponent(const Game *g, int sock)
{
    return (g->owner_sock == sock) ? g->player2_sock : g->owner_sock;
}

ErrorCode game_registry_set_pending(int game_id, int joiner_sock, Game *out_game)
{
    Game *g = find_game(game_id);
    if (g == NULL)
    {
        return ERR_NOT_FOUND;
    }
    if (g->owner_sock == joiner_sock)
    {
        return ERR_SELF_JOIN;
    }
    if (g->state != GAME_WAITING)
    {
        return ERR_NOT_WAITING;
    }
    if (g->pending_joiner_sock != -1)
    {
        return ERR_ALREADY_PENDING;
    }
    if (count_games(joiner_sock) >= MAX_GAMES_PER_PLAYER)
    {
        return ERR_TOO_MANY_GAMES;
    }

    g->pending_joiner_sock = joiner_sock;
    *out_game = *g;
    return ERR_NONE;
}

ErrorCode game_registry_resolve_join(int game_id, int owner_sock, int accepted, Game *out_game)
{
    Game *g = find_game(game_id);
    if (g == NULL)
    {
        return ERR_NOT_FOUND;
    }
    if (g->owner_sock != owner_sock)
    {
        return ERR_NOT_OWNER;
    }
    if (g->pending_joiner_sock == -1)
    {
        return ERR_NO_PENDING;
    }

    int joiner_sock = g->pending_joiner_sock;
    ErrorCode err = ERR_NONE;
    g->pending_joiner_sock = -1;

    if (accepted && count_games(joiner_sock) >= MAX_GAMES_PER_PLAYER)
    {
        // set_pending only checked the joiner when the request was made, and
        // a client can have requests pending in several games: others may
        // have been accepted since. The request is cancelled - the game stays
        // WAITING for someone else.
        err = ERR_JOINER_FULL;
    }
    else if (accepted)
    {
        g->player2_sock = joiner_sock;
        start_round(g);
    }

    *out_game = *g;
    out_game->pending_joiner_sock = joiner_sock; // who the answer is for
    return err;
}

ErrorCode game_registry_apply_move(int game_id, int player_sock, int column, int is_active, Game *out_game)
{
    Game *g = find_game(game_id);
    if (g == NULL)
    {
        return ERR_NOT_FOUND;
    }

    int player = (g->owner_sock == player_sock) ? 1 : (g->player2_sock == player_sock) ? 2 : 0;
    if (player == 0)
    {
        return ERR_NOT_PLAYER;
    }
    if (g->state != GAME_PLAYING)
    {
        return ERR_NOT_PLAYING;
    }
    if (!is_active)
    {
        return ERR_NOT_ACTIVE;
    }
    if (g->turn != player)
    {
        return ERR_NOT_YOUR_TURN;
    }

    int row;
    DropResult dr = board_drop_disc(&g->board, column, (player == 1) ? PLAYER_1 : PLAYER_2, &row);
    if (dr == DROP_INVALID_COLUMN)
    {
        return ERR_INVALID_COLUMN;
    }
    if (dr == DROP_COLUMN_FULL)
    {
        return ERR_COLUMN_FULL;
    }

    int won = board_check_win(&g->board, row, column);
    if (won || board_is_full(&g->board))
    {
        g->state = GAME_FINISHED;
        g->turn = 0;
        g->winner = won ? player : 0; // 0: a draw
    }
    else
    {
        g->turn = (player == 1) ? 2 : 1;
    }

    *out_game = *g;
    return ERR_NONE;
}

ErrorCode game_registry_rematch(int game_id, int sock, Game *out_game)
{
    Game *g = find_game(game_id);
    if (g == NULL)
    {
        return ERR_NOT_FOUND;
    }
    if (!is_player(g, sock))
    {
        return ERR_NOT_PLAYER;
    }
    if (g->state != GAME_FINISHED)
    {
        return ERR_NOT_FINISHED;
    }

    int is_owner = (g->owner_sock == sock);
    int *my_vote = is_owner ? &g->owner_wants_rematch : &g->player2_wants_rematch;
    int opponent_voted = is_owner ? g->player2_wants_rematch : g->owner_wants_rematch;
    if (*my_vote)
    {
        return ERR_ALREADY_PENDING;
    }

    if (opponent_voted)
    {
        start_round(g);
    }
    else
    {
        *my_vote = 1;
    }

    *out_game = *g;
    return ERR_NONE;
}

RoomState game_registry_state(int game_id)
{
    Game *g = find_game(game_id);
    return g ? g->state : GAME_EMPTY;
}

ErrorCode game_registry_check_activate(int game_id, int sock)
{
    Game *g = find_game(game_id);
    if (g == NULL)
    {
        return ERR_NOT_FOUND;
    }
    if (!is_player(g, sock))
    {
        return ERR_NOT_PLAYER;
    }
    if (g->state == GAME_WAITING)
    {
        return ERR_NOT_PLAYING;
    }
    return ERR_NONE;
}

// The game with id 'game_id', or NULL. 'game_id' comes straight from the
// client, so it is bounds-checked first.
static Game *find_game(int game_id)
{
    if (game_id < 1 || game_id > MAX_GAMES || registry.games[game_id - 1].state == GAME_EMPTY)
    {
        return NULL;
    }
    return &registry.games[game_id - 1];
}

// 'sock' is the owner or the second player of 'g' (not just a pending joiner).
static int is_player(const Game *g, int sock)
{
    return g->owner_sock == sock || g->player2_sock == sock;
}

// How many games 'sock' is a player of, in any state: the rooms that still
// wait for an opponent count too (MAX_GAMES_PER_PLAYER).
static int count_games(int sock)
{
    int n = 0;

    for (int i = 0; i < registry.next_id - 1; i++)
    {
        if (registry.games[i].state != GAME_EMPTY && is_player(&registry.games[i], sock))
        {
            n++;
        }
    }
    return n;
}

// A game starts, after an accepted join or both votes for a rematch: empty
// board, player 1 (the owner) moves first, and the votes of an earlier game
// in this room no longer count.
static void start_round(Game *g)
{
    g->state = GAME_PLAYING;
    board_init(&g->board);
    g->turn = 1;
    g->winner = 0;
    g->owner_wants_rematch = 0;
    g->player2_wants_rematch = 0;
}
