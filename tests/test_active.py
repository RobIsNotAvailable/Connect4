"""SET_ACTIVE_GAME and NOT_ACTIVE: a client can be in
several games but plays actively one at a time."""
from harness import EMPTY_BOARD as EMPTY, Server, check, drain, finish, new_room, play_win, start_game

BOARD_1_COL0 = "." * 35 + "1" + "." * 6  # player 1 dropped a disc in column 0


# ---------------------------------------------------------------- SET_ACTIVE_GAME: what it accepts and refuses
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r = new_room(a, "Sfida")
    drain(a, b, c)

    for bad in ("SET_ACTIVE_GAME", "SET_ACTIVE_GAME x", f"SET_ACTIVE_GAME {r} 1", f"SET_ACTIVE_GAME {r}x"):
        check(f"BAD_ARGS: {bad!r}", a.ask(bad, "ERROR"), "ERROR SET_ACTIVE_GAME BAD_ARGS")
    for game_id in ("99", "-1", "257"):
        check(f"NOT_FOUND: room {game_id}", a.ask(f"SET_ACTIVE_GAME {game_id}", "ERROR"), "ERROR SET_ACTIVE_GAME NOT_FOUND")
    check("the owner alone in a waiting room: NOT_PLAYING", a.ask(f"SET_ACTIVE_GAME {r}", "ERROR"),
          "ERROR SET_ACTIVE_GAME NOT_PLAYING")
    check("a stranger: NOT_PLAYER", c.ask(f"SET_ACTIVE_GAME {r}", "ERROR"), "ERROR SET_ACTIVE_GAME NOT_PLAYER")
    b.send(f"JOIN_GAME {r}")
    drain(a, b)
    check("a joiner whose request is pending is not a player: NOT_PLAYER", b.ask(f"SET_ACTIVE_GAME {r}", "ERROR"),
          "ERROR SET_ACTIVE_GAME NOT_PLAYER")
    check("0 is always fine, and gets no reply", (a.ask("SET_ACTIVE_GAME 0", "ERROR"), a.take_all()), (None, []))
    a.send(f"JOIN_RESPONSE {r} 1")
    drain(a, b, c)
    check("in the game, as player 1: no reply", (a.ask(f"SET_ACTIVE_GAME {r}", "ERROR"), a.take_all()), (None, []))
    check("in the game, as player 2: no reply", (b.ask(f"SET_ACTIVE_GAME {r}", "ERROR"), b.take_all()), (None, []))
    check("the others are told nothing about who plays what", c.take_all(), [])


# ---------------------------------------------------------------- one game: nothing to do, it is active by itself
with Server() as srv:
    a, b = srv.client("Anna"), srv.client("Bruno")
    r = new_room(a, "Sfida")
    start_game(a, b, r)
    drain(a, b)
    a.send(f"MOVE {r} 0")
    check("a client that plays one game never sends SET_ACTIVE_GAME: the move works",
          (a.take_all(), b.take_all()), ([f"GAME_STATE {r} 2 {BOARD_1_COL0}"],) * 2)


# ---------------------------------------------------------------- two games: only the active one takes moves
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1, r2 = new_room(a, "Prima"), new_room(a, "Seconda")
    start_game(a, b, r1)
    start_game(a, c, r2)  # Anna is in the middle of r1
    drain(a, b, c)

    check("the game Anna was already in stays active; the new one is not", a.ask(f"MOVE {r2} 0", "ERROR"),
          "ERROR MOVE NOT_ACTIVE")
    check("...and its opponent is not told anything", c.take_all(), [])
    check("Carla, who had no game, has r2 active by itself (only her turn is missing)", c.ask(f"MOVE {r2} 0", "ERROR"),
          "ERROR MOVE NOT_YOUR_TURN")

    a.send(f"MOVE {r1} 0")
    check("Anna moves in r1", (a.take_all(), b.take_all()), ([f"GAME_STATE {r1} 2 {BOARD_1_COL0}"],) * 2)
    check("r1 is still active for Anna: her second move in a row is NOT_YOUR_TURN",
          a.ask(f"MOVE {r1} 3", "ERROR"), "ERROR MOVE NOT_YOUR_TURN")

    a.send(f"SET_ACTIVE_GAME {r2}")
    check("after SET_ACTIVE_GAME the second game takes moves", (a.ask(f"MOVE {r2} 0", "GAME_STATE"), c.take("GAME_STATE")),
          (f"GAME_STATE {r2} 2 {BOARD_1_COL0}",) * 2)
    check("...and the first no longer does; it is not her turn there either, but NOT_ACTIVE comes first",
          a.ask(f"MOVE {r1} 3", "ERROR"), "ERROR MOVE NOT_ACTIVE")

    b.send(f"MOVE {r1} 1")
    check("Bruno's game is untouched by all this: his move works", b.take("GAME_STATE"), f"GAME_STATE {r1} 1 " + "." * 35 + "12" + "." * 5)
    drain(a, b, c)

    a.send(f"SET_ACTIVE_GAME {r1}")
    a.send(f"MOVE {r1} 3")
    check("back to the first game: it works again", a.take("GAME_STATE"), f"GAME_STATE {r1} 2 " + "." * 35 + "12" + "." + "1" + "." * 3)

    a.send("SET_ACTIVE_GAME 0")
    check("no active game: no moves", a.ask(f"MOVE {r1} 4", "ERROR"), "ERROR MOVE NOT_ACTIVE")


# ---------------------------------------------------------------- the other MOVE errors come before NOT_ACTIVE
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1, r2 = new_room(a, "Prima"), new_room(a, "Seconda")
    start_game(a, b, r1)
    drain(a, b, c)
    a.send("SET_ACTIVE_GAME 0")
    check("no such game: NOT_FOUND", a.ask("MOVE 99 0", "ERROR"), "ERROR MOVE NOT_FOUND")
    check("not a player: NOT_PLAYER", c.ask(f"MOVE {r1} 0", "ERROR"), "ERROR MOVE NOT_PLAYER")
    check("a waiting game: NOT_PLAYING", a.ask(f"MOVE {r2} 0", "ERROR"), "ERROR MOVE NOT_PLAYING")
    check("a game in progress that is not active: NOT_ACTIVE", a.ask(f"MOVE {r1} 0", "ERROR"), "ERROR MOVE NOT_ACTIVE")


# ---------------------------------------------------------------- a game that finished does not keep the next one from being active
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1, r2 = new_room(a, "Prima"), new_room(a, "Seconda")
    start_game(a, b, r1)
    check("r1 is played to the end (Anna wins)", play_win(a, b, r1)[0], f"GAME_OVER {r1} WIN")
    drain(a, b, c)
    start_game(a, c, r2)  # r1 is FINISHED but is still Anna's active game
    drain(a, b, c)
    a.send(f"MOVE {r2} 0")
    check("r2 became Anna's active game by itself: her move works", a.take("GAME_STATE"), f"GAME_STATE {r2} 2 {BOARD_1_COL0}")
    check("the finished game takes no moves", a.ask(f"MOVE {r1} 0", "ERROR"), "ERROR MOVE NOT_PLAYING")


# ---------------------------------------------------------------- leaving a game
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1, r2 = new_room(a, "Prima"), new_room(a, "Seconda")
    start_game(a, b, r1)
    start_game(a, c, r2)  # Anna: r1 active, r2 suspended
    drain(a, b, c)
    a.send(f"LEAVE_GAME {r1}")
    drain(a, b, c)
    check("Anna left her active game: the other one is not active by itself", a.ask(f"MOVE {r2} 0", "ERROR"),
          "ERROR MOVE NOT_ACTIVE")
    a.send(f"SET_ACTIVE_GAME {r2}")
    a.send(f"MOVE {r2} 0")
    check("...but she can choose it", a.take("GAME_STATE"), f"GAME_STATE {r2} 2 {BOARD_1_COL0}")

    # a game that starts when the player has no active game becomes active
    a.send("SET_ACTIVE_GAME 0")
    d = srv.client("Dario")
    r3 = new_room(a, "Terza")
    drain(a, b, c, d)
    start_game(a, d, r3)
    drain(a, b, c, d)
    a.send(f"MOVE {r3} 0")
    check("a new game starts while Anna has no active game: it becomes hers", a.take("GAME_STATE"),
          f"GAME_STATE {r3} 2 {BOARD_1_COL0}")


# ---------------------------------------------------------------- the id of a game that is gone must not linger as the active game
with Server() as srv:
    a, b, c, d, e = (srv.client(n) for n in ("Anna", "Bruno", "Carla", "Dario", "Eva"))
    r1 = new_room(a, "Prima")
    start_game(a, b, r1)
    a.send(f"LEAVE_GAME {r1}")  # r1 was Anna's active game; Bruno stays alone in it
    b.send(f"LEAVE_GAME {r1}")  # ...and leaving alone deletes the room: its id is free
    drain(a, b, c, d, e)
    x = new_room(c, "Riusata")
    check("the freed id is given to the next room", x, r1)
    start_game(c, d, x)  # a game in progress that has the id of Anna's old active game
    y = new_room(a, "Nuova")
    drain(a, b, c, d, e)
    start_game(a, e, y)
    drain(a, b, c, d, e)
    a.send(f"MOVE {y} 0")
    check("Anna's new game is her active one, though her old game's id belongs to a game in progress", a.take("GAME_STATE"),
          f"GAME_STATE {y} 2 {BOARD_1_COL0}")


# Same, when the owner leaves and the room passes to the other player: it is no longer played, so it
# stops being her active game; then she deletes it and its id goes to someone else's game.
with Server() as srv:
    e, f, g, h, i = (srv.client(n) for n in ("Elisa", "Fabio", "Gino", "Hugo", "Ilaria"))
    ys = [new_room(e, "Y0")]
    z = new_room(f, "Zeta")
    start_game(f, e, z)  # Elisa is player 2 of z, which is her active game
    f.send(f"LEAVE_GAME {z}")
    check("the room passes to Elisa", e.take("OPPONENT_LEFT"), f"OPPONENT_LEFT {z}")
    e.send(f"LEAVE_GAME {z}")
    drain(e, f, g, h, i)
    w = new_room(g, "Riusata")
    check("the deleted room's id is given to the next room", w, z)
    start_game(g, h, w)
    start_game(e, i, ys[0])
    drain(e, f, g, h, i)
    e.send(f"MOVE {ys[0]} 0")
    check("Elisa's new game is her active one, though the id of her old game belongs to another game in progress",
          e.take("GAME_STATE"), f"GAME_STATE {ys[0]} 2 {BOARD_1_COL0}")

finish("test_active")
