#include <pthread.h>
#include <string.h>
#include "game_registry.h"

#define MAX_GAMES 256

// The public functions below are already declared in game_registry.h.
// These are the private helpers defined in this file, forward-declared
// here so each can be defined after its first caller: free_slot after
// game_registry_remove_by_owner, game_id_is_valid after
// game_registry_set_pending.
static void free_slot(int index);
static int game_id_is_valid(int game_id);

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

Game game_create(int owner_sock, const char *owner_username)
{
    Game new_game = {
        .id = -1,
        .owner_sock = owner_sock,
        .state = GAME_WAITING,
        .pending_joiner_sock = -1,
        .player2_sock = -1
    };
    strncpy(new_game.owner_username, owner_username, USERNAME_LEN);

    pthread_mutex_lock(&registry.mutex);

    if (registry.count < MAX_GAMES)
    {
        int id = (registry.free_count > 0)
            ? registry.free_ids[--registry.free_count]
            : registry.next_id++;

        new_game.id = id;
        registry.games[id - 1] = new_game; // direct slot write, no scan
        registry.count++;
    }

    pthread_mutex_unlock(&registry.mutex);

    return new_game;
}

// A single owner can hold several games (create doesn't cap that - see its
// comment), so this still has to check each one; unlike a single-game
// lookup by id it isn't a single-slot operation. It's bounded by the
// highest id ever handed out rather than MAX_GAMES, and only runs once per
// disconnect (not per request), so the scan is cheap in practice.
void game_registry_remove_by_owner(int owner_sock)
{
    pthread_mutex_lock(&registry.mutex);

    for (int i = 0; i < registry.next_id - 1; i++)
    {
        if (registry.games[i].state != GAME_EMPTY && registry.games[i].owner_sock == owner_sock)
        {
            free_slot(i);
        }
    }

    pthread_mutex_unlock(&registry.mutex);
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
            strncpy(out[n].owner_username, registry.games[i].owner_username, USERNAME_LEN);
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
        else
        {
            if (accepted)
            {
                registry.games[i].state = GAME_PLAYING;
                registry.games[i].player2_sock = joiner_sock;
            }
            registry.games[i].pending_joiner_sock = -1;
            result = RESOLVE_OK;
        }

        *out_game = registry.games[i];
        if (result == RESOLVE_OK)
        {
            // pending_joiner_sock was just cleared above, but the caller
            // still needs to know who to notify with JOIN_RESULT.
            out_game->pending_joiner_sock = joiner_sock;
        }
    }

    pthread_mutex_unlock(&registry.mutex);

    return result;
}
