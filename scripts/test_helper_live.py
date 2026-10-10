"""Bounded helper-only network smoke test. No Minecraft world or existing process is touched."""
import argparse
from collections import deque
import json
from pathlib import Path
import queue
import socket
import subprocess
import threading
import time
import os
import re


class Helper:
    def __init__(self, executable, args):
        relay_only = os.environ.get("MCLINK_TEST_RELAY_ONLY", "1") == "1"
        env = dict(os.environ)
        env.pop("TS_DEBUG_ALWAYS_USE_DERP", None)
        if os.environ.get("MCLINK_TEST_LEGACY_ENV") == "1":
            env["TS_DEBUG_ALWAYS_USE_DERP"] = "1"
        else:
            args = [*args, "--relay-only=" + str(relay_only).lower()]
        self.process = subprocess.Popen([str(executable), *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, encoding="utf-8",
                                        env=env)
        self.events = queue.Queue()
        self.recent = deque(maxlen=64)
        self.logs = deque(maxlen=64)
        threading.Thread(target=self.read_events, daemon=True).start()
        threading.Thread(target=self.read_logs, daemon=True).start()

    def read_logs(self):
        for line in self.process.stderr:
            safe = re.sub(r"(?i)mcl1_[a-z0-9_=-]+|tc[a-z0-9_=-]{16,}|(?:privkey|nodekey|privatekey|publickey)[=:][^\s,]+|[a-f0-9]{40,}", "[redacted]", line)
            self.logs.append(safe[:512].strip())

    def read_events(self):
        for line in self.process.stdout:
            try:
                event = json.loads(line)
            except ValueError:
                self.events.put({"type": "error", "code": "bad_json"})
                continue
            self.events.put(event)

    def summary(self):
        events = list(self.recent)
        while True:
            try:
                events.append(self.events.get_nowait())
            except queue.Empty:
                break
        return [{key: event[key] for key in ("type", "code", "stage", "transport") if key in event}
                for event in events[-12:]]

    def wait(self, predicate, timeout=30):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                event = self.events.get(timeout=min(1, end - time.monotonic()))
            except queue.Empty:
                if self.process.poll() is not None:
                    raise RuntimeError("helper exited before expected event")
                continue
            self.recent.append(event)
            if event.get("type") == "error":
                raise RuntimeError("helper error code: " + event.get("code", "unknown"))
            if predicate(event):
                return event
        raise TimeoutError("expected helper event was not received")

    def close(self):
        try:
            self.process.stdin.close()
            self.process.wait(timeout=8)
        except (OSError, subprocess.TimeoutExpired):
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)


class EchoBackend:
    def __init__(self):
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        self.active = True
        self.connections = []
        self.lock = threading.Lock()
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while self.active:
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self.lock:
                self.connections.append(connection)
            threading.Thread(target=self.echo, args=(connection,), daemon=True).start()

    def echo(self, connection):
        try:
            while self.active:
                data = connection.recv(4096)
                if not data:
                    return
                connection.sendall(data)
        except OSError:
            pass
        finally:
            connection.close()

    def close(self):
        self.active = False
        self.listener.close()
        with self.lock:
            for connection in self.connections:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()


def connect_local(event):
    host, port = event["address"].rsplit(":", 1)
    return socket.create_connection((host, int(port)), timeout=25)


def receive_exact(connection, size):
    result = bytearray()
    while len(result) < size:
        data = connection.recv(size - len(result))
        if not data:
            raise RuntimeError("tunnel closed before complete echo")
        result.extend(data)
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--protocol", choices=("legacy", "1", "2"), default="2")
    options = parser.parse_args()
    helpers, sockets = [], []
    backend = EchoBackend()
    protocol_args = [] if options.protocol == "legacy" else ["--protocol", options.protocol]
    try:
        host = Helper(options.helper, ["host", "--target", f"127.0.0.1:{backend.port}", "--max-connections", "2", *protocol_args])
        helpers.append(host)
        invitation = host.wait(lambda event: event.get("type") == "ready")["invite"]
        clients = []
        for index in range(2):
            client = Helper(options.helper, ["join", "--invite", invitation, *protocol_args])
            helpers.append(client)
            ready = client.wait(lambda event: event.get("type") == "ready")
            local = connect_local(ready)
            sockets.append(local)
            payload = f"tailcat-smoke-{index}".encode()
            local.sendall(payload)
            if receive_exact(local, len(payload)) != payload:
                raise RuntimeError("echo through tunnel did not match")
            if options.protocol == "2":
                client.wait(lambda event: event.get("transport") == "relay" and event.get("latencyMs") is not None, timeout=15)
            clients.append((client, ready))
        print("PASS: two simultaneous relay tunnels, structured route status and echo", flush=True)
        if options.protocol != "2":
            print("PASS: legacy event protocol", flush=True)
            return
        extra = connect_local(clients[0][1])
        sockets.append(extra)
        extra.sendall(b"capacity-probe")
        clients[0][0].wait(lambda event: event.get("code") == "capacity", timeout=15)
        print("PASS: TCP resource capacity is reported separately", flush=True)
        for local in sockets:
            local.close()
        sockets.clear()
        time.sleep(1)
        backend.close()
        unavailable = connect_local(clients[1][1])
        sockets.append(unavailable)
        unavailable.sendall(b"backend-probe")
        clients[1][0].wait(lambda event: event.get("code") == "backend_unreachable", timeout=15)
        print("PASS: stopped backend has backend_unreachable diagnosis", flush=True)
        host.close()
        print("PASS: owned host terminated through stdin EOF", flush=True)
    except Exception:
        for index, helper in enumerate(helpers):
            print(f"Owned helper {index} event summary: {helper.summary()}", flush=True)
            print(f"Owned helper {index} last redacted log lines: {list(helper.logs)[-8:]}", flush=True)
        raise
    finally:
        for local in sockets:
            local.close()
        backend.close()
        for helper in reversed(helpers):
            helper.close()


if __name__ == "__main__":
    main()
