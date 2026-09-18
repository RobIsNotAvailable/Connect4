#ifndef BOARD_H
#define BOARD_H

// Board dimensions (docs/protocol.md §5.2): 6 rows, 7 columns.
#define BOARD_ROWS 6
#define BOARD_COLS 7

// Who (if anyone) occupies a cell - and, doubling up, who is making a
// move in board_drop_disc() below. PLAYER_1/PLAYER_2 match the player
// numbers used everywhere else in the protocol (GAME_START's
// <your_player>, GAME_STATE's <turn>): both are already 1/2, so once a
// cell isn't empty, its wire character is just '0' + the cell's value
// (see board_to_string, added with the string format). Must keep
// PLAYER_NONE == 0: board_init relies on a zeroed struct meaning "all
// empty", same trick as GAME_EMPTY in protocol.h.
typedef enum
{
    PLAYER_NONE = 0,
    PLAYER_1 = 1,
    PLAYER_2 = 2
} CellPlayer;

// A Connect4 grid. Pure game state - no socket, no thread-safety: one
// board belongs to one game, mutated only by whichever thread is
// handling that game's moves (docs/protocol.md - a client plays at most
// one game at a time), so nothing here needs a lock.
typedef struct
{
    CellPlayer cells[BOARD_ROWS][BOARD_COLS];
} Board;

// Outcomes of board_drop_disc().
typedef enum
{
    DROP_OK,
    DROP_INVALID_COLUMN, // column outside 0..BOARD_COLS-1
    DROP_COLUMN_FULL
} DropResult;

// Resets every cell of 'board' to PLAYER_NONE.
void board_init(Board *board);

// Drops a disc for 'player' into 'column' (0-indexed): it falls to the
// lowest empty row, like gravity. On DROP_OK, fills 'out_row' with the
// row it landed on (row 0 is the top, per docs/protocol.md §5.2) - the
// caller will need it to check for a win starting from that cell.
// Rejects an out-of-range column or a full one without touching the
// board.
DropResult board_drop_disc(Board *board, int column, CellPlayer player, int *out_row);

// True if the disc just placed at (row, col) completes a four-in-a-row
// for whichever player occupies that cell, checking all four directions
// (horizontal, vertical, both diagonals) through it. (row, col) must be
// the cell a disc was just dropped into (i.e. not PLAYER_NONE) - typically
// board_drop_disc's own out_row, together with the column just played.
int board_check_win(const Board *board, int row, int col);

#endif
