"""Stress test, NOT run by `make test` (it takes minutes): both players of a
game hang up at the same instant, many times, while an observer watches the
lobby. Afterwards the observer must believe the room is gone.

    python3 tests/stress_disconnect_race.py [iterations]     (default 1500)

What it looks for: without the server's command_mutex, one hang-up applied
first (room goes back to WAITING, NEW_GAME to be sent) and the other second
(room deleted, GAME_CLOSED sent), but the notifications could go out in the
opposite order, leaving a "ghost" room in the lobby: GAME_CLOSED followed by
NEW_GAME. It happened about once in 1500 rounds, so a normal test would not
catch it. To see the check work, put a usleep(3000) in notify_leave_events
right before the NEW_GAME broadcast and take the lock out: nearly every round
fails; with the lock in, none does.
"""
import socket
import sys
import threading
import time

from harness import HOST, PORT, Server


def connect(name):
    s = socket.create_connection((HOST, PORT))
    s.sendall(f"SET_USERNAME {name}\n".encode())
    return s


def read_until(s, text, timeout=2):
    s.settimeout(timeout)
    buf = b""
    while text.encode() not in buf:
        buf += s.recv(4096)
    return buf.decode()


def main(rounds):
    ghosts = 0
    seen = []  # every line the observer receives

    with Server():
        observer = connect("Obs")

        def watch():
            buf = b""
            while True:
                try:
                    data = observer.recv(65536)
                except OSError:
                    return
                if not data:
                    return
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    seen.append(line.decode())

        threading.Thread(target=watch, daemon=True).start()

        for i in range(rounds):
            a, b = connect(f"A{i}"), connect(f"B{i}")
            read_until(a, "USERNAME_SET")
            read_until(b, "USERNAME_SET")
            a.sendall(f"CREATE_GAME G{i}\n".encode())
            room = read_until(a, "GAME_CREATED").split("GAME_CREATED ")[1].split()[0]
            b.sendall(f"JOIN_GAME {room}\n".encode())
            read_until(a, "JOIN_NOTIFY")
            a.sendall(f"JOIN_RESPONSE {room} 1\n".encode())
            read_until(b, "GAME_START")
            time.sleep(0.01)
            del seen[:]

            barrier = threading.Barrier(2)

            def hang_up(sock):
                barrier.wait()
                sock.close()

            threads = [threading.Thread(target=hang_up, args=(s,)) for s in (a, b)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
            time.sleep(0.05)

            about_room = [l for l in seen if l.split()[0] in ("NEW_GAME", "GAME_CLOSED") and l.split()[1] == room]
            if not about_room or about_room[-1].startswith("NEW_GAME"):
                ghosts += 1
                print(f"round {i}: ghost room {room}, the observer saw {about_room}")

        observer.close()

    print(f"{ghosts} ghost rooms out of {rounds}")
    sys.exit(1 if ghosts else 0)


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 1500)
