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

    // The four lines through (row, col): horizontal, vertical and the two
    // diagonals. Each one is "the cell itself" (1) plus how far the same
    // player's discs extend from it in both directions along the line.
    static const int directions[4][2] = { {0, 1}, {1, 0}, {1, 1}, {1, -1} };

    for (int i = 0; i < 4; i++)
    {
        int drow = directions[i][0];
        int dcol = directions[i][1];
        int line = 1 + count_direction(board, row, col, cellPlayer, drow, dcol)
                     + count_direction(board, row, col, cellPlayer, -drow, -dcol);
        if (line >= 4)
        {
            return 1;
        }
    }
    return 0;
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

void board_to_string(const Board *board, char *out)
{
    int i = 0;
    for (int row = 0; row < BOARD_ROWS; row++)
    {
        for (int col = 0; col < BOARD_COLS; col++)
        {
            CellPlayer cellPlayer = board->cells[row][col];
            out[i++] = (cellPlayer == PLAYER_NONE) ? '.' : (char)('0' + cellPlayer);
        }
    }
    out[i] = '\0';
}

int board_is_full(const Board *board)
{
    // A disc always falls to the lowest empty cell, so a column is full
    // exactly when its top cell is taken: the board is full when its top row is.
    for (int col = 0; col < BOARD_COLS; col++)
    {
        if (board->cells[0][col] == PLAYER_NONE)
        {
            return 0;
        }
    }
    return 1;
}
