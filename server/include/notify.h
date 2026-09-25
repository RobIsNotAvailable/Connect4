#ifndef NOTIFY_H
#define NOTIFY_H

#include "game_registry.h"

// What the server tells the players on its own: a game starting, its board,
// its end, what happens when someone leaves, and whether the opponent is
// looking at the game (its active game, SET_ACTIVE_GAME). Called with
// the server's command_mutex held, like everything that touches the registries.

// GAME_START to both players of 'g' (their number and the opponent's name),
// then the first GAME_STATE, after an accepted join or a rematch. The game
// becomes the active one of a player who has nothing else to play.
void send_game_start(const Game *g);

// GAME_STATE (the board and whose turn it is) to both players of 'g'.
void send_game_state(const Game *g);

// GAME_OVER to both players of the FINISHED game 'g': WIN and LOSE, or DRAW.
void send_game_over(const Game *g);

// The notifications for what the registry did because 'leaver_sock' left
// some games, by LEAVE_GAME or by disconnecting.
void notify_leave_events(int leaver_sock, const LeaveEvent *events, int n);

// Changes the active game of 'sock' (0 = none) and tells the opponents who
// see a difference (OPPONENT_STATUS).
void set_active_game(int sock, int game_id);

// 1 if the active game of 'sock' is not 'game_id': the player is in the lobby
// or in another game, so whoever plays 'game_id' against it sees it AWAY.
int is_away(int sock, int game_id);

#endif
