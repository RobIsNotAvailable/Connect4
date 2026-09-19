"""Shared pieces of the end-to-end tests: start the real server, talk to it
over sockets, count passed and failed checks.

Every test file starts its own server, so a test never sees the rooms or
usernames of another one. Run one with `python3 tests/test_rematch.py`, or
all of them with `make test` (the server must be built first: `make`).
"""
import os
import re
import socket
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER_BIN = os.path.join(ROOT, "bin", "server")
HOST = "127.0.0.1"


def _server_port():
    # Read from the header the server is compiled with, so the tests follow
    # it if PORT ever changes.
    with open(os.path.join(ROOT, "include", "protocol.h")) as f:
        return int(re.search(r"#define\s+PORT\s+(\d+)", f.read()).group(1))


PORT = _server_port()

_passed = 0
_failed = 0


class Client:
    """One connection to the server. Lines the server sends are collected
    and handed out with take()/take_all(), so a test can check exactly what
    arrived - and that nothing else did."""

    def __init__(self):
        self.s = socket.create_connection((HOST, PORT))
        self.s.settimeout(0.15)
        self.buf = b""
        self.lines = []
        self.closed = False  # True once the server has closed the connection
        self.pump()

    def pump(self):
        """Reads whatever the server has sent so far (waits ~0.15 s for more)."""
        try:
            while True:
                d = self.s.recv(4096)
                if not d:
                    self.closed = True
                    break
                self.buf += d
        except socket.timeout:
            pass
        except ConnectionResetError:
            self.closed = True
        while b"\n" in self.buf:
            line, self.buf = self.buf.split(b"\n", 1)
            self.lines.append(line.decode())

    def send(self, line):
        self.s.sendall((line + "\n").encode())
        time.sleep(0.03)
        self.pump()

    def send_raw(self, data):
        """Sends exactly these bytes (no '\\n' added), for what send() cannot
        express: '\\r\\n', half a line, several lines at once, non-ASCII."""
        self.s.sendall(data)
        time.sleep(0.03)
        self.pump()

    def take(self, prefix):
        """Removes and returns the first pending line starting with 'prefix', or None."""
        self.pump()
        for i, l in enumerate(self.lines):
            if l.startswith(prefix):
                return self.lines.pop(i)
        return None

    def take_all(self):
        """Removes and returns every pending line."""
        self.pump()
        out, self.lines = self.lines, []
        return out

    def ask(self, cmd, reply_prefix):
        """Sends 'cmd' and returns the reply starting with 'reply_prefix' (or None)."""
        self.send(cmd)
        return self.take(reply_prefix)

    def close(self):
        self.s.close()


class Server:
    """The real bin/server, started for the duration of a `with` block."""

    def __enter__(self):
        if not os.path.exists(SERVER_BIN):
            sys.exit("bin/server not found: run `make` first")

        try:
            socket.create_connection((HOST, PORT), timeout=0.5).close()
        except OSError:
            pass
        else:
            sys.exit("something is already listening on port %d" % PORT)

        self.clients = []
        self.proc = subprocess.Popen([SERVER_BIN], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        deadline = time.time() + 3
        while True:
            if self.proc.poll() is not None:
                sys.exit("the server exited at startup (port %d busy?)" % PORT)
            try:
                socket.create_connection((HOST, PORT), timeout=0.2).close()
                return self
            except OSError:
                if time.time() > deadline:
                    self.proc.kill()
                    sys.exit("the server did not start listening on port %d" % PORT)
                time.sleep(0.05)

    def client(self, name=None):
        """A new connection. With a 'name' it has already chosen that username
        and nothing is left unread; without one it is exactly as the server
        greets a new client (WELCOME pending, no username)."""
        c = Client()
        self.clients.append(c)
        if name is not None:
            c.send("SET_USERNAME " + name)
            assert c.take_all()[-1:] == ["USERNAME_SET " + name], "could not set username " + name
        return c

    def __exit__(self, *exc):
        # The clients hang up first. Whoever closes a connection first is left
        # holding it in TIME_WAIT: if that were the server, its port would
        # stay busy for about a minute and the next test could not start
        # (the server does not set SO_REUSEADDR).
        for c in self.clients:
            c.close()
        self.proc.terminate()
        self.proc.wait()


EMPTY_BOARD = "." * 42


def start_game(owner, joiner, room):
    """The joiner asks, the owner accepts: the game starts."""
    joiner.send(f"JOIN_GAME {room}")
    owner.send(f"JOIN_RESPONSE {room} 1")


def play_win(owner, other, room):
    """The owner (player 1) wins with a vertical four in column 0. The room
    must be PLAYING and it must be the owner's turn. Returns both GAME_OVER
    lines (owner's, other's)."""
    for _ in range(3):
        owner.send(f"MOVE {room} 0")
        other.send(f"MOVE {room} 1")
    owner.send(f"MOVE {room} 0")
    return owner.take("GAME_OVER"), other.take("GAME_OVER")


def check(label, got, want):
    global _passed, _failed
    if got == want:
        _passed += 1
        print("PASS " + label)
    else:
        _failed += 1
        print("FAIL " + label)
        print("     got : %r" % (got,))
        print("     want: %r" % (want,))


def finish(name):
    """Prints the summary and exits with 0 only if every check passed."""
    print("\n%s: %d passed, %d failed" % (name, _passed, _failed))
    sys.exit(1 if _failed else 0)
