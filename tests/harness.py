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
        self.pump()

    def pump(self):
        """Reads whatever the server has sent so far (waits ~0.15 s for more)."""
        try:
            while True:
                d = self.s.recv(4096)
                if not d:
                    break
                self.buf += d
        except socket.timeout:
            pass
        while b"\n" in self.buf:
            line, self.buf = self.buf.split(b"\n", 1)
            self.lines.append(line.decode())

    def send(self, line):
        self.s.sendall((line + "\n").encode())
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

    def client(self):
        c = Client()
        self.clients.append(c)
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
