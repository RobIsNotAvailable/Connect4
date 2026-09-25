"""A small Connect 4 model, written independently of the server.

The tests use it as an oracle: it says what the board looks like after a
sequence of moves and whether one of them won, so a test can compare that
with what the server sent instead of trusting hand-written board strings.
It also proves that a move sequence written into a test really is a win (or
a draw), so a typo in a test cannot pass by accident.
"""

ROWS, COLS = 6, 7


def replay(columns):
    """Plays 'columns' one after the other, player 1 first. Returns a list with
    one (board, winner) pair per move: 'board' is the 42-character string of
    GAME_STATE after that move, 'winner' is 1, 2 or None."""
    grid = [["."] * COLS for _ in range(ROWS)]
    out = []
    for i, col in enumerate(columns):
        disc = "1" if i % 2 == 0 else "2"
        row = next(r for r in range(ROWS - 1, -1, -1) if grid[r][col] == ".")
        grid[row][col] = disc
        winner = int(disc) if _makes_four(grid, row, col) else None
        out.append(("".join("".join(r) for r in grid), winner))
    return out


def _makes_four(grid, row, col):
    disc = grid[row][col]
    for dr, dc in ((0, 1), (1, 0), (1, 1), (1, -1)):
        n = 1
        for sign in (1, -1):
            r, c = row + dr * sign, col + dc * sign
            while 0 <= r < ROWS and 0 <= c < COLS and grid[r][c] == disc:
                n += 1
                r += dr * sign
                c += dc * sign
        if n >= 4:
            return True
    return False
