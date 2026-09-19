"""LIST_MY_GAMES / MY_GAME_LIST (docs/protocol.md §3)."""
import time

from harness import Server, check, finish

with Server() as srv:
    a, b = srv.client(), srv.client()

    # no username yet
    check("no username -> NO_USERNAME", a.ask("LIST_MY_GAMES", "ERROR"), "ERROR LIST_MY_GAMES NO_USERNAME")
    a.send("SET_USERNAME Anna")
    b.send("SET_USERNAME Marco")

    # nothing owned yet
    check("empty list", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # two rooms, both waiting
    r1 = a.ask("CREATE_GAME Sfida_1", "GAME_CREATED").split()[1]
    r2 = a.ask("CREATE_GAME Rivincita!", "GAME_CREATED").split()[1]
    check("two waiting rooms", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 2 {r1} Sfida_1 WAITING {r2} Rivincita! WAITING")
    check("other client sees none of them", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # PLAYING
    b.send(f"JOIN_GAME {r1}")
    a.send(f"JOIN_RESPONSE {r1} 1")
    check("owner: PLAYING", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 2 {r1} Sfida_1 PLAYING {r2} Rivincita! WAITING")
    check("player2 is not owner: still empty", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

    # FINISHED (A is player 1 and moves first: vertical four in column 0)
    for _ in range(3):
        a.send(f"MOVE {r1} 0")
        b.send(f"MOVE {r1} 1")
    a.send(f"MOVE {r1} 0")
    check("A got WIN", a.take("GAME_OVER"), f"GAME_OVER {r1} WIN")
    check("owner: FINISHED", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 2 {r1} Sfida_1 FINISHED {r2} Rivincita! WAITING")

    # the cap: a third room is fine, a fourth is refused, the list has exactly 3
    r3 = a.ask("CREATE_GAME Terza", "GAME_CREATED").split()[1]
    check("4th room refused", a.ask("CREATE_GAME Quarta", "ERROR"), "ERROR CREATE_GAME TOO_MANY_GAMES")
    check("three rooms listed", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 3 {r1} Sfida_1 FINISHED {r2} Rivincita! WAITING {r3} Terza WAITING")

    # the owner leaves a room where B is player 2: B becomes its owner, WAITING
    a.send(f"LEAVE_GAME {r1}")
    check("A lost room 1", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 2 {r2} Rivincita! WAITING {r3} Terza WAITING")
    check("B now owns room 1 (WAITING)", b.ask("LIST_MY_GAMES", "MY_GAME_LIST"),
          f"MY_GAME_LIST 1 {r1} Sfida_1 WAITING")

    # a waiting room the owner leaves is deleted and disappears from the list
    a.send(f"LEAVE_GAME {r2}")
    check("deleted room gone", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r3} Terza WAITING")

    # a new client starts with nothing, even after A disconnected
    a.close()
    time.sleep(0.3)
    c = srv.client()
    c.send("SET_USERNAME Carla")
    check("new client, empty list", c.ask("LIST_MY_GAMES", "MY_GAME_LIST"), "MY_GAME_LIST 0")

finish("test_my_games")
