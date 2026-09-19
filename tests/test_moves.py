"""MOVE, GAME_STATE and GAME_OVER (docs/protocol.md §5): turns, errors, every
way of winning, the draw.

The boards the server should send are never written by hand: c4model.replay()
computes them, and play() refuses a move sequence that does not end the way the
test says it does (so a typo in a sequence cannot make a test pass by accident).
"""
from c4model import replay
from harness import EMPTY_BOARD as EMPTY, Server, check, finish, start_game

# Every winning sequence ends at its last move and not before (play() checks
# that against the model). Columns are played by player 1, player 2, player 1...
WINS = [
    ("player 1, vertical (left edge)", [0, 4, 0, 4, 0, 3, 0], 1),
    ("player 1, horizontal", [1, 6, 0, 5, 2, 6, 3], 1),
    ("player 1, horizontal (right edge)", [3, 0, 4, 0, 5, 0, 6], 1),
    ("player 1, diagonal /", [4, 3, 2, 5, 4, 5, 4, 6, 3, 5, 5], 1),
    ("player 1, diagonal \\", [5, 0, 2, 4, 3, 2, 4, 2, 2, 3, 3], 1),
    ("player 2, vertical", [0, 6, 5, 6, 0, 6, 5, 6], 2),
    ("player 2, horizontal", [0, 2, 0, 5, 1, 4, 0, 3], 2),
    ("player 2, diagonal /", [2, 0, 1, 3, 4, 1, 3, 3, 6, 3, 2, 2], 2),
    ("player 2, diagonal \\", [4, 6, 2, 5, 2, 4, 3, 3, 2, 2, 0, 3], 2),
]

# 42 moves, the board fills up and nobody makes four.
DRAW = [4, 3, 0, 6, 5, 0, 5, 4, 1, 5, 4, 0, 3, 4, 6, 3, 4, 6, 2, 1, 6, 3, 1, 3, 4, 6,
        3, 1, 2, 1, 6, 1, 0, 2, 0, 5, 5, 0, 2, 2, 2, 5]


def new_game(srv, n):
    """Two new clients (A<n>, B<n>) and a room in which they are already
    playing. Nothing is left unread. Returns (player 1, player 2, room id)."""
    p1, p2 = srv.client(f"A{n}"), srv.client(f"B{n}")
    room = p1.ask("CREATE_GAME Room", "GAME_CREATED").split()[1]
    start_game(p1, p2, room)
    p1.take_all()
    p2.take_all()
    return p1, p2, room


def play(p1, p2, room, cols, outcome=None):
    """Plays 'cols' from an empty board, player 1 first, and returns what went
    wrong as a list (empty when everything was as the model says): after every
    move both players must get exactly one GAME_STATE with the model's board
    and whose turn it is; after the last one, if the game ends, they must also
    get their own GAME_OVER. 'outcome' is 1 or 2 (that player wins with the
    last move), 0 (a draw with the last move) or None (the game goes on)."""
    states = replay(cols)
    assert all(w is None for _, w in states[:-1]), "a win before the last move"
    if outcome is None:
        assert states[-1][1] is None and "." in states[-1][0], "this sequence ends the game"
    elif outcome == 0:
        assert len(cols) == 42 and states[-1][1] is None and "." not in states[-1][0], "not a draw"
    else:
        assert states[-1][1] == outcome, "the last move does not win for player %d" % outcome

    bad = []
    for i, col in enumerate(cols):
        mover = p1 if i % 2 == 0 else p2
        mover.send(f"MOVE {room} {col}")
        board = states[i][0]
        last = i == len(cols) - 1 and outcome is not None
        turn = 0 if last else (2 if i % 2 == 0 else 1)
        want1 = want2 = [f"GAME_STATE {room} {turn} {board}"]
        if last:
            over1, over2 = {1: ("WIN", "LOSE"), 2: ("LOSE", "WIN"), 0: ("DRAW", "DRAW")}[outcome]
            want1 = want1 + [f"GAME_OVER {room} {over1}"]
            want2 = want2 + [f"GAME_OVER {room} {over2}"]
        got1, got2 = p1.take_all(), p2.take_all()
        if (got1, got2) != (want1, want2):
            bad.append((f"move {i + 1} (column {col})", got1, got2))
    return bad


with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    room = a.ask("CREATE_GAME Sfida", "GAME_CREATED").split()[1]
    c.take_all()

    # ------------------------------------------------ requests that make no sense
    for bad in ("MOVE", f"MOVE {room}", f"MOVE {room} 1 2", f"MOVE {room} x", "MOVE x 1", f"MOVE {room} 1x"):
        check(f"BAD_ARGS: {bad!r}", a.ask(bad, "ERROR"), "ERROR MOVE BAD_ARGS")
    for game_id in ("0", "-1", "99", "256", "257", "999999"):
        check(f"NOT_FOUND: room {game_id}", a.ask(f"MOVE {game_id} 3", "ERROR"), "ERROR MOVE NOT_FOUND")

    # ------------------------------------------------ the room is still waiting
    check("the owner cannot move yet: NOT_PLAYING", a.ask(f"MOVE {room} 3", "ERROR"), "ERROR MOVE NOT_PLAYING")
    check("someone else cannot move: NOT_PLAYER", b.ask(f"MOVE {room} 3", "ERROR"), "ERROR MOVE NOT_PLAYER")

    # ------------------------------------------------ the game starts
    start_game(a, b, room)
    check("player 1 gets an empty board and the first turn", a.take("GAME_STATE"), f"GAME_STATE {room} 1 {EMPTY}")
    check("player 2 gets the same", b.take("GAME_STATE"), f"GAME_STATE {room} 1 {EMPTY}")
    a.take_all()
    b.take_all()
    check("the others are told the room is in progress", c.take_all(), [f"GAME_IN_PROGRESS {room}"])

    # ------------------------------------------------ errors while playing
    check("not your turn", b.ask(f"MOVE {room} 3", "ERROR"), "ERROR MOVE NOT_YOUR_TURN")
    check("the turn is checked before the column", b.ask(f"MOVE {room} 9", "ERROR"), "ERROR MOVE NOT_YOUR_TURN")
    check("not a player of this game", c.ask(f"MOVE {room} 3", "ERROR"), "ERROR MOVE NOT_PLAYER")
    for col in ("7", "-1", "100"):
        check(f"INVALID_COLUMN: {col}", a.ask(f"MOVE {room} {col}", "ERROR"), "ERROR MOVE INVALID_COLUMN")
    check("errors are for the sender only", (a.take_all(), b.take_all(), c.take_all()), ([], [], []))

    # ------------------------------------------------ moves
    # The first move comes after all the refused ones above: they did not use up player 1's turn.
    # Column 0 gets six discs (no four in a column: they alternate), so it ends up full.
    moves = [3, 0, 0, 0, 0, 0, 0]
    check("first 7 moves: both get the model's GAME_STATE each time", play(a, b, room, moves), [])
    check("nobody else hears of the moves", c.take_all(), [])

    # column 0 is full now (6 discs), and it is player 2's turn
    check("COLUMN_FULL", b.ask(f"MOVE {room} 0", "ERROR"), "ERROR MOVE COLUMN_FULL")
    b.send(f"MOVE {room} 1")
    board = replay(moves + [1])[-1][0]
    check("...and the turn was not used up: player 2 plays another column",
          (a.take_all(), b.take_all()), ([f"GAME_STATE {room} 1 {board}"],) * 2)

    # a double click: the second copy of the move arrives when it is no longer our turn
    a.send_raw(f"MOVE {room} 2\nMOVE {room} 2\n".encode())
    board = replay(moves + [1, 2])[-1][0]
    check("double send, sender: one GAME_STATE, then NOT_YOUR_TURN", a.take_all(),
          [f"GAME_STATE {room} 2 {board}", "ERROR MOVE NOT_YOUR_TURN"])
    check("double send, opponent: one GAME_STATE", b.take_all(), [f"GAME_STATE {room} 2 {board}"])

    # ------------------------------------------------ two games at once do not mix
    a2, b2, room2 = new_game(srv, 2)
    check("the first game's players hear of the new room, like everyone else (§6)", (a.take_all(), b.take_all()),
          ([f"NEW_GAME {room2} Room A2", f"GAME_IN_PROGRESS {room2}"],) * 2)
    a2.send(f"MOVE {room2} 3")
    one_disc = replay([3])[-1][0]
    check("other game: its own board", (a2.take_all(), b2.take_all()), ([f"GAME_STATE {room2} 2 {one_disc}"],) * 2)
    check("...and the first game heard nothing", (a.take_all(), b.take_all()), ([], []))

    # ------------------------------------------------ every way to win, and the draw
    for n, (label, cols, winner) in enumerate(WINS, start=10):
        p1, p2, r = new_game(srv, n)
        check(f"{label}: GAME_STATE after every move, GAME_OVER at the end", play(p1, p2, r, cols, winner), [])
        check(f"{label}: the game is over, no more moves", (p1.ask(f"MOVE {r} 3", "ERROR"), p2.ask(f"MOVE {r} 3", "ERROR")),
              ("ERROR MOVE NOT_PLAYING",) * 2)

    p1, p2, r = new_game(srv, 30)
    check("draw: GAME_STATE after every move, DRAW to both at the end", play(p1, p2, r, DRAW, 0), [])
    check("draw: the game is over, no more moves", (p1.ask(f"MOVE {r} 3", "ERROR"), p2.ask(f"MOVE {r} 3", "ERROR")),
          ("ERROR MOVE NOT_PLAYING",) * 2)

    # when a game ends the others are told it leaves the list (GAME_CLOSED, once)
    w = srv.client("Watcher2")
    p1, p2, r = new_game(srv, 31)
    w.take_all()
    check("bystander: nothing while the game is played...", (play(p1, p2, r, WINS[0][1][:-1]), w.take_all()), ([], []))
    p1.send(f"MOVE {r} {WINS[0][1][-1]}")
    check("...GAME_CLOSED when it ends", w.take_all(), [f"GAME_CLOSED {r}"])

    # ------------------------------------------------ numbers that do not fit in an int
    # 2**32 is 0 once cut down to 32 bits: it must not turn into column 0 (or room 0/1...)
    p1, p2, r = new_game(srv, 40)
    p1.send(f"MOVE {r} {2 ** 32}")
    got = p1.take_all()
    check("column 2**32 is refused, not wrapped around to column 0",
          bool(got) and got[0].startswith("ERROR MOVE "), True)
    check("...and the board is untouched", (p2.take_all(), [l for l in got if l.startswith("GAME_STATE")]), ([], []))

    p1, p2, r = new_game(srv, 41)  # a new game, so that it is player 1's turn whatever happened above
    p1.send(f"MOVE {int(r) + 2 ** 32} 0")
    got = p1.take_all()
    check("room 2**32 + id is refused, not wrapped around to the room",
          bool(got) and got[0].startswith("ERROR MOVE "), True)
    check("...and the board is untouched too", (p2.take_all(), [l for l in got if l.startswith("GAME_STATE")]), ([], []))

finish("test_moves")
