"""The rules of docs/protocol.md §1: how lines are framed and what happens to
a line the server does not understand."""
import re

from harness import Server, check, finish

with Server() as srv:
    a = srv.client("Anna")

    # unknown or badly written commands
    check("unknown command", a.ask("FOO", "ERROR"), "ERROR - UNKNOWN_COMMAND")
    check("commands are upper case", a.ask("list_games", "ERROR"), "ERROR - UNKNOWN_COMMAND")
    a.send_raw(b"\n")
    check("a blank line is ignored", a.take_all(), [])

    # framing: a '\r' before the '\n' is ignored, lines can be split or glued together
    a.send_raw(b"LIST_GAMES\r\n")
    check("\\r\\n is accepted", a.take_all(), ["GAME_LIST 0"])
    a.send_raw(b"LIST_")
    check("half a line: no answer yet", a.take_all(), [])
    a.send_raw(b"GAMES\n")
    check("...the answer comes with the rest of the line", a.take_all(), ["GAME_LIST 0"])
    a.send_raw(b"LIST_GAMES\nLIST_GAMES\nFOO\n")
    check("three lines in one send get three answers", a.take_all(),
          ["GAME_LIST 0", "GAME_LIST 0", "ERROR - UNKNOWN_COMMAND"])

    # numbers (§1.3): decimal integers, no '+', no leading zeros, and they must fit
    # in an int. JOIN_GAME tells them apart: a number is a room that does not exist
    # (NOT_FOUND), anything else is BAD_ARGS.
    for n in ("0", "1", "-1", "255", "2147483647", "-2147483648"):
        check(f"a number: {n}", a.ask(f"JOIN_GAME {n}", "ERROR"), "ERROR JOIN_GAME NOT_FOUND")
    for n in ("+1", "01", "007", "00", "-0", "-01", "+", "-", "--1", "1.5", "0x10", "1e3",
              "2147483648", "-2147483649", "4294967296", "99999999999999999999"):
        check(f"not a number: {n}", a.ask(f"JOIN_GAME {n}", "ERROR"), "ERROR JOIN_GAME BAD_ARGS")

    # a line can be 1024 bytes including the '\n'; one byte more and the server hangs up
    a.send_raw(b"X" * 1023 + b"\n")
    check("1024-byte line is accepted", a.take_all(), ["ERROR - UNKNOWN_COMMAND"])
    check("...and the connection stays open", a.closed, False)
    a.send_raw(b"X" * 1024 + b"\n")
    check("1025-byte line: the server closes the connection", a.closed, True)
    check("...without saying anything", a.take_all(), [])

    # it is only that client that is thrown out
    b = srv.client("Bruno")
    check("the server is still up for others", b.ask("LIST_GAMES", "GAME_LIST"), "GAME_LIST 0")

    # a client that never named itself is thrown out too
    c = srv.client()
    c.send_raw(b"Y" * 2000)
    check("too long a line, unnamed client: closed", c.closed, True)

    # many clients coming and going do not disturb the server
    for _ in range(40):
        srv.client().close()
    d = srv.client()
    check("still greets new clients", bool(re.fullmatch(r"WELCOME \d+", d.lines[0])) if d.lines else False, True)

finish("test_basics")
