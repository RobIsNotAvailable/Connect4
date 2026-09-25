"""A client that stops reading must not freeze the others. The server sends
with a timeout and drops a client it cannot write to (client_send_line in
src/client_registry.c), instead of leaving every thread that has to notify
it stuck for good."""
import socket
import threading
import time

from harness import HOST, PORT, Server, check, finish

# What the server waits before giving up on a client, plus some slack.
GIVE_UP_WITHIN = 12
BURST = 30000  # create+delete cycles: each sends a couple of lines to everyone


class Peer:
    """A raw connection that has chosen a username. With reading=True a thread
    keeps reading everything the server sends (collected in .data)."""

    def __init__(self, name, reading=True, rcvbuf=None):
        self.s = socket.socket()
        if rcvbuf:
            # Set before connect(): a tiny fixed window, so that the server's
            # data piles up on its side after a few kilobytes.
            self.s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
        self.s.connect((HOST, PORT))
        self.s.sendall(f"SET_USERNAME {name}\n".encode())
        self.data = b""
        if reading:
            threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            while True:
                chunk = self.s.recv(65536)
                if not chunk:
                    return
                self.data += chunk
        except OSError:
            pass

    def wait_for(self, text, seconds):
        deadline = time.time() + seconds
        while time.time() < deadline:
            if text.encode() in self.data:
                return True
            time.sleep(0.05)
        return False

    def hung_up_within(self, seconds):
        """True once the server has closed the connection (after we read what
        it had queued for us)."""
        self.s.settimeout(0.2)
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                if not self.s.recv(65536):
                    return True
            except socket.timeout:
                pass
            except OSError:
                return True
        return False


with Server() as srv:
    stuck = Peer("Stuck", reading=False, rcvbuf=1024)
    busy = Peer("Busy")
    other = Peer("Other")
    time.sleep(0.3)

    # Busy creates and deletes a room again and again; each time the server
    # tells everyone else. Stuck never reads, so its buffers fill up.
    burst = ("CREATE_GAME a\nLEAVE_GAME 1\n" * BURST).encode()
    threading.Thread(target=lambda: busy.s.sendall(burst), daemon=True).start()
    time.sleep(1.5)

    # Other now creates a room too: its thread has to tell Stuck as well.
    # Its next command must still be answered.
    other.s.sendall(b"CREATE_GAME y\nLIST_GAMES\n")
    check("Other's GAME_CREATED", other.wait_for("GAME_CREATED", 2), True)
    check("Other's next command is answered", other.wait_for("GAME_LIST", GIVE_UP_WITHIN), True)

    check("Stuck was dropped", stuck.hung_up_within(GIVE_UP_WITHIN), True)

    # ...and nobody else was
    other.data = b""
    other.s.sendall(b"LIST_MY_GAMES\n")
    check("Other is still served", other.wait_for("MY_GAME_LIST", 2), True)
    check("Stuck's name is free again", srv.client().ask("SET_USERNAME Stuck", "USERNAME_SET"), "USERNAME_SET Stuck")

    for p in (stuck, busy, other):
        p.s.close()

finish("test_stuck_client")
