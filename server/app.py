"""WatchMouse report receiver: stores POST /log payloads in SQLite.

Endpoints:
  POST /log                 JSON from the watch (open, rate-limited)
  GET  /                    HTML admin list (Basic Auth)
  GET  /reports/<id>        Single report logcat (Basic Auth)
  POST /reports/<id>/delete Removes a report (Basic Auth)

Enable protection with REPORT_USER + REPORT_PASSWORD env vars.
"""

import base64
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
AUTH_USER = os.environ.get("REPORT_USER", "")
AUTH_PASSWORD = os.environ.get("REPORT_PASSWORD", "")

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


class ReportView:
    """Helper used by subclasses to share response logic."""

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

    def _page(self, title, content):
        self._html(
            200,
            "<!doctype html><html><head><meta charset='utf-8'>"
            "<title>%s</title>"
            "<style>body{font-family:system-ui,sans-serif;margin:2em;color:#ddd;background:#14161a}"
            "a{color:#6cf}table{border-collapse:collapse}th,td{border:1px solid #333;padding:.4em .8em;"
            "font-size:.9em}pre{background:#0d0f12;padding:1em;overflow:auto}</style>"
            "</head><body>%s</body></html>" % (html.escape(title), content),
        )

    def check_auth(self):
        if not AUTH_USER:
            return True
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(header[6:]).decode("utf-8")
        except Exception:
            return False
        user, _, password = decoded.partition(":")
        return user == AUTH_USER and password == AUTH_PASSWORD

    def deny_auth(self):
        self.send_response(401)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("WWW-Authenticate", 'Basic realm="WatchMouse reports"')
        self.send_header("Content-Length", "0")
        self.end_headers()


class Handler(ReportView, BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        if not self.check_auth():
            self.deny_auth()
            return
        conn = connect()
        try:
            path = urlparse(self.path).path
            if path in ("/", "/reports"):
                rows = conn.execute(
                    "SELECT id, created, client_id, version, model, battery, manufacturer "
                    "FROM reports ORDER BY id DESC LIMIT 200"
                ).fetchall()
                total = conn.execute("SELECT COUNT(*) FROM reports").fetchone()[0]
                items = "".join(
                    "<tr><td><a href='/reports/%d'>#%d</a></td><td>%s</td><td>%s</td>"
                    "<td>v%s</td><td>%s</td><td>%d%%</td></tr>"
                    % (
                        r[0],
                        r[0],
                        time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r[1])),
                        html.escape((r[3] or "")[:32]),
                        html.escape(r[4] or ""),
                        html.escape(r[6] or ""),
                        r[5] if r[5] is not None else -1,
                    )
                    for r in rows
                )
                content = (
                    "<h1>WatchMouse reports</h1>"
                    "<p>%d reports — last 200</p>"
                    "<table><tr><th>ID</th><th>When</th><th>Client</th>"
                    "<th>Version</th><th>Device</th><th>Battery</th></tr>%s</table>"
                    % (total, items or "<tr><td colspan='6'>none</td></tr>")
                )
                self._page("WatchMouse reports", content)
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
                    content = (
                        "<h1>Report #%d</h1><p><a href='/'>← back to list</a></p>"
                        "<pre>%s</pre><h2>Logs</h2><pre>%s</pre>"
                        "<form method='post' action='/reports/%d/delete'>"
                        "<button onclick=\"return confirm('Delete this report?')\">Delete</button></form>"
                        % (
                            data["id"],
                            html.escape(json.dumps(info, indent=2)),
                            html.escape(data["logs"] or ""),
                            data["id"],
                        )
                    )
                    self._page("Report #%d" % data["id"], content)
                    return
            self._html(404, "not found")
        finally:
            conn.close()

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/log":
            self.post_log()
            return
        match = re.fullmatch(r"/reports/(\d+)/delete", path)
        if match and self.check_auth():
            conn = connect()
            try:
                conn.execute("DELETE FROM reports WHERE id=?", (int(match.group(1)),))
                conn.commit()
            finally:
                conn.close()
            self.send_response(302)
            self.send_header("Location", "/")
            self.end_headers()
            return
        self._json(404, {"error": "not found"})

    def post_log(self):
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