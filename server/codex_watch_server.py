from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
from dataclasses import asdict
from datetime import datetime, timedelta
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from codex_usage_reader import CodexUsageReader


APP_NAME = "Codex Watch Server"
DEFAULT_PORT = 8765
DEFAULT_SYNC_MAX_AGE_SECONDS = 300


def load_codexbar_module(path: Path):
    module_path = path / "codexbar_safe.py"
    if not module_path.exists():
        raise FileNotFoundError(
            "codexbar_safe.py not found. Set CODEXBAR_SAFE_PATH or pass "
            f"--codexbar-path. Checked: {module_path}"
        )

    spec = importlib.util.spec_from_file_location("codexbar_safe_external", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load module spec: {module_path}")

    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def token_total(bucket: Any) -> int:
    value = getattr(bucket, "total", None)
    if isinstance(value, int):
        return value
    reported = int(getattr(bucket, "reported_total_tokens", 0) or 0)
    if reported:
        return reported
    return int(getattr(bucket, "input_tokens", 0) or 0) + int(getattr(bucket, "cached_input_tokens", 0) or 0) + int(getattr(bucket, "output_tokens", 0) or 0)


def compact_number(value: int) -> str:
    value = int(value or 0)
    if value >= 1_000_000:
        return f"{value / 1_000_000:.1f}M"
    if value >= 1_000:
        return f"{value / 1_000:.0f}K"
    return str(value)


def status_from_remaining(remaining: int | None, forced: str | None = None) -> str:
    if forced in {"ready", "working", "caution", "low"}:
        return forced
    if remaining is None:
        return "unknown"
    if remaining < 15:
        return "low"
    if remaining < 35:
        return "caution"
    return "ready"


def pet_state_for_status(status: str) -> str:
    return {
        "ready": "idle",
        "working": "running",
        "caution": "review",
        "low": "failed",
    }.get(status, "idle")


def make_limit(window: Any) -> dict[str, Any]:
    return {
        "label": getattr(window, "label", ""),
        "available": bool(getattr(window, "available", False)),
        "remaining_percent": int(getattr(window, "remaining_percent", 0) or 0),
        "used_percent": int(getattr(window, "used_percent", 0) or 0),
        "resets_in": getattr(window, "resets_at", "unknown"),
        "window_minutes": getattr(window, "window_minutes", None),
        "stale": bool(getattr(window, "stale", False)),
        "seen_at": getattr(window, "seen_at", "unknown"),
    }


def parse_seen_at(value: Any) -> datetime | None:
    text = str(value or "").strip()
    if text in {"", "unknown", "Never", "none"}:
        return None
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S", "%H:%M:%S", "%H:%M"):
        try:
            parsed = datetime.strptime(text, fmt)
            if fmt.startswith("%H"):
                now = datetime.now()
                parsed = parsed.replace(year=now.year, month=now.month, day=now.day)
            return parsed
        except ValueError:
            continue
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            parsed = parsed.astimezone()
        return parsed.replace(tzinfo=None)
    except ValueError:
        return None


def compact_age(seconds: int | None) -> str:
    if seconds is None:
        return "unknown"
    seconds = max(0, int(seconds))
    minutes = seconds // 60
    if minutes < 1:
        return "now"
    if minutes < 60:
        return f"{minutes}m"
    hours = minutes // 60
    if hours < 24:
        return f"{hours}h{minutes % 60:02d}m"
    return f"{hours // 24}d"


def quota_freshness(snap: Any, max_age_seconds: int) -> tuple[int | None, str, bool]:
    seen_at = parse_seen_at(getattr(snap, "quota_seen_at", "unknown"))
    if seen_at is None:
        return None, "unknown", False
    age_seconds = max(0, int((datetime.now() - seen_at).total_seconds()))
    return age_seconds, compact_age(age_seconds), age_seconds <= max_age_seconds


def make_payload(reader: Any, effort: str, forced_status: str | None, max_age_seconds: int = DEFAULT_SYNC_MAX_AGE_SECONDS) -> dict[str, Any]:
    snap = reader.read()
    session = make_limit(snap.session)
    weekly = make_limit(snap.weekly)
    age_seconds, age_label, fresh = quota_freshness(snap, max_age_seconds)
    if not fresh:
        if session["available"]:
            session["stale"] = True
        if weekly["available"]:
            weekly["stale"] = True
    remaining = session["remaining_percent"] if session["available"] else None
    status = status_from_remaining(remaining, forced_status)

    today_total = token_total(snap.today)
    last_7_total = token_total(snap.last_7_days)
    last_30_total = token_total(snap.last_30_days)

    return {
        "app": "codex-watch",
        "updated": datetime.now().isoformat(timespec="seconds"),
        "source_updated": snap.updated,
        "source": snap.source,
        "quota_status": snap.quota_status if fresh else "stale",
        "available": bool(snap.available),
        "model": snap.model,
        "plan": snap.plan,
        "effort": effort,
        "status": status,
        "session": session,
        "weekly": weekly,
        "tokens": {
            "today": today_total,
            "today_label": compact_number(today_total),
            "last_7_days": last_7_total,
            "last_7_days_label": compact_number(last_7_total),
            "last_30_days": last_30_total,
            "last_30_days_label": compact_number(last_30_total),
        },
        "sync": {
            "fresh": fresh,
            "max_age_seconds": max_age_seconds,
            "source_age_seconds": age_seconds,
            "source_age_label": age_label,
            "quota_seen_at": getattr(snap, "quota_seen_at", "unknown"),
            "watch_refresh_seconds": max_age_seconds,
        },
        "pet": {
            "id": "yukino",
            "state": pet_state_for_status(status),
            "atlas_columns": 8,
            "frame_width": 192,
            "frame_height": 208,
        },
        "diagnostics": {
            "session_files": snap.session_files,
            "scanned_files": snap.scanned_files,
            "latest_session": snap.latest_session,
            "error": snap.error,
        },
    }


class CodexWatchHandler(BaseHTTPRequestHandler):
    server_version = "CodexWatchServer/0.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        if getattr(self.server, "quiet", False):
            return
        super().log_message(fmt, *args)

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_common_headers("text/plain; charset=utf-8")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if not self.authorized(parsed):
            self.write_json({"error": "unauthorized"}, HTTPStatus.UNAUTHORIZED)
            return

        if parsed.path == "/health":
            self.write_json({"ok": True, "app": APP_NAME})
            return
        if parsed.path == "/usage":
            try:
                payload = make_payload(
                    self.server.reader,
                    self.server.effort,
                    self.server.forced_status,
                    self.server.max_age_seconds,
                )
                self.write_json(payload)
            except Exception as exc:
                self.write_json({"error": str(exc)}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return

        self.write_json({"error": "not found"}, HTTPStatus.NOT_FOUND)

    def authorized(self, parsed) -> bool:
        expected = getattr(self.server, "token", "")
        if not expected:
            return True
        provided = self.headers.get("X-Codex-Watch-Token", "")
        query_token = parse_qs(parsed.query).get("token", [""])[0]
        return provided == expected or query_token == expected

    def send_common_headers(self, content_type: str) -> None:
        self.send_header("Content-Type", content_type)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "X-Codex-Watch-Token")
        self.send_header("Cache-Control", "no-store")

    def write_json(self, payload: dict[str, Any], status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_common_headers("application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=APP_NAME)
    parser.add_argument("--host", default=os.environ.get("CODEX_WATCH_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CODEX_WATCH_PORT", DEFAULT_PORT)))
    parser.add_argument("--codex-dir", default=os.environ.get("CODEX_WATCH_CODEX_DIR", ""))
    parser.add_argument("--codexbar-path", default=os.environ.get("CODEXBAR_SAFE_PATH", ""))
    parser.add_argument("--token", default=os.environ.get("CODEX_WATCH_TOKEN", ""))
    parser.add_argument("--effort", default=os.environ.get("CODEX_WATCH_EFFORT", "XHIGH"))
    parser.add_argument("--force-status", choices=["ready", "working", "caution", "low"], default=os.environ.get("CODEX_WATCH_FORCE_STATUS"))
    parser.add_argument("--max-age-seconds", type=int, default=int(os.environ.get("CODEX_WATCH_MAX_AGE_SECONDS", DEFAULT_SYNC_MAX_AGE_SECONDS)))
    parser.add_argument("--once", action="store_true", help="Print one /usage payload and exit.")
    parser.add_argument("--quiet", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.codexbar_path:
        module = load_codexbar_module(Path(args.codexbar_path).expanduser())
        reader = module.CodexUsageReader()
    else:
        reader = CodexUsageReader(args.codex_dir or None)

    if args.once:
        print(json.dumps(make_payload(reader, args.effort, args.force_status, args.max_age_seconds), ensure_ascii=False, indent=2))
        return

    httpd = ThreadingHTTPServer((args.host, args.port), CodexWatchHandler)
    httpd.reader = reader
    httpd.token = args.token
    httpd.effort = args.effort
    httpd.forced_status = args.force_status
    httpd.max_age_seconds = args.max_age_seconds
    httpd.quiet = args.quiet

    print(f"{APP_NAME} listening on http://{args.host}:{args.port}/usage")
    if args.token:
        print("Token protection enabled.")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
