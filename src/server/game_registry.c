#include <pthread.h>
#include <string.h>
#include "game_registry.h"

// The public functions below are already declared in game_registry.h.
// These are the private helpers defined in this file, forward-declared
// here so each can be defined after its first caller: leave_game_slot and
// free_slot after game_registry_handle_disconnect, game_id_is_valid and
// is_playing after game_registry_set_pending, count_owned_games after
// game_create.
static void leave_game_slot(int index, int sock, LeaveEvent *event);
static void free_slot(int index);
static int game_id_is_valid(int game_id);
static int is_playing(int sock);
static int count_owned_games(int sock);

// Thread-safe registry of games, mirroring ClientList (see
// client_registry.c): a slot map indexed directly by id (games[id - 1] IS
// the game with that id when its state isn't GAME_EMPTY), with the same
// free-id stack scheme so ids get reused instead of growing forever, and
// lookups by id are O(1) instead of a scan.
typedef struct
{
    Game games[MAX_GAMES];      // slot map: games[id - 1] is the game with that id, or GAME_EMPTY if free
    int count;                  // how many slots are currently occupied
    int next_id;                // bump allocator: next never-before-used id, used when free_ids is empty
    int free_ids[MAX_GAMES];    // stack of freed ids waiting to be reused
    int free_count;             // how many entries in free_ids are currently valid
    pthread_mutex_t mutex;      // guards every field above
} GameRegistry;

static GameRegistry registry = {
    .count = 0,
    .next_id = 1,
    .free_count = 0,
    .mutex = PTHREAD_MUTEX_INITIALIZER
};

CreateResult game_create(int owner_sock, const char *name, Game *out_game)
{
    Game new_game = {
        .id = -1,
        .owner_sock = owner_sock,
        .state = GAME_WAITING,
        .pending_joiner_sock = -1,
        .player2_sock = -1,
        .turn = 0,
        .winner = 0
    };
    strncpy(new_game.name, name, ROOM_NAME_LEN);
    board_init(&new_game.board);

    CreateResult result;

    pthread_mutex_lock(&registry.mutex);

    if (count_owned_games(owner_sock) >= MAX_GAMES_PER_OWNER)
    {
        result = CREATE_ERR_TOO_MANY_GAMES;
    }
    else if (registry.count >= MAX_GAMES)
    {
        result = CREATE_ERR_SERVER_FULL;
    }
    else
    {
        int id = (registry.free_count > 0)
            ? registry.free_ids[--registry.free_count]
            : registry.next_id++;

        new_game.id = id;
        registry.games[id - 1] = new_game; // direct slot write, no scan
        registry.count++;
        *out_game = new_game;
        result = CREATE_OK;
    }

    pthread_mutex_unlock(&registry.mutex);

    return result;
}

// Returns how many occupied slots have 'sock' as owner, whatever their
// state. Caller must hold registry.mutex. Same bounded full scan as
// is_playing below; only runs on CREATE_GAME, which is rare.
static int count_owned_games(int sock)
{
    int n = 0;

    for (int i = 0; i < registry.next_id - 1; i++)
    {
        if (registry.games[i].state != GAME_EMPTY && registry.games[i].owner_sock == sock)
        {
            n++;
        }
    }
    return n;
}

// A single client can be involved (as owner, pending joiner, or player2)
// in several games at once - it can own up to MAX_GAMES_PER_OWNER of them,
// and nothing stops holding more than one pending join request
// either - so this has to check every occupied slot; unlike a single-game
// lookup by id it isn't a single-slot operation. Bounded by the highest
// id ever handed out rather than MAX_GAMES, and only runs once per
// disconnect (not per request), so the scan is cheap in practice.
int game_registry_handle_disconnect(int sock, LeaveEvent *events)
{
    int n = 0;

    pthread_mutex_lock(&registry.mutex);

    for (int i = 0; i < registry.next_id - 1; i++)
    {
        if (registry.games[i].state == GAME_EMPTY)
        {
            continue;
        }

        if (registry.games[i].pending_joiner_sock == sock)
        {
            // Docs/protocol.md §8: the request is cancelled, the game
            // itself stays WAITING and can receive new requests.
            events[n].type = LEAVE_JOIN_CANCELLED;
            events[n].game_id = registry.games[i].id;
            events[n].notify_sock = registry.games[i].owner_sock;
            n++;
            registry.games[i].pending_joiner_sock = -1;
        }
        else if (registry.games[i].owner_sock == sock || registry.games[i].player2_sock == sock)
        {
            leave_game_slot(i, sock, &events[n]);
            n++;
        }
    }

    pthread_mutex_unlock(&registry.mutex);

    return n;
}

LeaveResult game_registry_leave(int game_id, int sock, LeaveEvent *event)
{
    LeaveResult result = LEAVE_ERR_NOT_FOUND;

    pthread_mutex_lock(&registry.mutex);

    if (game_id_is_valid(game_id))
    {
        int i = game_id - 1;

        if (registry.games[i].owner_sock != sock && registry.games[i].player2_sock != sock)
        {
            result = LEAVE_ERR_NOT_PLAYER;
        }
        else
        {
            leave_game_slot(i, sock, event);
            result = LEAVE_OK;
        }
    }

    pthread_mutex_unlock(&registry.mutex);

    return result;
}

// 'sock' leaves the game at slot 'index' (0-based) - the rules of
// docs/protocol.md §8, whatever the game's state. 'sock' must be that
// game's owner or player2. Fills 'event' with what the caller has to
// notify. Caller must hold registry.mutex.
//
// Whoever is left in the game keeps it. If that is the owner, the game goes
// back to WAITING with a fresh board. If the owner is the one who left, the
// other player takes over as owner - unless they already own
// MAX_GAMES_PER_OWNER other games, in which case the game is removed rather
// than break that limit. With nobody left, the game is removed too.
static void leave_game_slot(int index, int sock, LeaveEvent *event)
{
    Game *g = &registry.games[index];
    int owner_left = (g->owner_sock == sock);
    int remaining_sock = owner_left ? g->player2_sock : g->owner_sock; // -1 if nobody is left

    event->game_id = g->id;
    strncpy(event->name, g->name, ROOM_NAME_LEN);
    event->notify_sock = -1;

    if (remaining_sock == -1 || (owner_left && count_owned_games(remaining_sock) >= MAX_GAMES_PER_OWNER))
    {
        event->type = LEAVE_ROOM_CLOSED;
        free_slot(index);
        return;
    }

    g->owner_sock = remaining_sock;
    g->player2_sock = -1;
    g->state = GAME_WAITING;
    board_init(&g->board);
    g->turn = 0;
    g->winner = 0;

    event->type = LEAVE_ROOM_REOPENED;
    event->notify_sock = remaining_sock;
}

// Frees the slot at 'index' (0-based): returns its id to the free-id
// stack for reuse and marks the slot empty, so future occupancy checks
// (game_id_is_valid, the loops above/below) see it as free. Caller must
// hold registry.mutex and already know the slot is occupied. Resetting
// 'id' to 0 isn't required for correctness anymore (state is now the
// only occupancy signal that's ever checked), kept only so a freed slot
// doesn't show a stale game number if the registry is ever inspected
// while debugging.
static void free_slot(int index)
{
    registry.free_ids[registry.free_count++] = registry.games[index].id;
    registry.games[index].id = 0;
    registry.games[index].state = GAME_EMPTY;
    registry.count--;
}

// Listing "all games in state X" is inherently a full pass (there's no
// single slot to jump to for that), so this is the one place the registry
// still walks its range - bounded by the highest id ever handed out and
// skipping free slots.
int game_registry_list_waiting(GameInfo *out)
{
    int n = 0;

    pthread_mutex_lock(&registry.mutex);

    for (int i = 0; i < registry.next_id - 1 && n < MAX_GAMES_IN_LIST; i++)
    {
        if (registry.games[i].state == GAME_WAITING)
        {
            out[n].game_id = registry.games[i].id;
            strncpy(out[n].name, registry.games[i].name, ROOM_NAME_LEN);
            out[n].owner_sock = registry.games[i].owner_sock;
            out[n].state = registry.games[i].state;
            n++;
        }
    }

    pthread_mutex_unlock(&registry.mutex);

    return n;
}

// Same bounded scan as game_registry_list_waiting, filtered by owner
// instead of state. An owner has at most MAX_GAMES_PER_OWNER games (game_create
// refuses more, and an ownership transfer closes the room rather than
// exceed it), so 'out' can never overflow; the loop condition enforces it
// anyway.
int game_registry_list_owned(int owner_sock, GameInfo *out)
{
    int n = 0;

    pthread_mutex_lock(&registry.mutex);

    for (int i = 0; i < registry.next_id - 1 && n < MAX_GAMES_PER_OWNER; i++)
    {
        if (registry.games[i].state != GAME_EMPTY && registry.games[i].owner_sock == owner_sock)
        {
            out[n].game_id = registry.games[i].id;
            strncpy(out[n].name, registry.games[i].name, ROOM_NAME_LEN);
            out[n].owner_sock = registry.games[i].owner_sock;
            out[n].state = registry.games[i].state;
            n++;
        }
    }

    pthread_mutex_unlock(&registry.mutex);

    return n;
}

JoinSetResult game_registry_set_pending(int game_id, int joiner_sock, Game *out_game)
{
    JoinSetResult result = JOIN_ERR_NOT_FOUND;

    pthread_mutex_lock(&registry.mutex);

    if (game_id_is_valid(game_id))
    {
        int i = game_id - 1;

        if (registry.games[i].owner_sock == joiner_sock)
        {
            result = JOIN_ERR_SELF_JOIN;
        }
        else if (registry.games[i].state != GAME_WAITING)
        {
            result = JOIN_ERR_NOT_WAITING;
        }
        else if (registry.games[i].pending_joiner_sock != -1)
        {
            result = JOIN_ERR_ALREADY_PENDING;
        }
        else if (is_playing(joiner_sock))
        {
            result = JOIN_ERR_ALREADY_PLAYING;
        }
        else
        {
            registry.games[i].pending_joiner_sock = joiner_sock;
            result = JOIN_OK;
        }

        *out_game = registry.games[i];
    }

    pthread_mutex_unlock(&registry.mutex);

    return result;
}

// Returns 1 if game_id refers to a currently occupied slot, 0 otherwise.
// game_id comes straight from the client, so it's bounds-checked first;
// the slot's state then says whether it actually holds a game (not
// stale/free). Caller must hold registry.mutex - used by
// game_registry_set_pending above and game_registry_resolve_join below,
// to avoid repeating this check inline.
static int game_id_is_valid(int game_id)
{
    return game_id >= 1 && game_id <= MAX_GAMES && registry.games[game_id - 1].state != GAME_EMPTY;
}

// Returns 1 if 'sock' is currently owner or player2 of any PLAYING game
// (docs/protocol.md §5.1: a client plays at most one game at a time).
// Caller must hold registry.mutex. Bounded by the highest id ever
// handed out, like the other full-registry scans in this file.
static int is_playing(int sock)
{
    for (int i = 0; i < registry.next_id - 1; i++)
    {
        if (registry.games[i].state == GAME_PLAYING &&
            (registry.games[i].owner_sock == sock || registry.games[i].player2_sock == sock))
        {
            return 1;
        }
    }
    return 0;
}

ResolveResult game_registry_resolve_join(int game_id, int owner_sock, int accepted, Game *out_game)
{
    ResolveResult result = RESOLVE_ERR_NOT_FOUND;

    pthread_mutex_lock(&registry.mutex);

    if (game_id_is_valid(game_id))
    {
        int i = game_id - 1;
        int joiner_sock = registry.games[i].pending_joiner_sock;

        if (registry.games[i].owner_sock != owner_sock)
        {
            result = RESOLVE_ERR_NOT_OWNER;
        }
        else if (joiner_sock == -1)
        {
            result = RESOLVE_ERR_NO_PENDING;
        }
        else if (accepted && is_playing(owner_sock))
        {
            result = RESOLVE_ERR_ALREADY_PLAYING;
        }
        else if (accepted && is_playing(joiner_sock))
        {
            // set_pending only checked the joiner when the request was made,
            // and a client can have requests pending in several games:
            // another owner may have accepted it since. Playing two matches
            // at once isn't allowed (docs/protocol.md §5.1), so this one is
            // cancelled - the game stays WAITING for someone else.
            registry.games[i].pending_joiner_sock = -1;
            result = RESOLVE_ERR_JOINER_BUSY;
        }
        else
        {
            if (accepted)
            {
                registry.games[i].state = GAME_PLAYING;
                registry.games[i].player2_sock = joiner_sock;
                registry.games[i].turn = 1; // player 1 (the owner) moves first
                // A new round: votes left over from a previous game in this room don't count.
                registry.games[i].owner_wants_rematch = 0;
                registry.games[i].player2_wants_rematch = 0;
            }
            registry.games[i].pending_joiner_sock = -1;
            result = RESOLVE_OK;
        }

        *out_game = registry.games[i];
        if (result == RESOLVE_OK || result == RESOLVE_ERR_JOINER_BUSY)
        {
            // pending_joiner_sock was just cleared above, but the caller
            // still needs to know who to notify with JOIN_RESULT.
            out_game->pending_joiner_sock = joiner_sock;
        }
    }

    pthread_mutex_unlock(&registry.mutex);

    return result;
}

MoveResult game_registry_apply_move(int game_id, int player_sock, int column, Game *out_game)
{
    MoveResult result = MOVE_ERR_NOT_FOUND;

    pthread_mutex_lock(&registry.mutex);

    if (game_id_is_valid(game_id))
    {
        int i = game_id - 1;
        int player = 0;

        if (registry.games[i].owner_sock == player_sock)
        {
            player = 1;
        }
        else if (registry.games[i].player2_sock == player_sock)
        {
            player = 2;
        }

        if (player == 0)
        {
            result = MOVE_ERR_NOT_PLAYER;
        }
        else if (registry.games[i].state != GAME_PLAYING)
        {
            result = MOVE_ERR_NOT_PLAYING;
        }
        else if (registry.games[i].turn != player)
        {
            result = MOVE_ERR_NOT_YOUR_TURN;
        }
        else
        {
            int row;
            CellPlayer disc = (player == 1) ? PLAYER_1 : PLAYER_2;
            DropResult dr = board_drop_disc(&registry.games[i].board, column, disc, &row);

            if (dr == DROP_INVALID_COLUMN)
            {
                result = MOVE_ERR_INVALID_COLUMN;
            }
            else if (dr == DROP_COLUMN_FULL)
            {
                result = MOVE_ERR_COLUMN_FULL;
            }
            else if (board_check_win(&registry.games[i].board, row, column))
            {
                registry.games[i].state = GAME_FINISHED;
                registry.games[i].turn = 0;
                registry.games[i].winner = player;
                result = MOVE_OK;
            }
            else if (board_is_full(&registry.games[i].board))
            {
                registry.games[i].state = GAME_FINISHED;
                registry.games[i].turn = 0;
                registry.games[i].winner = 0; // draw
                result = MOVE_OK;
            }
            else
            {
                registry.games[i].turn = (player == 1) ? 2 : 1;
                result = MOVE_OK;
            }
        }

        *out_game = registry.games[i];
    }

    pthread_mutex_unlock(&registry.mutex);

    return result;
}

RematchResult game_registry_rematch(int game_id, int sock, Game *out_game)
{
    RematchResult result = REMATCH_ERR_NOT_FOUND;

    pthread_mutex_lock(&registry.mutex);

    if (game_id_is_valid(game_id))
    {
        Game *g = &registry.games[game_id - 1];
        int is_owner = (g->owner_sock == sock);
        int *my_vote = is_owner ? &g->owner_wants_rematch : &g->player2_wants_rematch;
        int *opponent_vote = is_owner ? &g->player2_wants_rematch : &g->owner_wants_rematch;
        int opponent_sock = is_owner ? g->player2_sock : g->owner_sock;

        if (!is_owner && g->player2_sock != sock)
        {
            result = REMATCH_ERR_NOT_PLAYER;
        }
        else if (g->state != GAME_FINISHED)
        {
            result = REMATCH_ERR_NOT_FINISHED;
        }
        else if (*my_vote)
        {
            result = REMATCH_ERR_ALREADY_VOTED;
        }
        else if (is_playing(sock))
        {
            // This game is FINISHED, so it doesn't count: this is another one.
            result = REMATCH_ERR_ALREADY_PLAYING;
        }
        else if (!*opponent_vote)
        {
            *my_vote = 1;
            result = REMATCH_WAITING;
        }
        else if (is_playing(opponent_sock))
        {
            // The opponent voted yes and then started another game (nothing
            // stops a client leaving the finished game's pop-up behind). Same
            // reasoning as RESOLVE_ERR_JOINER_BUSY: no two matches at once.
            *opponent_vote = 0;
            result = REMATCH_ERR_OPPONENT_BUSY;
        }
        else
        {
            g->state = GAME_PLAYING;
            board_init(&g->board);
            g->turn = 1; // player 1 (the owner) moves first
            g->winner = 0;
            g->owner_wants_rematch = 0;
            g->player2_wants_rematch = 0;
            result = REMATCH_STARTED;
        }

        *out_game = *g;
    }

    pthread_mutex_unlock(&registry.mutex);

    return result;
}
