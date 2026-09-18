#include <string.h>
#include "board.h"

void board_init(Board *board)
{
    // Every byte 0 == every cell PLAYER_NONE (see board.h).
    memset(board, 0, sizeof(*board));
}

DropResult board_drop_disc(Board *board, int column, CellPlayer cellPlayer, int *out_row)
{
    if (column < 0 || column >= BOARD_COLS)
    {
        return DROP_INVALID_COLUMN;
    }

    for (int row = BOARD_ROWS - 1; row >= 0; row--)
    {
        if (board->cells[row][column] == PLAYER_NONE)
        {
            board->cells[row][column] = cellPlayer;
            *out_row = row;
            return DROP_OK;
        }
    }

    return DROP_COLUMN_FULL;
}

// Private helpers for board_check_win below, forward-declared so it can
// read top-down: in_bounds, then count_direction which uses it.
static int in_bounds(int row, int col);
static int count_direction(const Board *board, int row, int col, CellPlayer cellPlayer, int drow, int dcol);

int board_check_win(const Board *board, int row, int col)
{
    CellPlayer cellPlayer = board->cells[row][col];

    // Each of the four lines through (row, col) is "the cell itself" (1)
    // plus how far the same player's discs extend in both opposite
    // directions along that line. Checked one axis at a time, returning
    // as soon as one reaches 4 instead of always computing all four.
    int horizontal = 1 + count_direction(board, row, col, cellPlayer, 0, 1)
                       + count_direction(board, row, col, cellPlayer, 0, -1);

    if (horizontal >= 4) return 1;
    

    int vertical = 1 + count_direction(board, row, col, cellPlayer, 1, 0)
                     + count_direction(board, row, col, cellPlayer, -1, 0);

    if (vertical >= 4) return 1;
    

    int diag_down = 1 + count_direction(board, row, col, cellPlayer, 1, 1)
                      + count_direction(board, row, col, cellPlayer, -1, -1);

    if (diag_down >= 4)return 1;
    

    int diag_up = 1 + count_direction(board, row, col, cellPlayer, 1, -1)
                    + count_direction(board, row, col, cellPlayer, -1, 1);

    return diag_up >= 4;
}

static int in_bounds(int row, int col)
{
    return row >= 0 && row < BOARD_ROWS && col >= 0 && col < BOARD_COLS;
}

// Starting one step away from (row, col) and stepping by (drow, dcol)
// each time, counts how many cells in a row belong to 'cellPlayer',
// stopping at the board edge or at the first cell that doesn't match.
static int count_direction(const Board *board, int row, int col, CellPlayer cellPlayer, int drow, int dcol)
{
    int count = 0;
    row += drow;
    col += dcol;

    while (in_bounds(row, col) && board->cells[row][col] == cellPlayer)
    {
        count++;
        row += drow;
        col += dcol;
    }

    return count;
}
