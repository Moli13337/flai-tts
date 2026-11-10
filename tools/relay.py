import socket
import threading

UPSTREAM = ("54.254.158.121", 8899)
LISTEN = ("0.0.0.0", 8899)


def pump(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d:
                break
            b.sendall(d)
    except Exception:
        pass
    try:
        b.shutdown(socket.SHUT_WR)
    except Exception:
        pass


def handle(cli):
    try:
        print("conn in", flush=True)
        up = socket.create_connection(UPSTREAM, timeout=30)
        up.settimeout(300)
        print("upstream ok", flush=True)
        threading.Thread(target=pump, args=(cli, up), daemon=True).start()
        pump(up, cli)
    except Exception as e:
        print("relay err:", e, flush=True)


srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(LISTEN)
srv.listen(16)
print("relay listening", LISTEN, "->", UPSTREAM, flush=True)
while True:
    c, addr = srv.accept()
    c.settimeout(300)
    threading.Thread(target=handle, args=(c,), daemon=True).start()
