"""REMATCH / REMATCH_NOTIFY : the two-player vote."""
from harness import EMPTY_BOARD as EMPTY, Server, check, finish, play_win, start_game as start

with Server() as srv:
    a, b, c, d = srv.client(), srv.client(), srv.client(), srv.client()
    for cl, n in ((a, "Anna"), (b, "Marco"), (c, "Carla"), (d, "Dario")):
        cl.send(f"SET_USERNAME {n}")

    # 1. errors before a rematch is possible
    check("BAD_ARGS", a.ask("REMATCH", "ERROR"), "ERROR REMATCH BAD_ARGS")
    check("BAD_ARGS (not a number)", a.ask("REMATCH x", "ERROR"), "ERROR REMATCH BAD_ARGS")
    check("NOT_FOUND", a.ask("REMATCH 999", "ERROR"), "ERROR REMATCH NOT_FOUND")
    r = a.ask("CREATE_GAME Sfida", "GAME_CREATED").split()[1]
    check("owner alone, room WAITING -> NOT_FINISHED", a.ask(f"REMATCH {r}", "ERROR"), "ERROR REMATCH NOT_FINISHED")
    check("stranger -> NOT_PLAYER", b.ask(f"REMATCH {r}", "ERROR"), "ERROR REMATCH NOT_PLAYER")
    start(a, b, r)
    check("PLAYING -> NOT_FINISHED", a.ask(f"REMATCH {r}", "ERROR"), "ERROR REMATCH NOT_FINISHED")

    # 2. game 1 finished: A votes, B is notified, A is not
    check("game 1 over (A)", play_win(a, b, r)[0], f"GAME_OVER {r} WIN")
    b.take_all(); a.take_all(); c.take_all(); d.take_all()
    a.send(f"REMATCH {r}")
    check("A's vote: A hears nothing", a.take_all(), [])
    check("A's vote: B is notified", b.take_all(), [f"REMATCH_NOTIFY {r}"])
    check("A votes twice -> ALREADY_PENDING", a.ask(f"REMATCH {r}", "ERROR"), "ERROR REMATCH ALREADY_PENDING")
    check("no start yet", b.take_all(), [])

    # 3. B votes too: game 2 starts, like an accepted join
    b.send(f"REMATCH {r}")
    check("game 2 starts (A)", a.take_all(), [f"GAME_START {r} 1 Marco", f"GAME_STATE {r} 1 {EMPTY}"])
    check("game 2 starts (B)", b.take_all(), [f"GAME_START {r} 2 Anna", f"GAME_STATE {r} 1 {EMPTY}"])
    check("lobby clients hear nothing", c.take_all() + d.take_all(), [])
    check("owner list: PLAYING", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Sfida Marco 1 PLAYING 1 HERE")
    check("player 2 cannot open", b.ask(f"MOVE {r} 3", "ERROR"), "ERROR MOVE NOT_YOUR_TURN")
    check("REMATCH while PLAYING", b.ask(f"REMATCH {r}", "ERROR"), "ERROR REMATCH NOT_FINISHED")

    # 4. game 2 finished, B votes FIRST this time: the votes of game 1 must be gone
    check("game 2 over (A)", play_win(a, b, r)[0], f"GAME_OVER {r} WIN")
    a.take_all(); b.take_all()
    b.send(f"REMATCH {r}")
    check("stale votes cleared: B alone does not start it", b.take_all(), [])
    check("A is notified", a.take_all(), [f"REMATCH_NOTIFY {r}"])
    a.send(f"REMATCH {r}")
    check("game 3 starts (A)", a.take_all(), [f"GAME_START {r} 1 Marco", f"GAME_STATE {r} 1 {EMPTY}"])
    b.take_all()

    # 5. game 3 finished, A votes, then B leaves instead: the room goes back to WAITING
    check("game 3 over (A)", play_win(a, b, r)[0], f"GAME_OVER {r} WIN")
    a.take_all(); b.take_all(); c.take_all()
    a.send(f"REMATCH {r}")
    b.take_all()
    b.send(f"LEAVE_GAME {r}")
    check("B: GAME_LEFT", b.take("GAME_LEFT"), f"GAME_LEFT {r}")
    check("A: OPPONENT_LEFT", a.take("OPPONENT_LEFT"), f"OPPONENT_LEFT {r}")
    check("A: rematch no longer possible", a.ask(f"REMATCH {r}", "ERROR"), "ERROR REMATCH NOT_FINISHED")
    check("owner list: WAITING again", a.ask("LIST_MY_GAMES", "MY_GAME_LIST"), f"MY_GAME_LIST 1 {r} Sfida - 1 WAITING 0 HERE")

    # 6. C joins the reopened room and plays: A's vote from before must not count
    c.take_all(); a.take_all()
    start(a, c, r)
    check("game 4 over (A)", play_win(a, c, r)[0], f"GAME_OVER {r} WIN")
    a.take_all(); c.take_all()
    c.send(f"REMATCH {r}")
    check("A's old vote gone: C alone does not start it", c.take_all(), [])
    check("A is notified", a.take_all(), [f"REMATCH_NOTIFY {r}"])

    # 7. C says yes and then starts another game: several games at once are allowed, so A's vote starts the rematch
    x = d.ask("CREATE_GAME Altra", "GAME_CREATED").split()[1]
    start(d, c, x)
    a.take_all(); c.take_all(); d.take_all()
    a.send(f"REMATCH {r}")
    check("game 5 starts (A), who is told that Carla is elsewhere (she plays x)", a.take_all(),
          [f"GAME_START {r} 1 Carla", f"GAME_STATE {r} 1 {EMPTY}", f"OPPONENT_STATUS {r} AWAY"])
    check("game 5 starts (C), which is also playing x", c.take_all(), [f"GAME_START {r} 2 Anna", f"GAME_STATE {r} 1 {EMPTY}"])
    check("the other game is not touched", d.take_all(), [])


finish("test_rematch")
