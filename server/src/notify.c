#include "client_registry.h"
#include "notify.h"

static void activate_if_idle(int sock, int game_id);
static void announce_presence(int sock, int old_active, int new_active, int skip_game);
static void clear_active_game(int sock, int game_id);

void send_game_start(const Game *g)
{
    // Before anything is sent: the player can answer GAME_START with a MOVE at
    // once, and it must find the game active.
    int owner_was_active = client_list_get_active_game(g->owner_sock);
    int player2_was_active = client_list_get_active_game(g->player2_sock);
    activate_if_idle(g->owner_sock, g->id);
    activate_if_idle(g->player2_sock, g->id);

    client_send_line(g->owner_sock, "GAME_START %d 1 %s", g->id, client_list_username(g->player2_sock));
    client_send_line(g->player2_sock, "GAME_START %d 2 %s", g->id, client_list_username(g->owner_sock));
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

void send_game_state(const Game *g)
{
    char board[BOARD_ROWS * BOARD_COLS + 1];
    board_to_string(&g->board, board);
    client_send_line(g->owner_sock, "GAME_STATE %d %d %s", g->id, g->turn, board);
    client_send_line(g->player2_sock, "GAME_STATE %d %d %s", g->id, g->turn, board);
}

void send_game_over(const Game *g)
{
    if (g->winner == 0)
    {
        client_send_line(g->owner_sock, "GAME_OVER %d DRAW", g->id);
        client_send_line(g->player2_sock, "GAME_OVER %d DRAW", g->id);
        return;
    }

    int winner_sock = (g->winner == 1) ? g->owner_sock : g->player2_sock;
    client_send_line(winner_sock, "GAME_OVER %d WIN", g->id);
    client_send_line(game_opponent(g, winner_sock), "GAME_OVER %d LOSE", g->id);
}

void notify_leave_events(int leaver_sock, const LeaveEvent *events, int n)
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
                client_send_line(notify_sock, "OPPONENT_LEFT %d", game_id);
                client_broadcast_except(notify_sock, -1, "NEW_GAME %d %s %s",
                                        game_id, events[i].name, client_list_username(notify_sock));
                break;
        }
    }
}

void set_active_game(int sock, int game_id)
{
    int old_active = client_list_get_active_game(sock);

    if (old_active != game_id)
    {
        client_list_set_active_game(sock, game_id);
        announce_presence(sock, old_active, game_id, 0);
    }
}

int is_away(int sock, int game_id)
{
    return client_list_get_active_game(sock) != game_id;
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

// The active game of 'sock' went from 'old_active' to 'new_active': tells the
// opponent in each of its games whose status changed because of it
// (OPPONENT_STATUS). Nothing is sent to the ones that see no difference. The
// game 'skip_game' is left out: a game that has just started is handled by
// send_game_start.
static void announce_presence(int sock, int old_active, int new_active, int skip_game)
{
    Game games[MAX_GAMES_PER_PLAYER];
    int n = game_registry_list_my_games(sock, games, MAX_GAMES_PER_PLAYER);

    for (int i = 0; i < n; i++)
    {
        int id = games[i].id;
        int opponent_sock = game_opponent(&games[i], sock);
        if (id == skip_game || opponent_sock == -1)
        {
            continue; // a room that waits has nobody to tell
        }

        int was_away = (old_active != id);
        int now_away = (new_active != id);
        if (was_away != now_away)
        {
            client_send_line(opponent_sock, "OPPONENT_STATUS %d %s", id, now_away ? "AWAY" : "HERE");
        }
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
