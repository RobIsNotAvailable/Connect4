"""Disconnections: a pending joiner, the owner of a waiting room, the second
player or the owner of a game in progress or finished; a room that
passes to a player at the limit of games, a client involved in several games
at once. LEAVE_GAME runs the same rules on a single room, see test_leave.py."""
import time

from harness import EMPTY_BOARD as EMPTY, Server, check, drain, finish, play_win, start_game as start


def hang_up(client):
    """The client drops the connection; gives the server a moment to notice."""
    client.close()
    time.sleep(0.1)


def listed(client):
    """LIST_GAMES as a set of (id, name, owner)."""
    tokens = client.ask("LIST_GAMES", "GAME_LIST").split()[2:]
    return {tuple(tokens[i:i + 3]) for i in range(0, len(tokens), 3)}


with Server() as srv:
    a, b, c, d = (srv.client(n) for n in ("Anna", "Marco", "Carla", "Dario"))

    # 1. the creator of a waiting room, with a joiner still waiting for an answer
    r = a.ask("CREATE_GAME Attesa", "GAME_CREATED").split()[1]
    drain(b, c, d)
    b.send(f"JOIN_GAME {r}")
    drain(a)
    hang_up(a)
    check("pending joiner: GAME_CLOSED, the request is over", b.take_all(), [f"GAME_CLOSED {r}"])
    check("lobby: GAME_CLOSED", (c.take_all(), d.take_all()), ([f"GAME_CLOSED {r}"], [f"GAME_CLOSED {r}"]))
    check("gone from LIST_GAMES", c.ask("LIST_GAMES", "GAME_LIST"), "GAME_LIST 0")
    check("JOIN_GAME -> NOT_FOUND", b.ask(f"JOIN_GAME {r}", "ERROR"), "ERROR JOIN_GAME NOT_FOUND")
    a = srv.client("Anna")  # the name is free again

    # 2. a joiner drops before the owner has answered: the room stays, waiting
    r = a.ask("CREATE_GAME Attesa", "GAME_CREATED").split()[1]
    drain(b, c, d)
    b.send(f"JOIN_GAME {r}")
    check("the owner is asked", a.take_all(), [f"JOIN_NOTIFY {r} Marco"])
    hang_up(b)
    check("owner: JOIN_CANCELLED", a.take_all(), [f"JOIN_CANCELLED {r}"])
    check("lobby hears nothing", (c.take_all(), d.take_all()), ([], []))
    check("room still in the list", listed(c), {(r, "Attesa", "Anna")})
    c.send(f"JOIN_GAME {r}")
    check("...and takes new requests", a.take_all(), [f"JOIN_NOTIFY {r} Carla"])
    a.send(f"JOIN_RESPONSE {r} 0")
    check("...the old one is gone: the answer goes to Carla", c.take_all(), [f"JOIN_RESULT {r} 0"])
    b = srv.client("Marco")

    # 2b. a joiner with requests in two rooms drops: both owners are told
    x = d.ask("CREATE_GAME Altra", "GAME_CREATED").split()[1]
    drain(a, b, c)
    b.send(f"JOIN_GAME {r}")
    b.send(f"JOIN_GAME {x}")
    drain(a, d)
    hang_up(b)
    check("first owner: JOIN_CANCELLED", a.take_all(), [f"JOIN_CANCELLED {r}"])
    check("second owner: JOIN_CANCELLED", d.take_all(), [f"JOIN_CANCELLED {x}"])
    d.send(f"LEAVE_GAME {x}")
    b = srv.client("Marco")
    drain(a, b, c, d)

    # 3. player 2 drops in the middle of a game: the room goes back to WAITING, empty
    start(a, b, r)
    a.send(f"MOVE {r} 0")
    b.send(f"MOVE {r} 1")
    drain(a, b, c, d)
    hang_up(b)
    check("owner: OPPONENT_LEFT, no GAME_OVER", a.take_all(), [f"OPPONENT_LEFT {r}"])
    check("lobby: NEW_GAME", (c.take_all(), d.take_all()),
          ([f"NEW_GAME {r} Attesa Anna"], [f"NEW_GAME {r} Attesa Anna"]))
    check("owner list: WAITING", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Attesa - 1 WAITING 0 HERE")
    start(a, c, r)
    check("a new player finds an empty board", a.take_all()[-2:], [f"GAME_START {r} 1 Carla", f"GAME_STATE {r} 1 {EMPTY}"])
    b = srv.client("Marco")
    drain(c, d)

    # 4. the creator drops in the middle of a game: player 2 takes the room over
    hang_up(a)
    check("player 2: OPPONENT_LEFT", c.take_all(), [f"OPPONENT_LEFT {r}"])
    check("lobby: NEW_GAME with the new creator", (b.take_all(), d.take_all()),
          ([f"NEW_GAME {r} Attesa Carla"], [f"NEW_GAME {r} Attesa Carla"]))
    check("Carla owns it now, waiting", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Attesa - 1 WAITING 0 HERE")
    a = srv.client("Anna")
    drain(b, d)

    # 5. the same two cases with the game already finished
    start(c, b, r)
    check("Carla wins", play_win(c, b, r), (f"GAME_OVER {r} WIN", f"GAME_OVER {r} LOSE"))
    drain(a, b, c, d)
    hang_up(b)
    check("finished, player 2 drops: owner OPPONENT_LEFT", c.take_all(), [f"OPPONENT_LEFT {r}"])
    check("finished, player 2 drops: lobby NEW_GAME", (a.take_all(), d.take_all()),
          ([f"NEW_GAME {r} Attesa Carla"], [f"NEW_GAME {r} Attesa Carla"]))
    b = srv.client("Marco")
    start(c, b, r)
    check("Carla wins again", play_win(c, b, r), (f"GAME_OVER {r} WIN", f"GAME_OVER {r} LOSE"))
    drain(a, b, c, d)
    hang_up(c)
    check("finished, creator drops: player 2 OPPONENT_LEFT", b.take_all(), [f"OPPONENT_LEFT {r}"])
    check("finished, creator drops: lobby NEW_GAME with Marco", (a.take_all(), d.take_all()),
          ([f"NEW_GAME {r} Attesa Marco"], [f"NEW_GAME {r} Attesa Marco"]))
    check("no rematch in a room that is waiting", b.ask(f"REMATCH {r}", "ERROR"), "ERROR REMATCH NOT_FINISHED")
    c = srv.client("Carla")
    b.send(f"LEAVE_GAME {r}")
    drain(a, b, c, d)

    # 6. the creator drops while the second player is at the limit of 5 (4
    # rooms of her own and this game): the room passes to her all the same,
    # since it already counted for her
    e, f = srv.client("Elisa"), srv.client("Fabio")
    ys = [e.ask(f"CREATE_GAME Y{i}", "GAME_CREATED").split()[1] for i in range(4)]
    z = f.ask("CREATE_GAME Zeta", "GAME_CREATED").split()[1]
    start(f, e, z)
    drain(a, e, f)
    hang_up(f)
    check("second player at the limit: OPPONENT_LEFT, she owns it now", e.take_all(), [f"OPPONENT_LEFT {z}"])
    check("lobby: NEW_GAME with Elisa", a.take_all(), [f"NEW_GAME {z} Zeta Elisa"])
    check("Elisa has 5, Zeta included", e.ask("LIST_MY_GAMES", "MY_GAME_LIST").split()[:2], ["MY_GAME_LIST", "5"])
    check("...and is still at the limit", e.ask("CREATE_GAME Altra", "ERROR"), "ERROR CREATE_GAME TOO_MANY_GAMES")
    e.send(f"LEAVE_GAME {z}")
    drain(a, b, c, d, e)

    # 7. both players drop, one after the other: the room disappears
    g, h = srv.client("Gino"), srv.client("Ilaria")
    w = g.ask("CREATE_GAME Coppia", "GAME_CREATED").split()[1]
    start(g, h, w)
    drain(a, g, h)
    hang_up(g)
    check("the second one takes it over", h.take_all(), [f"OPPONENT_LEFT {w}"])
    check("lobby: NEW_GAME with Ilaria", a.take_all(), [f"NEW_GAME {w} Coppia Ilaria"])
    hang_up(h)
    check("lobby: GAME_CLOSED", a.take_all(), [f"GAME_CLOSED {w}"])
    check("the room is gone", a.ask(f"JOIN_GAME {w}", "ERROR"), "ERROR JOIN_GAME NOT_FOUND")
    drain(b, c, d, e)

    # 8. one client in several games at once: owner of a waiting room, owner of
    # a room in play, and a joiner still waiting in a third. Every one of them
    # gets what it is owed. (Notifications come in room-id order, and ids are
    # reused, so each list is compared sorted.)
    r3 = c.ask("CREATE_GAME Terza", "GAME_CREATED").split()[1]
    drain(a, b, d, e)
    a.send(f"JOIN_GAME {r3}")
    check("Carla is asked", c.take_all(), [f"JOIN_NOTIFY {r3} Anna"])
    r1 = a.ask("CREATE_GAME Prima", "GAME_CREATED").split()[1]
    r2 = a.ask("CREATE_GAME Seconda", "GAME_CREATED").split()[1]
    start(a, b, r2)
    drain(a, b, c, d, e)
    hang_up(a)
    check("joiner's owner: JOIN_CANCELLED, then what the lobby hears",
          sorted(c.take_all()), sorted([f"JOIN_CANCELLED {r3}", f"GAME_CLOSED {r1}", f"NEW_GAME {r2} Seconda Marco"]))
    check("player 2 of the game in play: OPPONENT_LEFT, and the waiting room closes",
          sorted(b.take_all()), sorted([f"OPPONENT_LEFT {r2}", f"GAME_CLOSED {r1}"]))
    check("lobby: the waiting room closes, the other one is joinable again",
          sorted(d.take_all()), sorted([f"GAME_CLOSED {r1}", f"NEW_GAME {r2} Seconda Marco"]))
    elisa_rooms = {(ys[n], f"Y{n}", "Elisa") for n in range(4)}  # still waiting, from step 6
    check("LIST_GAMES: Terza and Seconda, Prima is gone", listed(d),
          elisa_rooms | {(r3, "Terza", "Carla"), (r2, "Seconda", "Marco")})
    a = srv.client("Anna")
    drain(a, b, c, d, e)

    # 9. a client that never chose a username drops: nothing to tell, and the
    # server is fine
    ghost = srv.client()
    hang_up(ghost)
    check("nobody was told anything", (a.take_all(), b.take_all(), c.take_all()), ([], [], []))
    check("the server still answers", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

finish("test_disconnect")
