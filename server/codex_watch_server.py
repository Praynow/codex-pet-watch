from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
import sys
from dataclasses import asdict
from datetime import datetime, timedelta
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from codex_usage_reader import CodexUsageReader


APP_NAME = "Codex Watch Server"
DEFAULT_PORT = 8765
DEFAULT_SYNC_MAX_AGE_SECONDS = 300
DEFAULT_UPDATE_APK_URL = "https://watch.sadjuly.xyz/downloads/codex-pet-watch.apk"


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


def parse_expiry(value: Any) -> datetime | None:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        if len(text) == 10:
            return datetime.strptime(text, "%Y-%m-%d").replace(hour=23, minute=59, second=59)
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            parsed = parsed.astimezone()
        return parsed.replace(tzinfo=None)
    except ValueError:
        return None


def make_reset_card(expires_at: Any, count: int = 1) -> dict[str, Any]:
    expiry = parse_expiry(expires_at)
    if expiry is None:
        return {
            "available": False,
            "count": 0,
            "expires_at": "unknown",
            "expires_label": "--",
            "expires_date_label": "--",
            "expires_time_label": "--",
            "expires_in": "unknown",
            "days_remaining": None,
            "expired": False,
            "urgent": False,
        }

    seconds = int((expiry - datetime.now()).total_seconds())
    expired = seconds <= 0
    days_remaining = 0 if expired else max(1, math.ceil(seconds / 86400))
    return {
        "available": True,
        "count": count,
        "expires_at": expiry.isoformat(timespec="seconds"),
        "expires_label": expiry.strftime("%m-%d %H:%M"),
        "expires_date_label": expiry.strftime("%m-%d"),
        "expires_time_label": expiry.strftime("%H:%M"),
        "expires_in": "expired" if expired else f"{days_remaining}d",
        "days_remaining": days_remaining,
        "expired": expired,
        "urgent": expired or days_remaining <= 7,
    }


def make_reset_cards(cards_json: Any, legacy_expires_at: Any = "") -> dict[str, Any]:
    raw_entries: list[Any] = []
    invalid_entries = 0
    text = str(cards_json or "").strip()
    if text:
        try:
            decoded = json.loads(text)
            if isinstance(decoded, list):
                raw_entries = decoded
            else:
                invalid_entries += 1
        except (TypeError, ValueError, json.JSONDecodeError):
            invalid_entries += 1

    if not raw_entries and legacy_expires_at:
        raw_entries = [{"expires_at": legacy_expires_at, "count": 1}]

    grouped: dict[str, dict[str, Any]] = {}
    for entry in raw_entries:
        if not isinstance(entry, dict):
            invalid_entries += 1
            continue
        expires_at = entry.get("expires_at")
        try:
            count = int(entry.get("count", 1))
        except (TypeError, ValueError):
            invalid_entries += 1
            continue
        if count <= 0 or count > 999:
            invalid_entries += 1
            continue
        card = make_reset_card(expires_at, count)
        if not card["available"]:
            invalid_entries += 1
            continue
        key = card["expires_at"]
        if key in grouped:
            grouped[key]["count"] += count
        else:
            grouped[key] = card

    cards = sorted(grouped.values(), key=lambda item: item["expires_at"])
    usable_cards = [card for card in cards if not card["expired"]]
    next_expiry = usable_cards[0] if usable_cards else (cards[0] if cards else None)
    total_count = sum(card["count"] for card in cards)
    usable_count = sum(card["count"] for card in usable_cards)
    expired_count = total_count - usable_count
    urgent_count = sum(card["count"] for card in usable_cards if card["urgent"])
    return {
        "available": bool(cards),
        "total_count": total_count,
        "usable_count": usable_count,
        "urgent_count": urgent_count,
        "expired_count": expired_count,
        "invalid_entries": invalid_entries,
        "urgent": urgent_count > 0 or expired_count > 0,
        "next_expiry": next_expiry,
        "cards": cards,
    }


def quota_freshness(snap: Any, max_age_seconds: int) -> tuple[int | None, str, bool]:
    seen_at = parse_seen_at(getattr(snap, "quota_seen_at", "unknown"))
    if seen_at is None:
        return None, "unknown", False
    age_seconds = max(0, int((datetime.now() - seen_at).total_seconds()))
    return age_seconds, compact_age(age_seconds), age_seconds <= max_age_seconds


def make_payload(
    reader: Any,
    forced_status: str | None,
    max_age_seconds: int = DEFAULT_SYNC_MAX_AGE_SECONDS,
    reset_card_expires_at: str = "",
    reset_cards_json: str = "",
) -> dict[str, Any]:
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
    reset_cards = make_reset_cards(reset_cards_json, reset_card_expires_at)
    reset_card = reset_cards["next_expiry"] or make_reset_card("")

    return {
        "app": "codex-watch",
        "updated": datetime.now().isoformat(timespec="seconds"),
        "source_updated": snap.updated,
        "source": snap.source,
        "quota_status": snap.quota_status if fresh else "stale",
        "available": bool(snap.available),
        "model": snap.model,
        "plan": snap.plan,
        "effort": snap.effort,
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
        "reset_card": reset_card,
        "reset_cards": reset_cards,
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
            "latest_session": Path(str(snap.latest_session)).name,
            "error": "usage reader error" if snap.error else None,
        },
    }


def parse_boolean(value: Any) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def configured_update_apk(server: Any) -> Path | None:
    raw_path = str(getattr(server, "update_apk_path", "") or "").strip()
    if not raw_path:
        return None
    path = Path(raw_path).expanduser().resolve()
    if not path.is_file() or path.suffix.lower() != ".apk":
        return None
    return path


def make_update_metadata(server: Any) -> dict[str, Any] | None:
    apk_path = configured_update_apk(server)
    if apk_path is None:
        return None

    apk_url = str(getattr(server, "update_apk_url", "") or "").strip()
    parsed_url = urlparse(apk_url)
    if (
        parsed_url.scheme.lower() != "https"
        or not parsed_url.netloc
        or parsed_url.username is not None
        or parsed_url.password is not None
        or parsed_url.query
        or parsed_url.fragment
    ):
        raise ValueError("CODEX_WATCH_UPDATE_APK_URL must be a clean HTTPS URL")

    version_code = int(getattr(server, "update_version_code", 0) or 0)
    version_name = str(getattr(server, "update_version_name", "") or "").strip()
    if version_code <= 0 or not version_name:
        raise ValueError("Update version metadata is invalid")

    return {
        "version_code": version_code,
        "version_name": version_name,
        "apk_url": apk_url,
        "sha256": sha256_file(apk_path),
        "required": bool(getattr(server, "update_required", False)),
        "notes": str(getattr(server, "update_notes", "") or "").strip(),
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
                    self.server.forced_status,
                    self.server.max_age_seconds,
                    self.server.reset_card_expires_at,
                    self.server.reset_cards_json,
                )
                self.write_json(payload)
            except Exception as exc:
                print(f"usage generation failed: {type(exc).__name__}", file=sys.stderr)
                self.write_json({"error": "usage unavailable"}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if parsed.path == "/update":
            try:
                payload = make_update_metadata(self.server)
                if payload is None:
                    self.write_json({"error": "update unavailable"}, HTTPStatus.NOT_FOUND)
                else:
                    self.write_json(payload)
            except Exception as exc:
                print(f"update metadata failed: {type(exc).__name__}", file=sys.stderr)
                self.write_json({"error": "update unavailable"}, HTTPStatus.SERVICE_UNAVAILABLE)
            return
        if parsed.path == "/downloads/codex-pet-watch.apk":
            self.write_update_apk()
            return

        self.write_json({"error": "not found"}, HTTPStatus.NOT_FOUND)

    def authorized(self, parsed) -> bool:
        expected = getattr(self.server, "token", "")
        if not expected:
            return True
        provided = self.headers.get("X-Codex-Watch-Token", "")
        return provided == expected

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

    def write_update_apk(self) -> None:
        apk_path = configured_update_apk(self.server)
        if apk_path is None:
            self.write_json({"error": "update unavailable"}, HTTPStatus.NOT_FOUND)
            return
        try:
            size = apk_path.stat().st_size
            if size <= 0:
                raise ValueError("APK is empty")
        except Exception as exc:
            print(f"update download failed: {type(exc).__name__}", file=sys.stderr)
            self.write_json({"error": "update unavailable"}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return

        try:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", 'attachment; filename="codex-pet-watch.apk"')
            self.send_header("Cache-Control", "private, max-age=300, no-transform")
            self.send_header("Vary", "X-Codex-Watch-Token")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            with apk_path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(64 * 1024), b""):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception as exc:
            print(f"update download failed: {type(exc).__name__}", file=sys.stderr)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=APP_NAME)
    parser.add_argument("--host", default=os.environ.get("CODEX_WATCH_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CODEX_WATCH_PORT", DEFAULT_PORT)))
    parser.add_argument("--codex-dir", default=os.environ.get("CODEX_WATCH_CODEX_DIR", ""))
    parser.add_argument("--codexbar-path", default=os.environ.get("CODEXBAR_SAFE_PATH", ""))
    parser.add_argument("--reset-card-expires-at", default=os.environ.get("CODEX_WATCH_RESET_CARD_EXPIRES_AT", ""))
    parser.add_argument("--reset-cards-json", default=os.environ.get("CODEX_WATCH_RESET_CARDS_JSON", ""))
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
        print(
            json.dumps(
                make_payload(
                    reader,
                    args.force_status,
                    args.max_age_seconds,
                    args.reset_card_expires_at,
                    args.reset_cards_json,
                ),
                ensure_ascii=False,
                indent=2,
            )
        )
        return

    httpd = ThreadingHTTPServer((args.host, args.port), CodexWatchHandler)
    httpd.reader = reader
    httpd.token = os.environ.get("CODEX_WATCH_TOKEN", "")
    httpd.forced_status = args.force_status
    httpd.max_age_seconds = args.max_age_seconds
    httpd.reset_card_expires_at = args.reset_card_expires_at
    httpd.reset_cards_json = args.reset_cards_json
    httpd.update_apk_path = os.environ.get("CODEX_WATCH_UPDATE_APK_PATH", "")
    httpd.update_apk_url = os.environ.get("CODEX_WATCH_UPDATE_APK_URL", DEFAULT_UPDATE_APK_URL)
    httpd.update_version_code = int(os.environ.get("CODEX_WATCH_UPDATE_VERSION_CODE", "3"))
    httpd.update_version_name = os.environ.get("CODEX_WATCH_UPDATE_VERSION_NAME", "0.2.1")
    httpd.update_required = parse_boolean(os.environ.get("CODEX_WATCH_UPDATE_REQUIRED", "false"))
    httpd.update_notes = os.environ.get("CODEX_WATCH_UPDATE_NOTES", "")
    httpd.quiet = args.quiet

    print(f"{APP_NAME} listening on http://{args.host}:{args.port}/usage")
    if httpd.token:
        print("Token protection enabled.")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
