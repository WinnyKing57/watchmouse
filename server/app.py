"""WatchMouse report receiver: stores POST /log payloads in SQLite.

Endpoints:
  POST /log            JSON from the watch; returns {"id": <rowid>}
  GET  /               HTML list of the latest reports
  GET  /reports/<id>   Single report with its logcat
"""

import html
import json
import os
import re
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

DB_PATH = os.environ.get("REPORT_DB", "/data/reports.db")
MAX_BODY = int(os.environ.get("MAX_BODY", "600000"))
RATE_LIMIT = float(os.environ.get("RATE_LIMIT_SECONDS", "10"))

_rate = {}


def connect():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS reports ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT,"
        "created INTEGER,"
        "client_id TEXT,"
        "version TEXT,"
        "version_code INTEGER,"
        "manufacturer TEXT,"
        "model TEXT,"
        "sdk INTEGER,"
        "build TEXT,"
        "battery INTEGER,"
        "logs TEXT)"
    )
    conn.commit()
    return conn


def rate_limited(ip):
    now = time.time()
    last = _rate.get(ip, 0)
    if now - last < RATE_LIMIT:
        return True
    _rate[ip] = now
    return False


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _html(self, code, text):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        conn = connect()
        try:
            path = urlparse(self.path).path
            if path in ("/", "/reports"):
                rows = conn.execute(
                    "SELECT id, created, client_id, version, model, battery "
                    "FROM reports ORDER BY id DESC LIMIT 200"
                ).fetchall()
                items = "".join(
                    "<li><a href='/reports/%d'>#%d</a> %d %s v%s bat %d%%</li>"
                    % (r[0], r[0], r[1], html.escape(r[4] or ""), html.escape(r[3] or ""), r[5] or -1)
                    for r in rows
                )
                self._html(200, "<h1>WatchMouse reports</h1><ul>%s</ul>" % (items or "<li>none</li>"))
                return
            match = re.fullmatch(r"/reports/(\d+)", path)
            if match:
                row = conn.execute(
                    "SELECT * FROM reports WHERE id=?", (int(match.group(1)),)
                ).fetchone()
                if row:
                    keys = [
                        "id",
                        "created",
                        "client_id",
                        "version",
                        "version_code",
                        "manufacturer",
                        "model",
                        "sdk",
                        "build",
                        "battery",
                        "logs",
                    ]
                    data = dict(zip(keys, row))
                    info = {k: v for k, v in data.items() if k != "logs"}
                    self._html(
                        200,
                        "<h1>Report #%d</h1><pre>%s</pre><h2>Logs</h2><pre>%s</pre>"
                        % (data["id"], html.escape(json.dumps(info, indent=2)), html.escape(data["logs"] or "")),
                    )
                    return
            self._html(404, "not found")
        finally:
            conn.close()

    def do_POST(self):
        if urlparse(self.path).path != "/log":
            self._json(404, {"error": "not found"})
            return
        if rate_limited(self.client_address[0]):
            self._json(429, {"error": "rate limited"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
            self._json(400, {"error": "bad content length"})
            return
        try:
            data = json.loads(self.rfile.read(length))
        except Exception:
            self._json(400, {"error": "invalid json"})
            return
        if not isinstance(data, dict):
            self._json(400, {"error": "invalid payload"})
            return
        conn = connect()
        try:
            cur = conn.execute(
                "INSERT INTO reports "
                "(created, client_id, version, version_code, manufacturer, model, sdk, build, battery, logs) "
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    int(time.time()),
                    str(data.get("client_id", ""))[:64],
                    str(data.get("version", ""))[:16],
                    data.get("version_code"),
                    str(data.get("manufacturer", ""))[:64],
                    str(data.get("model", ""))[:64],
                    data.get("sdk"),
                    str(data.get("build", ""))[:32],
                    data.get("battery"),
                    str(data.get("logs", ""))[:MAX_BODY - 64],
                ),
            )
            conn.commit()
            self._json(201, {"id": cur.lastrowid})
        finally:
            conn.close()


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    print("report receiver on port %d, db %s" % (port, DB_PATH))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()