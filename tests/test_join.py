"""JOIN_GAME, JOIN_NOTIFY, JOIN_RESPONSE, JOIN_RESULT (docs/protocol.md §4) and
the rules of §5.1 on who can play (a client plays one game at a time)."""
from harness import EMPTY_BOARD as EMPTY, Server, check, finish, start_game


def new_room(owner, name):
    """The owner creates a room; returns its id."""
    return owner.ask(f"CREATE_GAME {name}", "GAME_CREATED").split()[1]


def drain(*clients):
    """Throws away what the clients have not read yet (NEW_GAME and the like)."""
    for c in clients:
        c.take_all()


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

    # accepted: the game starts
    a.send(f"JOIN_RESPONSE {room} 1")
    check("joiner: JOIN_RESULT, then GAME_START and the empty board", b.take_all(),
          [f"JOIN_RESULT {room} 1", f"GAME_START {room} 2 Anna", f"GAME_STATE {room} 1 {EMPTY}"])
    check("owner: GAME_START and the empty board", a.take_all(),
          [f"GAME_START {room} 1 Bruno", f"GAME_STATE {room} 1 {EMPTY}"])
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
          [f"JOIN_RESULT {room} 1", f"GAME_START {room} 2 Anna", f"GAME_STATE {room} 1 {EMPTY}"])
    check("the ones not involved are told it is in progress", (b.take_all(), d.take_all()),
          ([f"GAME_IN_PROGRESS {room}"],) * 2)


# ---------------------------------------------------------------- a client plays one game at a time (§5.1)
# A joiner who is already playing.
with Server() as srv:
    a, b, d = srv.client("Anna"), srv.client("Bruno"), srv.client("Dario")
    r1 = new_room(a, "Prima")
    start_game(a, b, r1)
    r2 = new_room(d, "Seconda")
    drain(a, b, d)
    check("joiner who is playing: ALREADY_PLAYING", b.ask(f"JOIN_GAME {r2}", "ERROR"), "ERROR JOIN_GAME ALREADY_PLAYING")
    check("...also when it is the owner of the game it plays", a.ask(f"JOIN_GAME {r2}", "ERROR"), "ERROR JOIN_GAME ALREADY_PLAYING")
    check("...and the room's owner heard nothing", d.take_all(), [])

# An owner who is playing elsewhere cannot accept; the request stays where it was.
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1, r2 = new_room(a, "Prima"), new_room(a, "Seconda")
    c.send(f"JOIN_GAME {r1}")
    start_game(a, b, r2)  # Anna is playing now
    drain(a, b, c)
    check("owner playing elsewhere: ALREADY_PLAYING", a.ask(f"JOIN_RESPONSE {r1} 1", "ERROR"), "ERROR JOIN_RESPONSE ALREADY_PLAYING")
    check("...the joiner is told nothing (the request is not consumed)", c.take_all(), [])
    a.send(f"JOIN_RESPONSE {r1} 0")
    check("...the owner can still refuse it", c.take_all(), [f"JOIN_RESULT {r1} 0"])

# A joiner who started playing after asking (§5.1): the request is cancelled.
with Server() as srv:
    a, b, c, d = (srv.client(n) for n in ("Anna", "Bruno", "Carla", "Dario"))
    r1, r3 = new_room(a, "Prima"), new_room(d, "Terza")
    c.send(f"JOIN_GAME {r1}")
    c.send(f"JOIN_GAME {r3}")
    d.send(f"JOIN_RESPONSE {r3} 1")  # Carla is playing in r3 now
    drain(a, b, c, d)
    check("another owner accepted first: the owner gets JOINER_BUSY", a.ask(f"JOIN_RESPONSE {r1} 1", "ERROR"),
          "ERROR JOIN_RESPONSE JOINER_BUSY")
    check("...and the joiner is told the request is refused", c.take_all(), [f"JOIN_RESULT {r1} 0"])
    check("...the room goes on waiting", b.ask("LIST_GAMES", "GAME_LIST"), f"GAME_LIST 1 {r1} Prima Anna")
    check("...with nothing pending: NO_PENDING", a.ask(f"JOIN_RESPONSE {r1} 1", "ERROR"), "ERROR JOIN_RESPONSE NO_PENDING")
    b.send(f"JOIN_GAME {r1}")
    check("...and somebody else can ask", a.take_all(), [f"JOIN_NOTIFY {r1} Bruno"])

# Same, when the joiner started playing by accepting a request in its own room.
with Server() as srv:
    a, c, e = srv.client("Anna"), srv.client("Carla"), srv.client("Eva")
    r1, r4 = new_room(a, "Prima"), new_room(c, "DiCarla")
    c.send(f"JOIN_GAME {r1}")
    e.send(f"JOIN_GAME {r4}")
    c.send(f"JOIN_RESPONSE {r4} 1")  # Carla is playing in her own room now
    drain(a, c, e)
    check("joiner playing in a room of its own: JOINER_BUSY", a.ask(f"JOIN_RESPONSE {r1} 1", "ERROR"),
          "ERROR JOIN_RESPONSE JOINER_BUSY")
    check("...and the joiner is told the request is refused", c.take_all(), [f"JOIN_RESULT {r1} 0"])


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
