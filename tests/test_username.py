"""WELCOME and SET_USERNAME (docs/protocol.md §2)."""
import re
import time

from harness import Server, check, finish

with Server() as srv:
    a, b = srv.client(), srv.client()

    # the greeting: one line, WELCOME with the client's id, a different id each
    ids = [re.fullmatch(r"WELCOME (\d+)", c.lines[0]).group(1) if len(c.lines) == 1 and re.fullmatch(r"WELCOME (\d+)", c.lines[0]) else None
           for c in (a, b)]
    check("each new client gets exactly one WELCOME <id>", None not in ids, True)
    check("...and the ids differ", ids[0] != ids[1], True)
    a.take_all(); b.take_all()

    # until a username is chosen every command is refused, the command name echoed back
    check("LIST_GAMES before naming", a.ask("LIST_GAMES", "ERROR"), "ERROR LIST_GAMES NO_USERNAME")
    check("CREATE_GAME before naming", a.ask("CREATE_GAME Sfida", "ERROR"), "ERROR CREATE_GAME NO_USERNAME")
    check("unknown command before naming", a.ask("FOO", "ERROR"), "ERROR FOO NO_USERNAME")

    # a name that is refused leaves the client free to try again
    check("no argument", a.ask("SET_USERNAME", "ERROR"), "ERROR SET_USERNAME BAD_ARGS")
    check("a name with a space is two arguments", a.ask("SET_USERNAME Anna Rossi", "ERROR"), "ERROR SET_USERNAME BAD_ARGS")
    check("21 characters", a.ask("SET_USERNAME " + "x" * 21, "ERROR"), "ERROR SET_USERNAME INVALID_NAME")
    a.send_raw("SET_USERNAME Renè\n".encode("utf-8"))
    check("accents are not allowed", a.take_all(), ["ERROR SET_USERNAME INVALID_NAME"])
    a.send_raw(b"SET_USERNAME ab\x7fcd\n")
    check("control characters are not allowed", a.take_all(), ["ERROR SET_USERNAME INVALID_NAME"])
    check("still unnamed after all that", a.ask("LIST_GAMES", "ERROR"), "ERROR LIST_GAMES NO_USERNAME")

    check("a valid name", a.ask("SET_USERNAME Anna", "USERNAME_SET"), "USERNAME_SET Anna")
    check("the refused commands did not create anything", a.ask("LIST_GAMES", "GAME_LIST"), "GAME_LIST 0")
    check("the name is chosen once", a.ask("SET_USERNAME Altro", "ERROR"), "ERROR SET_USERNAME ALREADY_NAMED")
    check("...even the same one again", a.ask("SET_USERNAME Anna", "ERROR"), "ERROR SET_USERNAME ALREADY_NAMED")

    # usernames are unique among connected clients, letters case aside
    check("taken", b.ask("SET_USERNAME Anna", "ERROR"), "ERROR SET_USERNAME USERNAME_TAKEN")
    check("taken, other case", b.ask("SET_USERNAME anna", "ERROR"), "ERROR SET_USERNAME USERNAME_TAKEN")
    check("taken, upper case", b.ask("SET_USERNAME ANNA", "ERROR"), "ERROR SET_USERNAME USERNAME_TAKEN")
    check("then another name works", b.ask("SET_USERNAME Bruno", "USERNAME_SET"), "USERNAME_SET Bruno")

    # the name is shown as it was typed, and it never changed
    a.send("CREATE_GAME Sfida")
    check("others see the chosen name", b.take_all(), ["NEW_GAME 1 Sfida Anna"])
    a.take_all()

    # the longest name (20 characters) is fine
    long_name = "n" * 20
    c = srv.client()
    check("20 characters", c.ask("SET_USERNAME " + long_name, "USERNAME_SET"), "USERNAME_SET " + long_name)

    # a name is free again once its owner disconnects
    a.close()
    time.sleep(0.3)
    b.take_all()  # the room of the client who left is gone: GAME_CLOSED
    d = srv.client()
    check("reused after a disconnect, in another case", d.ask("SET_USERNAME anna", "USERNAME_SET"), "USERNAME_SET anna")

    # several clients asking for the same name at the same instant: exactly one gets it
    for round_ in range(3):
        name = f"Race{round_}"
        racers = [srv.client() for _ in range(10)]
        for r in racers:
            r.s.sendall(f"SET_USERNAME {name}\n".encode())
        for r in racers:
            r.pump()
        answers = sorted(r.take_all()[-1] for r in racers)
        check(f"race for {name}: one winner, nine refused", answers,
              sorted([f"USERNAME_SET {name}"] + ["ERROR SET_USERNAME USERNAME_TAKEN"] * 9))
        for r in racers:
            r.close()
        time.sleep(0.3)

finish("test_username")
