"""Stopping the server with SIGTERM (what `docker stop` sends) and SIGINT (Ctrl-C)."""
import signal
import subprocess

from harness import Server, check, finish


def stop_with(sig):
    """Starts a server, connects a client, sends 'sig' to the server. Returns
    the server's exit status (or a message if it is still running after 2
    seconds) and whether the client saw its connection closed."""
    with Server() as srv:
        c = srv.client("Anna")
        srv.proc.send_signal(sig)
        try:
            status = srv.proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            status = "still running after 2 seconds"
        c.pump()
        return status, c.closed


# Without a handler the default action of these signals kills the process with
# status -15 / -2 (minus the signal number), and if the server is PID 1 in a
# container it does not even do that: the signal is ignored. A handler makes the
# server leave by itself, with status 0.
for name, sig in (("SIGTERM", signal.SIGTERM), ("SIGINT", signal.SIGINT)):
    status, closed = stop_with(sig)
    check(f"{name}: the server exits by itself with status 0", status, 0)
    check(f"{name}: ...and the connected client sees the connection closed", closed, True)

# Each call above started a server right after another one was stopped while it
# had a client connected: that only works with SO_REUSEADDR (TIME_WAIT on the port).

finish("test_shutdown")
