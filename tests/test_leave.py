"""LEAVE_GAME / GAME_LEFT (docs/protocol.md §7): what the sender, the player left
behind and everyone else hear in each case. A disconnect runs the same rules,
see test_disconnect.py."""
from harness import EMPTY_BOARD as EMPTY, Server, check, drain, finish, play_win, start_game as start


with Server() as srv:
    a, b, c, d = (srv.client(n) for n in ("Anna", "Marco", "Carla", "Dario"))

    # 1. errors that do not depend on any room
    check("BAD_ARGS", a.ask("LEAVE_GAME", "ERROR"), "ERROR LEAVE_GAME BAD_ARGS")
    check("BAD_ARGS (not a number)", a.ask("LEAVE_GAME x", "ERROR"), "ERROR LEAVE_GAME BAD_ARGS")
    check("BAD_ARGS (extra argument)", a.ask("LEAVE_GAME 1 2", "ERROR"), "ERROR LEAVE_GAME BAD_ARGS")
    check("NOT_FOUND (no such room)", a.ask("LEAVE_GAME 999", "ERROR"), "ERROR LEAVE_GAME NOT_FOUND")
    check("NOT_FOUND (id 0)", a.ask("LEAVE_GAME 0", "ERROR"), "ERROR LEAVE_GAME NOT_FOUND")
    check("NOT_FOUND (id -1)", a.ask("LEAVE_GAME -1", "ERROR"), "ERROR LEAVE_GAME NOT_FOUND")
    nobody = srv.client()
    check("no username -> NO_USERNAME", nobody.ask("LEAVE_GAME 1", "ERROR"), "ERROR LEAVE_GAME NO_USERNAME")

    # 2. the creator, alone, deletes a room; a pending joiner is not a player
    r = a.ask("CREATE_GAME Sfida", "GAME_CREATED").split()[1]
    drain(b, c, d)
    check("stranger -> NOT_PLAYER", b.ask(f"LEAVE_GAME {r}", "ERROR"), "ERROR LEAVE_GAME NOT_PLAYER")
    b.send(f"JOIN_GAME {r}")
    check("the owner is asked", a.take_all(), [f"JOIN_NOTIFY {r} Marco"])
    check("pending joiner -> NOT_PLAYER, and nothing else", b.ask(f"LEAVE_GAME {r}", "ERROR"), "ERROR LEAVE_GAME NOT_PLAYER")
    check("...the owner heard nothing", a.take_all(), [])
    a.send(f"LEAVE_GAME {r}")
    check("owner: GAME_LEFT and nothing else (no GAME_CLOSED)", a.take_all(), [f"GAME_LEFT {r}"])
    check("pending joiner: GAME_CLOSED, the request is over", b.take_all(), [f"GAME_CLOSED {r}"])
    check("lobby: GAME_CLOSED", c.take_all(), [f"GAME_CLOSED {r}"])
    check("gone from LIST_GAMES", c.ask("LIST_GAMES", "GAME_LIST"), "GAME_LIST 0")
    check("gone from LIST_MY_GAMES", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")
    check("JOIN_GAME -> NOT_FOUND", b.ask(f"JOIN_GAME {r}", "ERROR"), "ERROR JOIN_GAME NOT_FOUND")
    check("second LEAVE_GAME -> NOT_FOUND", a.ask(f"LEAVE_GAME {r}", "ERROR"), "ERROR LEAVE_GAME NOT_FOUND")
    drain(d)

    # 3. deleting a room frees one of the 5 places (rooms and games) of a client
    rooms = [a.ask(f"CREATE_GAME S{i}", "GAME_CREATED").split()[1] for i in range(5)]
    check("6th room refused", a.ask("CREATE_GAME Sesta", "ERROR"), "ERROR CREATE_GAME TOO_MANY_GAMES")
    a.send(f"LEAVE_GAME {rooms[1]}")
    q = a.ask("CREATE_GAME Sesta", "GAME_CREATED")
    check("after leaving one, a new room is allowed", q.split()[2], "Sesta")
    for room in rooms[:1] + rooms[2:] + [q.split()[1]]:
        a.send(f"LEAVE_GAME {room}")
    check("a owns nothing again", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")
    drain(a, b, c, d)

    # 4. player 2 leaves a game in progress: the room goes back to WAITING, empty
    r = a.ask("CREATE_GAME Sfida", "GAME_CREATED").split()[1]
    start(a, b, r)
    a.send(f"MOVE {r} 0")
    b.send(f"MOVE {r} 1")
    drain(a, b, c, d)
    b.send(f"LEAVE_GAME {r}")
    check("player 2: GAME_LEFT first, then NEW_GAME like any lobby client", b.take_all(),
          [f"GAME_LEFT {r}", f"NEW_GAME {r} Sfida Anna"])
    check("owner: OPPONENT_LEFT and no GAME_OVER (nobody wins)", a.take_all(), [f"OPPONENT_LEFT {r}"])
    check("lobby: NEW_GAME", c.take_all(), [f"NEW_GAME {r} Sfida Anna"])
    check("owner list: WAITING", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Sfida - 1 WAITING 0 HERE")
    check("LIST_GAMES shows it again", c.ask("LIST_GAMES", "GAME_LIST"), f"GAME_LIST 1 {r} Sfida Anna")
    check("no moves in a room that is waiting", a.ask(f"MOVE {r} 0", "ERROR"), "ERROR MOVE NOT_PLAYING")
    check("player 2 has left: second LEAVE_GAME -> NOT_PLAYER", b.ask(f"LEAVE_GAME {r}", "ERROR"), "ERROR LEAVE_GAME NOT_PLAYER")

    # 5. a new player gets an empty board (the discs of the old game are gone)
    drain(a, b, c, d)
    start(a, c, r)
    check("owner: fresh game", a.take_all()[-2:], [f"GAME_START {r} 1 Carla", f"GAME_STATE {r} 1 {EMPTY}"])
    check("newcomer: fresh game", c.take_all(),
          [f"JOIN_RESULT {r} 1", f"GAME_IN_PROGRESS {r}", f"GAME_START {r} 2 Anna", f"GAME_STATE {r} 1 {EMPTY}"])
    check("former player 2 is a lobby client: GAME_IN_PROGRESS", b.take_all(), [f"GAME_IN_PROGRESS {r}"])

    # 6. the creator, with a second player, leaves: the second player takes over
    drain(d)
    a.send(f"LEAVE_GAME {r}")
    check("old owner: GAME_LEFT, then NEW_GAME with the new creator", a.take_all(),
          [f"GAME_LEFT {r}", f"NEW_GAME {r} Sfida Carla"])
    check("new owner: OPPONENT_LEFT", c.take_all(), [f"OPPONENT_LEFT {r}"])
    check("lobby: NEW_GAME with Carla as creator", (b.take_all(), d.take_all()),
          ([f"NEW_GAME {r} Sfida Carla"], [f"NEW_GAME {r} Sfida Carla"]))
    check("Carla owns it now", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Sfida - 1 WAITING 0 HERE")
    check("Anna owns nothing", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")
    check("Anna is not in it any more: LEAVE_GAME -> NOT_PLAYER", a.ask(f"LEAVE_GAME {r}", "ERROR"), "ERROR LEAVE_GAME NOT_PLAYER")
    a.send(f"JOIN_GAME {r}")
    check("...she can ask to join it like anyone", c.take_all(), [f"JOIN_NOTIFY {r} Anna"])
    c.send(f"JOIN_RESPONSE {r} 0")
    check("...and be refused", a.take_all(), [f"JOIN_RESULT {r} 0"])
    drain(b, c, d)

    # 7. a finished game can be left too
    start(c, b, r)
    check("Carla wins", play_win(c, b, r), (f"GAME_OVER {r} WIN", f"GAME_OVER {r} LOSE"))
    drain(a, b, c, d)
    b.send(f"LEAVE_GAME {r}")
    check("loser: GAME_LEFT, NEW_GAME", b.take_all(), [f"GAME_LEFT {r}", f"NEW_GAME {r} Sfida Carla"])
    check("winner: OPPONENT_LEFT", c.take_all(), [f"OPPONENT_LEFT {r}"])
    check("room WAITING", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Sfida - 1 WAITING 0 HERE")
    drain(a, b, c, d)

    # 8. a client in a game can ask for another room (§5.1), before or after leaving
    x = d.ask("CREATE_GAME Altra", "GAME_CREATED").split()[1]
    start(c, b, r)
    drain(a, b, c, d)
    b.send(f"JOIN_GAME {x}")
    check("B is playing r and asks for x: the owner is notified", d.take_all(), [f"JOIN_NOTIFY {x} Marco"])
    d.send(f"JOIN_RESPONSE {x} 0")
    drain(a, b, c, d)
    b.send(f"LEAVE_GAME {r}")
    drain(a, b, c, d)
    b.send(f"JOIN_GAME {x}")
    check("B left r and asks again", d.take_all(), [f"JOIN_NOTIFY {x} Marco"])
    d.send(f"JOIN_RESPONSE {x} 0")
    d.send(f"LEAVE_GAME {x}")
    c.send(f"LEAVE_GAME {r}")
    drain(a, b, c, d)

    # 9. the creator leaves while the second player is at the limit of 5 (4
    # rooms of her own and this game): the room passes to her all the same,
    # since it already counted for her (§8)
    e, f = srv.client("Elisa"), srv.client("Fabio")
    ys = [e.ask(f"CREATE_GAME Y{i}", "GAME_CREATED").split()[1] for i in range(4)]
    z = f.ask("CREATE_GAME Zeta", "GAME_CREATED").split()[1]
    start(f, e, z)
    drain(a, e, f)
    f.send(f"LEAVE_GAME {z}")
    check("old owner: GAME_LEFT, then NEW_GAME with Elisa", f.take_all(), [f"GAME_LEFT {z}", f"NEW_GAME {z} Zeta Elisa"])
    check("second player: OPPONENT_LEFT, she owns it now", e.take_all(), [f"OPPONENT_LEFT {z}"])
    check("lobby: NEW_GAME with Elisa", a.take_all(), [f"NEW_GAME {z} Zeta Elisa"])
    # the list is in id order, and ids are reused, so not necessarily Y0..Y3, Zeta
    names = {int(ys[n]): f"Y{n}" for n in range(4)} | {int(z): "Zeta"}
    by_id = " ".join(f"{i} {names[i]} - 1 WAITING 0 HERE" for i in sorted(names))
    check("Elisa has her 4 rooms and Zeta", e.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 5 {by_id}")
    e.send(f"LEAVE_GAME {z}")
    check("...alone in Zeta, she deletes it by leaving", e.take_all(), [f"GAME_LEFT {z}"])
    check("Zeta is gone", f.ask(f"JOIN_GAME {z}", "ERROR"), "ERROR JOIN_GAME NOT_FOUND")

    # 10. both players leave one after the other: the last one deletes the room,
    # and the one who left first hears about it like everyone else
    g, h = srv.client("Gino"), srv.client("Ilaria")
    w = g.ask("CREATE_GAME Coppia", "GAME_CREATED").split()[1]
    start(g, h, w)
    drain(a, g, h)
    g.send(f"LEAVE_GAME {w}")
    check("first to leave: GAME_LEFT, NEW_GAME", g.take_all(), [f"GAME_LEFT {w}", f"NEW_GAME {w} Coppia Ilaria"])
    check("the other one is the creator now", h.take_all(), [f"OPPONENT_LEFT {w}"])
    drain(a)
    h.send(f"LEAVE_GAME {w}")
    check("last to leave: GAME_LEFT only", h.take_all(), [f"GAME_LEFT {w}"])
    check("first to leave: GAME_CLOSED", g.take_all(), [f"GAME_CLOSED {w}"])
    check("lobby: GAME_CLOSED", a.take_all(), [f"GAME_CLOSED {w}"])

finish("test_leave")
