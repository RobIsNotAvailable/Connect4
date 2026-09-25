"""CREATE_GAME, LIST_GAMES and NEW_GAME."""
import re

from harness import Server, check, finish

LINE = re.compile(r"GAME_LIST (\d+)((?: \d+ \S+ \S+)*)")


def parse_list(line):
    """'GAME_LIST <count> [<id> <name> <owner>]...' -> (count, [(id, name, owner), ...]),
    or None if the line is not well formed."""
    m = LINE.fullmatch(line or "")
    if not m:
        return None
    tokens = m.group(2).split()
    return int(m.group(1)), [tuple(tokens[i:i + 3]) for i in range(0, len(tokens), 3)]


def create_rooms(clients, rooms_each, room_name):
    """Every client creates 'rooms_each' rooms as fast as it can (no waiting for replies)."""
    for i, c in enumerate(clients):
        for k in range(rooms_each):
            c.s.sendall(f"CREATE_GAME {room_name(i, k)}\n".encode())
    for c in clients:
        c.pump()


# ---------------------------------------------------------------- creating
with Server() as srv:
    a, b = srv.client("Anna"), srv.client("Bruno")

    # requests that are refused create nothing and tell nobody
    check("no name", a.ask("CREATE_GAME", "ERROR"), "ERROR CREATE_GAME BAD_ARGS")
    check("a name with a space is two arguments", a.ask("CREATE_GAME Due Parole", "ERROR"), "ERROR CREATE_GAME BAD_ARGS")
    check("21 characters", a.ask("CREATE_GAME " + "x" * 21, "ERROR"), "ERROR CREATE_GAME INVALID_NAME")
    a.send_raw("CREATE_GAME Città\n".encode("utf-8"))
    check("accents are not allowed", a.take_all(), ["ERROR CREATE_GAME INVALID_NAME"])
    check("nothing was created", a.ask("LIST_GAMES", "GAME_LIST"), "GAME_LIST 0")
    check("LIST_GAMES takes no argument", a.ask("LIST_GAMES x", "ERROR"), "ERROR LIST_GAMES BAD_ARGS")
    check("LIST_MY_GAMES neither", a.ask("LIST_MY_GAMES x", "ERROR"), "ERROR LIST_MY_GAMES BAD_ARGS")
    check("nobody was told", b.take_all(), [])

    # a new room: the creator gets GAME_CREATED and NOT NEW_GAME, the others get NEW_GAME
    a.send("CREATE_GAME Sfida_1")
    check("creator: GAME_CREATED with id 1 and the name", a.take_all(), ["GAME_CREATED 1 Sfida_1"])
    check("others: NEW_GAME with id, name and creator", b.take_all(), ["NEW_GAME 1 Sfida_1 Anna"])

    # names: 20 characters and any printable symbols are fine, they need not be unique
    long_name = "n" * 20
    a.send(f"CREATE_GAME {long_name}")
    check("20-character name", a.take_all(), [f"GAME_CREATED 2 {long_name}"])
    check("...announced in full", b.take_all(), [f"NEW_GAME 2 {long_name} Anna"])
    a.send("CREATE_GAME Sfida_1")
    check("the same name twice is fine", a.take_all(), ["GAME_CREATED 3 Sfida_1"])
    b.take_all()

    # at most 5 rooms and games per client (MAX_GAMES_PER_PLAYER)
    a.send("CREATE_GAME Quarta")
    a.send("CREATE_GAME Quinta")
    check("4th and 5th room", a.take_all(), ["GAME_CREATED 4 Quarta", "GAME_CREATED 5 Quinta"])
    b.take_all()
    check("6th room refused", a.ask("CREATE_GAME Sesta", "ERROR"), "ERROR CREATE_GAME TOO_MANY_GAMES")
    check("...and not announced", b.take_all(), [])
    b.send("CREATE_GAME Uno")
    check("the limit is per client: another can create", b.take_all(), ["GAME_CREATED 6 Uno"])
    check("...and the first one is told", a.take_all(), ["NEW_GAME 6 Uno Bruno"])

    # the list has the waiting rooms of the others, by id, never one's own
    check("list: the rooms of the others", parse_list(b.ask("LIST_GAMES", "GAME_LIST")),
          (5, [("1", "Sfida_1", "Anna"), ("2", long_name, "Anna"), ("3", "Sfida_1", "Anna"),
               ("4", "Quarta", "Anna"), ("5", "Quinta", "Anna")]))
    check("list: not one's own", parse_list(a.ask("LIST_GAMES", "GAME_LIST")), (1, [("6", "Uno", "Bruno")]))

    # deleting a room frees one of the places
    a.send("LEAVE_GAME 2")
    check("LEAVE_GAME on a room of one's own that is empty", a.take("GAME_LEFT"), "GAME_LEFT 2")
    check("...others are told it is gone", b.take_all(), ["GAME_CLOSED 2"])
    a.send("CREATE_GAME Sesta")
    check("a new room fits again", (a.take_all() or [""])[0].startswith("GAME_CREATED "), True)


# ---------------------------------------------------------------- how long the list can be
# Short names: the list holds at most 32 rooms (MAX_GAMES_IN_LIST).
with Server() as srv:
    owners = [srv.client(f"P{i}") for i in range(11)]  # 11 x 3 = 33 rooms
    create_rooms(owners, 3, lambda i, k: f"r{k}")
    watcher = srv.client("Watcher")
    count, entries = parse_list(watcher.ask("LIST_GAMES", "GAME_LIST"))
    check("33 waiting rooms: the list stops at 32", (count, len(entries)), (32, 32))
    check("...the first 32, by id", [int(e[0]) for e in entries], list(range(1, 33)))

# Long names: 32 entries would not fit in a line of 1024 bytes, so the server lists only
# the ones that fit whole, and <count> says how many they are.
with Server() as srv:
    owners = [srv.client(f"{i:02d}" + "x" * 18) for i in range(11)]  # 20-character usernames
    create_rooms(owners, 3, lambda i, k: f"{k}" + "r" * 19)         # 20-character room names
    watcher = srv.client("Watcher")
    raw = watcher.ask("LIST_GAMES", "GAME_LIST")
    parsed = parse_list(raw)
    check("long names: the line is well formed", parsed is not None, True)
    count, entries = parsed
    check("...it fits in a line (1023 characters + '\\n')", len(raw) <= 1023, True)
    check("...<count> matches the entries that are there", count, len(entries))
    check("...fewer than 32, but not none", 0 < count < 32, True)
    check("...the ones with the lowest ids", [int(e[0]) for e in entries], list(range(1, count + 1)))
    check("...every name is whole", all(len(e[1]) == 20 and len(e[2]) == 20 for e in entries), True)

finish("test_create_list")
