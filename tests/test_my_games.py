"""LIST_MY_GAMES / MY_GAME_LIST (docs/protocol.md §3): every room and game the
client is a player of, the ones that still wait for an opponent included."""
import time

from harness import Server, check, drain, finish, new_room, play_win, start_game

with Server() as srv:
    a, b, c = srv.client(), srv.client(), srv.client("Carla")

    # no username yet
    check("no username -> NO_USERNAME", a.ask("LIST_MY_GAMES", "ERROR"), "ERROR LIST_MY_GAMES NO_USERNAME")
    a.send("SET_USERNAME Anna")
    b.send("SET_USERNAME Bruno")
    check("empty list", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # rooms that wait: no opponent ('-'), turn 0
    r1, r2 = new_room(a, "Prima"), new_room(a, "Rivincita!")
    check("two waiting rooms", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 2 {r1} Prima - 1 WAITING 0 HERE {r2} Rivincita! - 1 WAITING 0 HERE")
    check("another client has none of them", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # a request that is pending is not a game of the joiner yet
    b.send(f"JOIN_GAME {r1}")
    check("the joiner, request pending: nothing", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # PLAYING: each player sees it with the other one as opponent
    a.send(f"JOIN_RESPONSE {r1} 1")
    drain(a, b, c)
    check("owner: player 1", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 2 {r1} Prima Bruno 1 PLAYING 1 HERE {r2} Rivincita! - 1 WAITING 0 HERE")
    check("joiner: player 2", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r1} Prima Anna 2 PLAYING 1 HERE")
    check("someone else: nothing", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # the turn, and whether the opponent's active game is this one (HERE) or not (AWAY)
    start_game(a, c, r2)  # Anna is in the middle of r1: it stays her active game
    a.send(f"MOVE {r1} 0")
    drain(a, b, c)
    check("two games, by id; the turn moved on in r1", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 2 {r1} Prima Bruno 1 PLAYING 2 HERE {r2} Rivincita! Carla 1 PLAYING 1 HERE")
    check("Carla sees Anna away (she plays r1)", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 1 {r2} Rivincita! Anna 2 PLAYING 1 AWAY")

    # FINISHED: the game stays listed until its players leave it
    a.send(f"SET_ACTIVE_GAME {r2}")
    check("r2 is played to the end (Anna wins)", play_win(a, c, r2)[0], f"GAME_OVER {r2} WIN")
    drain(a, b, c)
    check("FINISHED, turn 0", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r2} Rivincita! Anna 2 FINISHED 0 HERE")

    # the owner leaves a game where Bruno is player 2: he owns it now, and it waits again
    a.send(f"LEAVE_GAME {r1}")
    drain(a, b, c)
    check("Anna left r1", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r2} Rivincita! Carla 1 FINISHED 0 HERE")
    check("Bruno owns r1 now, waiting", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r1} Prima - 1 WAITING 0 HERE")

    # a waiting room its owner leaves is deleted
    b.send(f"LEAVE_GAME {r1}")
    check("deleted room gone", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # Anna disconnects: Carla owns r2, waiting; a new client starts with nothing
    a.close()
    time.sleep(0.3)
    check("Carla owns r2 now, waiting", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r2} Rivincita! - 1 WAITING 0 HERE")
    check("new client, empty list", srv.client("Dario").ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

finish("test_my_games")
