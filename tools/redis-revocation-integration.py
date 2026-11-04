#!/usr/bin/env python3
"""Small live Redis + backend + Netty authorization-revocation integration check."""

import argparse
import http.cookiejar
import json
import os
import queue
import struct
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

import websocket


class SocketClient:
    def __init__(self, url, cookie):
        self.ws = websocket.create_connection(
            url, timeout=1, origin="http://localhost:5173", header=[f"Cookie: {cookie}"]
        )
        self.events = queue.Queue()
        self.closed = threading.Event()
        self.close_frame_seen = threading.Event()
        self.close_code = None
        self.close_reason = None
        self.receive_error = None
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        while True:
            try:
                opcode, data = self.ws.recv_data(control_frame=True)
            except websocket.WebSocketTimeoutException:
                continue
            except websocket.WebSocketConnectionClosedException:
                self.receive_error = "socket closed without a close frame"
                self.closed.set()
                return
            except websocket.WebSocketException as error:
                self.receive_error = type(error).__name__
                self.closed.set()
                return
            except Exception as error:
                self.receive_error = type(error).__name__
                self.closed.set()
                return
            if opcode == websocket.ABNF.OPCODE_CLOSE:
                try:
                    payload = data if isinstance(data, bytes) else bytes(data)
                    if len(payload) < 2:
                        raise ValueError("close frame has no status code")
                    self.close_code = struct.unpack("!H", payload[:2])[0]
                    self.close_reason = payload[2:].decode("utf-8", errors="strict")
                except Exception as error:
                    self.receive_error = "invalid close frame: " + type(error).__name__
                self.close_frame_seen.set()
                self.closed.set()
                return
            if opcode == websocket.ABNF.OPCODE_PING:
                self.ws.pong(data)
                continue
            if opcode != websocket.ABNF.OPCODE_TEXT:
                self.receive_error = f"unexpected websocket opcode {opcode}"
                self.closed.set()
                return
            try:
                decoded = data.decode("utf-8", errors="strict") if isinstance(data, bytes) else data
                message = json.loads(decoded)
                if not isinstance(message, dict):
                    raise ValueError("message envelope is not an object")
                self.events.put(message)
            except Exception as error:
                self.receive_error = "invalid server JSON: " + type(error).__name__
                return

    def send(self, op, **fields):
        self.ws.send(json.dumps({"op": op, **fields}, separators=(",", ":")))

    def subscribe(self, destination):
        self.send("SUBSCRIBE", id="integration-" + uuid.uuid4().hex, destination=destination)

    def publish(self, destination, body):
        self.send("SEND", destination=destination, body=body)

    def wait_message(self, destination, predicate, timeout=8):
        deadline = time.monotonic() + timeout
        pending = []
        found = None
        while time.monotonic() < deadline:
            try:
                message = self.events.get(timeout=min(0.1, max(0.001, deadline - time.monotonic())))
            except queue.Empty:
                if self.receive_error:
                    raise AssertionError(f"socket receive failed: {self.receive_error}")
                if self.closed.is_set():
                    break
                continue
            if message.get("op") == "MESSAGE" and message.get("destination") == destination:
                raw = message.get("body")
                body = json.loads(raw) if isinstance(raw, str) else raw
                if predicate(body):
                    found = body
                    break
            pending.append(message)
        for message in pending:
            self.events.put(message)
        if found is None:
            raise AssertionError("expected room message was not received")
        return found

    def wait_closed(self, expected_code, expected_reason, timeout=5):
        if not self.closed.wait(timeout):
            raise AssertionError("socket did not close before deadline")
        if self.receive_error:
            raise AssertionError(f"socket receive failed: {self.receive_error}")
        if not self.close_frame_seen.is_set():
            raise AssertionError("socket transport ended without a WebSocket close frame")
        if self.close_code != expected_code or self.close_reason != expected_reason:
            raise AssertionError("close code or reason did not match expected authorization result")

    def close(self):
        self.closed.set()
        try:
            self.ws.close()
        except Exception:
            pass


def login(base_url, email, password):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/auth",
        data=json.dumps({"email": email, "password": password}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with opener.open(request, timeout=15) as response:
            if response.status not in (200, 204):
                raise RuntimeError("login rejected")
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"login rejected with HTTP {error.code}") from None
    cookie = "; ".join(f"{cookie.name}={cookie.value}" for cookie in jar)
    if not cookie:
        raise RuntimeError("login returned no cookie")
    return cookie


def leave_room(base_url, cookie, wooms_id):
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/wooms/{wooms_id}/users",
        headers={"Cookie": cookie, "Accept": "application/json"},
        method="DELETE",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.status != 200:
                raise RuntimeError(f"leave request failed with HTTP {response.status}")
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"leave request failed with HTTP {error.code}") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://127.0.0.1:18081")
    parser.add_argument("--socket-url", default="ws://127.0.0.1:18091/ws")
    parser.add_argument("--room-id", default="00000000-0000-0000-0000-000000000001")
    parser.add_argument("--wooms-id", type=int, default=1)
    args = parser.parse_args()
    password = os.environ.get("WOOMS_TEST_PASSWORD")
    if not password:
        parser.error("WOOMS_TEST_PASSWORD is required")

    clients = []
    stage = "login"
    try:
        cookie_a = login(args.backend_url, "admin@wooms.com", password)
        cookie_b = login(args.backend_url, "admin2@wooms.com", password)
        a = SocketClient(args.socket_url, cookie_a)
        clients.append(a)
        b = SocketClient(args.socket_url, cookie_b)
        clients.append(b)

        chat_topic = f"/ws/wooms/chat/{args.room_id}"
        send_topic = f"/ws/send/chat/{args.room_id}"
        a.subscribe(chat_topic)
        b.subscribe(chat_topic)
        probe = "before-revoke-" + uuid.uuid4().hex
        a.publish(send_topic, {"content": probe})
        b.wait_message(chat_topic, lambda value: value.get("content") == probe)

        stage = "leave and Redis delivery"
        leave_room(args.backend_url, cookie_b, args.wooms_id)
        b.wait_closed(4403, "room access revoked", timeout=5)

        stage = "fresh authorization after leave"
        b_fresh = SocketClient(args.socket_url, cookie_b)
        clients.append(b_fresh)
        b_fresh.subscribe(chat_topic)
        b_fresh.wait_closed(4403, "authorization denied", timeout=5)

        stage = "unrelated connection remains active"
        after = "survivor-" + uuid.uuid4().hex
        a.publish(send_topic, {"content": after})
        a.wait_message(chat_topic, lambda value: value.get("content") == after)
        if not a.ws.connected:
            raise AssertionError("unrelated user connection was closed")

        print(json.dumps({
            "result": "passed",
            "checks": [
                "both users authorized and subscribed",
                "backend ACCEPT-to-REFUSE leave reached Netty through Redis",
                "Redis reason produced close code 4403 and reason room access revoked within five seconds",
                "fresh socket authorization after leave was denied by backend",
                "different user connection remained usable",
            ],
        }, separators=(",", ":")))
        return 0
    except Exception as error:
        print(json.dumps({"result": "failed", "stage": stage,
                          "errorType": type(error).__name__}, separators=(",", ":")), file=sys.stderr)
        return 1
    finally:
        for client in clients:
            client.close()


if __name__ == "__main__":
    raise SystemExit(main())
