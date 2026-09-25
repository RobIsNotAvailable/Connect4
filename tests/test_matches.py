"""OPPONENT_STATUS and the limit of MAX_GAMES_PER_PLAYER rooms and games at
once. The list of one's games is in test_my_games.py."""
from harness import EMPTY_BOARD as EMPTY, Server, check, drain, finish, new_room, play_win, start_game

MAX_GAMES = 5


def statuses(client):
    """The OPPONENT_STATUS lines a client has received; the rest is thrown away."""
    return [l for l in client.take_all() if l.startswith("OPPONENT_STATUS")]


def fill(srv, x, n, tag):
    """Gives 'x' n games in progress, each in a room of its own against a new
    client. Returns [(partner, room), ...]."""
    matches = []
    for k in range(n):
        partner = srv.client(f"{tag}{k}")
        room = new_room(x, f"{tag}room{k}")
        start_game(x, partner, room)
        matches.append((partner, room))
    return matches


# ---------------------------------------------------------------- OPPONENT_STATUS
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1, r2 = new_room(a, "Prima"), new_room(a, "Seconda")
    start_game(a, b, r1)
    drain(a, b, c)
    start_game(a, c, r2)  # Anna is in the middle of r1: it stays her active game
    check("the new opponent is told at once that Anna is elsewhere", c.take_all(),
          [f"JOIN_RESULT {r2} 1", f"GAME_IN_PROGRESS {r2}", f"GAME_START {r2} 2 Anna", f"GAME_STATE {r2} 1 {EMPTY}",
           f"OPPONENT_STATUS {r2} AWAY"])
    check("Anna is told nothing about Carla (HERE is the default)", a.take_all(),
          [f"JOIN_NOTIFY {r2} Carla", f"GAME_IN_PROGRESS {r2}", f"GAME_START {r2} 1 Carla", f"GAME_STATE {r2} 1 {EMPTY}"])
    check("Bruno only hears that r2 started: Anna's status in r1 did not change", b.take_all(),
          [f"GAME_IN_PROGRESS {r2}"])

    a.send(f"SET_ACTIVE_GAME {r2}")
    check("Anna moves to r2: Bruno is told she is away from r1", statuses(b), [f"OPPONENT_STATUS {r1} AWAY"])
    check("...and Carla is told she is here, in r2", statuses(c), [f"OPPONENT_STATUS {r2} HERE"])

    a.send("SET_ACTIVE_GAME 0")
    check("Anna goes to the lobby: Carla is told she is away from r2, Bruno hears nothing (already away from r1)",
          (statuses(b), statuses(c)), ([], [f"OPPONENT_STATUS {r2} AWAY"]))

    a.send(f"SET_ACTIVE_GAME {r1}")
    check("Anna goes back to r1: Bruno is told she is here, Carla hears nothing (away from r2 either way)",
          (statuses(b), statuses(c)), ([f"OPPONENT_STATUS {r1} HERE"], []))

    a.send(f"SET_ACTIVE_GAME {r1}")
    check("choosing the game that is already active says nothing", (statuses(b), statuses(c)), ([], []))

    check("the list says the same", (a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), c.ask("LIST_MY_GAMES", "MY_GAME_LIST")),
          (f"MY_GAME_LIST 2 {r1} Prima Bruno 1 PLAYING 1 HERE {r2} Seconda Carla 1 PLAYING 1 HERE",
           f"MY_GAME_LIST 1 {r2} Seconda Anna 2 PLAYING 1 AWAY"))

    # leaving the active game: whoever played her in her other games is told she is back
    drain(a, b, c)
    a.send(f"LEAVE_GAME {r1}")
    check("Anna leaves r1, her active game: Carla hears nothing (Anna was away from r2 and is still, in the lobby)",
          statuses(c), [])
    check("Bruno, alone in r1 now, has no game left to be told about", statuses(b), [])

    # a game that is over while the player is in another one, and its rematch
    a.send(f"SET_ACTIVE_GAME {r2}")
    check("Anna goes to r2: Carla is told she is here", statuses(c), [f"OPPONENT_STATUS {r2} HERE"])
    check("r2 is played to the end (Anna wins)", play_win(a, c, r2)[0], f"GAME_OVER {r2} WIN")
    drain(a, b, c)
    start_game(b, a, r1)  # Bruno owns r1 now: Anna joins it. Her active game r2 is over, so r1 replaces it
    check("Anna starts r1 while r2 is over: Carla sees her away from r2", statuses(c), [f"OPPONENT_STATUS {r2} AWAY"])
    drain(a, b, c)
    c.send(f"REMATCH {r2}")
    a.send(f"REMATCH {r2}")
    check("the rematch starts: GAME_START, then AWAY again for Anna", c.take_all(),
          [f"GAME_START {r2} 2 Anna", f"GAME_STATE {r2} 1 {EMPTY}", f"OPPONENT_STATUS {r2} AWAY"])
    check("Carla is here for Anna", statuses(a), [])


# one game only: the opponent goes to the lobby and comes back
with Server() as srv:
    a, b = srv.client("Anna"), srv.client("Bruno")
    r = new_room(a, "Sola")
    start_game(a, b, r)
    drain(a, b)
    b.send("SET_ACTIVE_GAME 0")
    check("Bruno goes to the lobby: Anna is told he is away", statuses(a), [f"OPPONENT_STATUS {r} AWAY"])
    check("...and Bruno is told nothing", statuses(b), [])
    check("...the list says it too", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Sola Bruno 1 PLAYING 1 AWAY")
    b.send(f"SET_ACTIVE_GAME {r}")
    check("Bruno comes back: Anna is told he is here", statuses(a), [f"OPPONENT_STATUS {r} HERE"])
    check("...and the list says HERE", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Sola Bruno 1 PLAYING 1 HERE")


# the one who is elsewhere is the joiner: the owner is told
with Server() as srv:
    a, b, c = srv.client("Anna"), srv.client("Bruno"), srv.client("Carla")
    r1 = new_room(a, "Prima")
    start_game(a, b, r1)  # Bruno plays r1
    rc = new_room(c, "DiCarla")
    drain(a, b, c)
    start_game(c, b, rc)  # Bruno asks for Carla's room, Carla accepts
    check("the owner is told that the joiner is elsewhere", c.take_all(),
          [f"JOIN_NOTIFY {rc} Bruno", f"GAME_IN_PROGRESS {rc}", f"GAME_START {rc} 1 Bruno", f"GAME_STATE {rc} 1 {EMPTY}",
           f"OPPONENT_STATUS {rc} AWAY"])
    check("the joiner is told nothing about the owner", b.take_all(),
          [f"JOIN_RESULT {rc} 1", f"GAME_IN_PROGRESS {rc}", f"GAME_START {rc} 2 Carla", f"GAME_STATE {rc} 1 {EMPTY}"])


# ---------------------------------------------------------------- the limit: a joiner who is full
with Server() as srv:
    a = srv.client("Anna")
    matches = fill(srv, a, MAX_GAMES, "A")
    g = srv.client("Gino")
    room = new_room(g, "Nuova")
    drain(a, g)
    reply = a.ask("LIST_MY_GAMES", "MY_GAME_LIST")
    check("five games in progress, all listed", reply.split()[:2], ["MY_GAME_LIST", str(MAX_GAMES)])
    check("...in a line that fits", len(reply) < 1024, True)
    check("full: JOIN_GAME -> TOO_MANY_GAMES", a.ask(f"JOIN_GAME {room}", "ERROR"), "ERROR JOIN_GAME TOO_MANY_GAMES")
    check("...the room's owner heard nothing", g.take_all(), [])
    check("full: CREATE_GAME -> TOO_MANY_GAMES", a.ask("CREATE_GAME Altra", "ERROR"), "ERROR CREATE_GAME TOO_MANY_GAMES")

    first_partner, first_room = matches[0]
    check("a game is played to the end (Anna wins)", play_win(a, first_partner, first_room)[0], f"GAME_OVER {first_room} WIN")
    drain(a, g)
    check("a finished game keeps its place: still full", a.ask(f"JOIN_GAME {room}", "ERROR"),
          "ERROR JOIN_GAME TOO_MANY_GAMES")

    partner, left = matches[-1]  # the room passes to the partner: Anna has a place again
    a.send(f"LEAVE_GAME {left}")
    drain(a, g, partner)
    a.send(f"JOIN_GAME {room}")
    check("a place is free again: the owner is notified", g.take_all(), [f"JOIN_NOTIFY {room} Anna"])


# ---------------------------------------------------------------- the limit counts the rooms that wait too
with Server() as srv:
    a, b = srv.client("Anna"), srv.client("Bruno")
    rooms = [new_room(a, f"R{k}") for k in range(MAX_GAMES)]
    other = new_room(b, "DiBruno")
    drain(a, b)
    check("five waiting rooms: full, no joining", a.ask(f"JOIN_GAME {other}", "ERROR"), "ERROR JOIN_GAME TOO_MANY_GAMES")
    a.send(f"LEAVE_GAME {rooms[0]}")
    drain(a, b)
    a.send(f"JOIN_GAME {other}")
    check("a deleted room frees a place", b.take_all(), [f"JOIN_NOTIFY {other} Anna"])


# ---------------------------------------------------------------- the limit: an owner who is full can still accept
# The room already counts among the owner's games: accepting takes no new place.
with Server() as srv:
    h, i = srv.client("Hana"), srv.client("Ivan")
    room = new_room(h, "Attesa")
    i.send(f"JOIN_GAME {room}")
    fill(srv, h, MAX_GAMES - 1, "H")  # four games in progress, plus the waiting room
    drain(h, i)
    check("the owner is full", h.ask("CREATE_GAME Altra", "ERROR"), "ERROR CREATE_GAME TOO_MANY_GAMES")
    h.send(f"JOIN_RESPONSE {room} 1")
    check("...but the request is accepted: the game starts", i.take_all(),
          [f"JOIN_RESULT {room} 1", f"GAME_IN_PROGRESS {room}", f"GAME_START {room} 2 Hana", f"GAME_STATE {room} 1 {EMPTY}",
           f"OPPONENT_STATUS {room} AWAY"])


# ---------------------------------------------------------------- the limit: a joiner who filled up while waiting
with Server() as srv:
    a, o = srv.client("Anna"), srv.client("Olga")
    fill(srv, a, MAX_GAMES - 1, "B")
    room = new_room(o, "Chiesta")
    a.send(f"JOIN_GAME {room}")  # four games: still allowed
    check("Anna had a place: Olga is notified", o.take_all()[-1:], [f"JOIN_NOTIFY {room} Anna"])
    fill(srv, a, 1, "C")  # a fifth game, in another room
    drain(a, o)
    check("Olga accepts but Anna is full now: JOINER_FULL", o.ask(f"JOIN_RESPONSE {room} 1", "ERROR"),
          "ERROR JOIN_RESPONSE JOINER_FULL")
    check("...Anna is told the request is refused", [l for l in a.take_all() if l.startswith("JOIN_")],
          [f"JOIN_RESULT {room} 0"])
    check("...the request is gone: NO_PENDING", o.ask(f"JOIN_RESPONSE {room} 1", "ERROR"), "ERROR JOIN_RESPONSE NO_PENDING")
    check("...and the room goes on waiting", o.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 1 {room} Chiesta - 1 WAITING 0 HERE")

finish("test_matches")
