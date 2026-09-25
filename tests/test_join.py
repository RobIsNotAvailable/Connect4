"""JOIN_GAME, JOIN_NOTIFY, JOIN_RESPONSE, JOIN_RESULT, and a client in several
games at once."""
from harness import EMPTY_BOARD as EMPTY, Server, check, drain, finish, new_room, start_game


# ---------------------------------------------------------------- JOIN_GAME, then accepted
with Server() as srv:
    a, b, c, d = (srv.client(n) for n in ("Anna", "Bruno", "Carla", "Dario"))
    room = new_room(a, "Sfida")
    drain(a, b, c, d)

    # requests that are refused at once
    for bad in ("JOIN_GAME", "JOIN_GAME x", f"JOIN_GAME {room} 2", f"JOIN_GAME {room}x"):
        check(f"BAD_ARGS: {bad!r}", b.ask(bad, "ERROR"), "ERROR JOIN_GAME BAD_ARGS")
    for game_id in ("0", "-1", "2", "99", "257"):
        check(f"NOT_FOUND: room {game_id}", b.ask(f"JOIN_GAME {game_id}", "ERROR"), "ERROR JOIN_GAME NOT_FOUND")
    check("the owner cannot join its own room: SELF_JOIN", a.ask(f"JOIN_GAME {room}", "ERROR"), "ERROR JOIN_GAME SELF_JOIN")
    check("errors are for the sender only", (a.take_all(), b.take_all(), c.take_all(), d.take_all()), ([], [], [], []))

    # a request: the owner is told, the joiner gets no reply, nobody else hears of it
    b.send(f"JOIN_GAME {room}")
    check("owner: JOIN_NOTIFY with the joiner's username", a.take_all(), [f"JOIN_NOTIFY {room} Bruno"])
    check("joiner: no reply until the owner decides", b.take_all(), [])
    check("others: nothing", (c.take_all(), d.take_all()), ([], []))
    check("a second joiner: ALREADY_PENDING", c.ask(f"JOIN_GAME {room}", "ERROR"), "ERROR JOIN_GAME ALREADY_PENDING")
    check("the same joiner again: ALREADY_PENDING", b.ask(f"JOIN_GAME {room}", "ERROR"), "ERROR JOIN_GAME ALREADY_PENDING")
    check("the owner is not bothered by the refused requests", a.take_all(), [])
    check("a room with a request pending is still in the list", d.ask("LIST_GAMES", "GAME_LIST"),
          f"GAME_LIST 1 {room} Sfida Anna")

    # accepted: the game starts, and everyone is told it is in progress (the
    # two players too, so their list of waiting rooms loses it)
    a.send(f"JOIN_RESPONSE {room} 1")
    check("joiner: JOIN_RESULT, GAME_IN_PROGRESS, then GAME_START and the empty board", b.take_all(),
          [f"JOIN_RESULT {room} 1", f"GAME_IN_PROGRESS {room}", f"GAME_START {room} 2 Anna", f"GAME_STATE {room} 1 {EMPTY}"])
    check("owner: GAME_IN_PROGRESS, GAME_START and the empty board", a.take_all(),
          [f"GAME_IN_PROGRESS {room}", f"GAME_START {room} 1 Bruno", f"GAME_STATE {room} 1 {EMPTY}"])
    check("others: GAME_IN_PROGRESS", (c.take_all(), d.take_all()), ([f"GAME_IN_PROGRESS {room}"],) * 2)
    check("the room is out of the list", c.ask("LIST_GAMES", "GAME_LIST"), "GAME_LIST 0")
    check("nobody can join it any more: NOT_WAITING", c.ask(f"JOIN_GAME {room}", "ERROR"), "ERROR JOIN_GAME NOT_WAITING")
    check("...and the owner is told SELF_JOIN before NOT_WAITING", a.ask(f"JOIN_GAME {room}", "ERROR"),
          "ERROR JOIN_GAME SELF_JOIN")
    check("the request is used up: NO_PENDING", a.ask(f"JOIN_RESPONSE {room} 1", "ERROR"), "ERROR JOIN_RESPONSE NO_PENDING")


# ---------------------------------------------------------------- JOIN_RESPONSE: errors and refusal
with Server() as srv:
    a, b, c, d = (srv.client(n) for n in ("Anna", "Bruno", "Carla", "Dario"))
    room = new_room(a, "Sfida")
    drain(a, b, c, d)

    for bad in ("JOIN_RESPONSE", f"JOIN_RESPONSE {room}", f"JOIN_RESPONSE {room} 1 1",
                "JOIN_RESPONSE x 1", f"JOIN_RESPONSE {room} x"):
        check(f"BAD_ARGS: {bad!r}", a.ask(bad, "ERROR"), "ERROR JOIN_RESPONSE BAD_ARGS")
    for game_id in ("0", "-1", "2", "99", "257"):
        check(f"NOT_FOUND: room {game_id}", a.ask(f"JOIN_RESPONSE {game_id} 1", "ERROR"), "ERROR JOIN_RESPONSE NOT_FOUND")
    check("the owner, no request yet: NO_PENDING", a.ask(f"JOIN_RESPONSE {room} 1", "ERROR"), "ERROR JOIN_RESPONSE NO_PENDING")
    check("not the owner: NOT_OWNER (checked before NO_PENDING)", b.ask(f"JOIN_RESPONSE {room} 1", "ERROR"),
          "ERROR JOIN_RESPONSE NOT_OWNER")

    b.send(f"JOIN_GAME {room}")
    drain(a)
    check("not the owner, request pending: NOT_OWNER", c.ask(f"JOIN_RESPONSE {room} 1", "ERROR"), "ERROR JOIN_RESPONSE NOT_OWNER")
    check("the joiner cannot answer its own request", b.ask(f"JOIN_RESPONSE {room} 1", "ERROR"), "ERROR JOIN_RESPONSE NOT_OWNER")
    check("...and the request is still there, nobody was told", (a.take_all(), b.take_all(), c.take_all()), ([], [], []))

    # refused: the room goes on waiting
    a.send(f"JOIN_RESPONSE {room} 0")
    check("joiner: JOIN_RESULT 0", b.take_all(), [f"JOIN_RESULT {room} 0"])
    check("owner and others: nothing", (a.take_all(), c.take_all(), d.take_all()), ([], [], []))
    check("the room is still in the list", d.ask("LIST_GAMES", "GAME_LIST"), f"GAME_LIST 1 {room} Sfida Anna")
    check("the request is used up: NO_PENDING", a.ask(f"JOIN_RESPONSE {room} 1", "ERROR"), "ERROR JOIN_RESPONSE NO_PENDING")

    # ...and it can get new requests, even from the one that was refused
    b.send(f"JOIN_GAME {room}")
    check("the refused joiner asks again: the owner is notified", a.take_all(), [f"JOIN_NOTIFY {room} Bruno"])
    a.send(f"JOIN_RESPONSE {room} 0")
    drain(b)
    c.send(f"JOIN_GAME {room}")
    check("another joiner asks: the owner is notified", a.take_all(), [f"JOIN_NOTIFY {room} Carla"])
    a.send(f"JOIN_RESPONSE {room} 1")
    check("...and is accepted", c.take_all(),
          [f"JOIN_RESULT {room} 1", f"GAME_IN_PROGRESS {room}", f"GAME_START {room} 2 Anna", f"GAME_STATE {room} 1 {EMPTY}"])
    check("the ones not involved are told it is in progress", (b.take_all(), d.take_all()),
          ([f"GAME_IN_PROGRESS {room}"],) * 2)


# ---------------------------------------------------------------- a client can be in several games at once
BOARD_1_COL0 = "." * 35 + "1" + "." * 6  # player 1 dropped a disc in column 0
BOARD_1_COL3 = "." * 38 + "1" + "." * 3  # player 1 dropped a disc in column 3

# A client that is already playing can ask for another room, and get in.
with Server() as srv:
    a, b, d = srv.client("Anna"), srv.client("Bruno"), srv.client("Dario")
    r1 = new_room(a, "Prima")
    start_game(a, b, r1)
    r2, r3 = new_room(d, "Seconda"), new_room(d, "Terza")
    drain(a, b, d)
    b.send(f"JOIN_GAME {r2}")
    check("joiner who is playing: the owner is notified", d.take_all(), [f"JOIN_NOTIFY {r2} Bruno"])
    d.send(f"JOIN_RESPONSE {r2} 1")
    check("...and it gets in", b.take_all(),
          [f"JOIN_RESULT {r2} 1", f"GAME_IN_PROGRESS {r2}", f"GAME_START {r2} 2 Dario", f"GAME_STATE {r2} 1 {EMPTY}"])
    check("...the owner starts r2 as player 1, and is told Bruno is elsewhere (he is playing r1)", d.take_all(),
          [f"GAME_IN_PROGRESS {r2}", f"GAME_START {r2} 1 Bruno", f"GAME_STATE {r2} 1 {EMPTY}", f"OPPONENT_STATUS {r2} AWAY"])
    check("...the game it was already in is not told", a.take_all(), [f"GAME_IN_PROGRESS {r2}"])
    a.send(f"JOIN_GAME {r3}")
    check("the owner of a game in progress can ask too", d.take_all(), [f"JOIN_NOTIFY {r3} Anna"])
    d.send(f"JOIN_RESPONSE {r3} 1")
    check("an owner who is playing elsewhere can accept: it plays both games", d.take_all(),
          [f"GAME_IN_PROGRESS {r3}", f"GAME_START {r3} 1 Anna", f"GAME_STATE {r3} 1 {EMPTY}", f"OPPONENT_STATUS {r3} AWAY"])

# The games of one client are independent: a move only reaches the two players of its game.
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1, r2 = new_room(a, "Prima"), new_room(a, "Seconda")
    start_game(a, b, r1)
    c.send(f"JOIN_GAME {r2}")
    drain(a, b, c)
    a.send(f"JOIN_RESPONSE {r2} 1")  # Anna is in the middle of r1
    check("joiner: JOIN_RESULT, GAME_START as player 2, and Anna is elsewhere (she is in r1)", c.take_all(),
          [f"JOIN_RESULT {r2} 1", f"GAME_IN_PROGRESS {r2}", f"GAME_START {r2} 2 Anna", f"GAME_STATE {r2} 1 {EMPTY}",
           f"OPPONENT_STATUS {r2} AWAY"])
    check("owner: GAME_START as player 1 of the second game", a.take_all(),
          [f"GAME_IN_PROGRESS {r2}", f"GAME_START {r2} 1 Carla", f"GAME_STATE {r2} 1 {EMPTY}"])
    drain(b)
    a.send(f"MOVE {r1} 0")
    check("a move in r1: Anna and Bruno hear of it, Carla does not",
          (a.take_all(), b.take_all(), c.take_all()),
          ([f"GAME_STATE {r1} 2 {BOARD_1_COL0}"], [f"GAME_STATE {r1} 2 {BOARD_1_COL0}"], []))
    a.send(f"SET_ACTIVE_GAME {r2}")  # she plays one game at a time: r1 was the active one
    drain(b, c)  # the OPPONENT_STATUS lines of the switch are checked in test_matches.py
    a.send(f"MOVE {r2} 3")
    check("a move in r2: Anna and Carla hear of it, Bruno does not",
          (a.take_all(), b.take_all(), c.take_all()),
          ([f"GAME_STATE {r2} 2 {BOARD_1_COL3}"], [], [f"GAME_STATE {r2} 2 {BOARD_1_COL3}"]))

# Requests pending in two rooms can both be accepted; leaving one game leaves the other alone.
with Server() as srv:
    a, c, d = srv.client("Anna"), srv.client("Carla"), srv.client("Dario")
    r1, r3 = new_room(a, "Prima"), new_room(d, "Terza")
    c.send(f"JOIN_GAME {r1}")
    c.send(f"JOIN_GAME {r3}")
    drain(a, c, d)
    a.send(f"JOIN_RESPONSE {r1} 1")
    d.send(f"JOIN_RESPONSE {r3} 1")
    check("both owners accept: Carla is in both games", c.take_all(),
          [f"JOIN_RESULT {r1} 1", f"GAME_IN_PROGRESS {r1}", f"GAME_START {r1} 2 Anna", f"GAME_STATE {r1} 1 {EMPTY}",
           f"JOIN_RESULT {r3} 1", f"GAME_IN_PROGRESS {r3}", f"GAME_START {r3} 2 Dario", f"GAME_STATE {r3} 1 {EMPTY}"])
    drain(a, d)
    c.send(f"LEAVE_GAME {r1}")
    check("Carla leaves r1: Anna is told", a.take("OPPONENT_LEFT"), f"OPPONENT_LEFT {r1}")
    check("...Dario, in the other game, hears nothing about it", d.take("OPPONENT_LEFT"), None)
    drain(a, c, d)
    d.send(f"MOVE {r3} 0")
    check("the game with Dario goes on: Carla sees his move", c.take_all(), [f"GAME_STATE {r3} 2 {BOARD_1_COL0}"])


# ---------------------------------------------------------------- many joiners at the same instant
# Only one request can be pending: one gets in silently, nine get ALREADY_PENDING.
with Server() as srv:
    owner = srv.client("Owner")
    racers = [srv.client(f"R{i}") for i in range(10)]
    for round_ in range(3):
        room = new_room(owner, f"Sfida{round_}")
        drain(owner, *racers)
        for r in racers:
            r.s.sendall(f"JOIN_GAME {room}\n".encode())
        for r in racers:
            r.pump()
        results = [r.take_all() for r in racers]
        refused = [i for i, res in enumerate(results) if res == ["ERROR JOIN_GAME ALREADY_PENDING"]]
        silent = [i for i, res in enumerate(results) if res == []]
        check(f"race for room {room}: nine refused, one waiting", (len(refused), len(silent)), (9, 1))
        winner = f"R{silent[0]}" if len(silent) == 1 else None
        check("...the owner got one JOIN_NOTIFY, for the one that got in", owner.take_all(),
              [f"JOIN_NOTIFY {room} {winner}"])


# ---------------------------------------------------------------- the answer is 0 or 1, nothing else
# (own server: if the answer 2 were accepted, the game would start and spoil the checks after it)
with Server() as srv:
    a, b = srv.client("Anna"), srv.client("Bruno")
    room = new_room(a, "Sfida")
    b.send(f"JOIN_GAME {room}")
    drain(a, b)
    for answer in ("2", "-1"):
        check(f"answer {answer}: BAD_ARGS", a.ask(f"JOIN_RESPONSE {room} {answer}", "ERROR"), "ERROR JOIN_RESPONSE BAD_ARGS")
    check("...the request is still pending, nobody was told anything", (a.take_all(), b.take_all()), ([], []))
    a.send(f"JOIN_RESPONSE {room} 1")
    check("...and the owner can still answer it", b.take("JOIN_RESULT"), f"JOIN_RESULT {room} 1")

finish("test_join")
