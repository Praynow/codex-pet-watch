"""
Local Codex usage reader for Codex Pet Watch.

Derived from CodexBar Safe by Alessandro, MIT licensed.
This module reads only local Codex CLI files:
- ~/.codex/config.toml
- ~/.codex/sessions/**/*.jsonl

It does not read browser data, cookies, OpenAI auth tokens, or Claude files,
and it does not start Codex or any other CLI process.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path


DEFAULT_CODEX_DIR = Path(os.environ.get("CODEX_WATCH_CODEX_DIR", Path.home() / ".codex")).expanduser()
DEFAULT_SESSIONS_DIR = DEFAULT_CODEX_DIR / "sessions"
QUOTA_SAMPLE_MAX_AGE = timedelta(hours=8)


@dataclass
class TokenBucket:
    input_tokens: int = 0
    cached_input_tokens: int = 0
    output_tokens: int = 0
    reasoning_output_tokens: int = 0
    reported_total_tokens: int = 0

    @property
    def total(self) -> int:
        if self.reported_total_tokens:
            return self.reported_total_tokens
        known = self.input_tokens + self.cached_input_tokens + self.output_tokens
        if self.reasoning_output_tokens and self.reasoning_output_tokens not in (self.output_tokens, known):
            return known + self.reasoning_output_tokens
        return known

    def add(self, other: "TokenBucket") -> None:
        self.input_tokens += other.input_tokens
        self.cached_input_tokens += other.cached_input_tokens
        self.output_tokens += other.output_tokens
        self.reasoning_output_tokens += other.reasoning_output_tokens
        self.reported_total_tokens += other.reported_total_tokens


@dataclass
class LimitWindow:
    label: str
    used_percent: int = 0
    remaining_percent: int = 100
    resets_at: str = "unknown"
    window_minutes: int | None = None
    available: bool = False
    stale: bool = False
    seen_at: str = "unknown"


@dataclass
class UsageSnapshot:
    available: bool = False
    updated: str = "Never"
    source: str = "none"
    model: str = "unknown"
    effort: str = "unknown"
    plan: str = "unknown"
    session: LimitWindow = field(default_factory=lambda: LimitWindow("Session"))
    weekly: LimitWindow = field(default_factory=lambda: LimitWindow("Weekly"))
    today: TokenBucket = field(default_factory=TokenBucket)
    last_7_days: TokenBucket = field(default_factory=TokenBucket)
    last_30_days: TokenBucket = field(default_factory=TokenBucket)
    all_sessions: TokenBucket = field(default_factory=TokenBucket)
    session_files: int = 0
    scanned_files: int = 0
    latest_session: str = "none"
    quota_seen_at: str = "unknown"
    quota_status: str = "not found"
    latest_rate_limit_seen_at: str = "unknown"
    error: str | None = None


class CodexUsageReader:
    """Read Codex quota and token usage from local Codex CLI files."""

    def __init__(self, codex_dir: str | Path | None = None) -> None:
        self.codex_dir = Path(codex_dir).expanduser() if codex_dir else DEFAULT_CODEX_DIR
        self.sessions_dir = self.codex_dir / "sessions"

    def read(self) -> UsageSnapshot:
        snap = UsageSnapshot()
        snap.updated = datetime.now().strftime("%H:%M:%S")

        if not self.codex_dir.exists():
            snap.error = f"Codex config directory not found: {self.codex_dir}"
            return snap

        snap.available = True
        snap.model = self._read_model()

        if not self.sessions_dir.exists():
            snap.source = "config"
            snap.error = "Codex sessions directory not found yet"
            return snap

        files = sorted(
            self.sessions_dir.rglob("*.jsonl"),
            key=lambda p: self._safe_mtime(p),
            reverse=True,
        )
        snap.session_files = len(files)
        if not files:
            snap.source = "sessions"
            snap.error = "No Codex session logs found yet"
            return snap

        snap.latest_session = str(files[0])
        latest_model = None
        latest_effort = None
        latest_model_seen = None
        latest_limits = None
        latest_plan = None
        latest_seen = None
        latest_rate_seen = None
        saw_rate_limit_events = False

        for path in files:
            model, effort, model_seen = self._extract_latest_model_context(path)
            if model_seen and (latest_model_seen is None or model_seen > latest_model_seen):
                latest_model = model
                latest_effort = effort
                latest_model_seen = model_seen
            limits, plan, seen_at, saw_events, rate_seen_at = self._extract_latest_limits(path)
            saw_rate_limit_events = saw_rate_limit_events or saw_events
            latest_rate_seen = _max_datetime(latest_rate_seen, rate_seen_at)
            if limits and (latest_seen is None or (seen_at and seen_at > latest_seen)):
                latest_limits = limits
                latest_plan = plan
                latest_seen = seen_at

        snap.latest_rate_limit_seen_at = _format_seen_at(latest_rate_seen)
        if latest_model:
            snap.model = latest_model
        if latest_effort:
            snap.effort = latest_effort

        if latest_limits:
            snap.source = "sessions"
            too_old = latest_seen is None or datetime.now() - latest_seen > QUOTA_SAMPLE_MAX_AGE
            seen_label = _format_seen_at(latest_seen)
            snap.quota_seen_at = seen_label
            snap.quota_status = "estimated" if too_old else "current"
            snap.session = self._parse_limit("5 hours", latest_limits.get("primary"), too_old, seen_label)
            snap.weekly = self._parse_limit("weekly", latest_limits.get("secondary"), too_old, seen_label)
            if latest_plan:
                snap.plan = str(latest_plan).replace("_", " ").title()
        else:
            snap.source = "tokens"
            if saw_rate_limit_events:
                snap.quota_status = "not provided by recent local logs"
                snap.error = "Codex logs include rate-limit events, but quota fields were empty"
            else:
                snap.quota_status = "not found"

        self._scan_token_totals(files, snap)
        return snap

    @staticmethod
    def _safe_mtime(path: Path) -> float:
        try:
            return path.stat().st_mtime
        except OSError:
            return 0.0

    def _read_model(self) -> str:
        config = self.codex_dir / "config.toml"
        if not config.exists():
            return "unknown"
        try:
            for line in config.read_text(encoding="utf-8", errors="ignore").splitlines():
                match = re.match(r"\s*model\s*=\s*[\"']?([^\"'#]+)", line)
                if match:
                    return match.group(1).strip()
        except OSError:
            pass
        return "unknown"

    @staticmethod
    def _extract_latest_model_context(path: Path) -> tuple[str | None, str | None, datetime | None]:
        last_model = None
        last_effort = None
        last_seen = None
        try:
            with path.open("r", encoding="utf-8", errors="ignore") as handle:
                for line in handle:
                    if '"model"' not in line and '"effort"' not in line:
                        continue
                    event = _json_line(line)
                    payload = event.get("payload", {}) if isinstance(event, dict) else {}
                    if not isinstance(payload, dict):
                        continue
                    model = str(payload.get("model") or "").strip()
                    effort = str(payload.get("effort") or payload.get("reasoning_effort") or "").strip()
                    if not model and not effort:
                        continue
                    seen_at = _parse_timestamp(event.get("timestamp"))
                    if model:
                        last_model = model
                    if effort:
                        last_effort = effort
                    last_seen = seen_at or last_seen
        except OSError:
            return None, None, None
        return last_model, last_effort, last_seen

    @staticmethod
    def _extract_latest_limits(path: Path) -> tuple[dict | None, str | None, datetime | None, bool, datetime | None]:
        last_limits = None
        last_plan = None
        last_seen = None
        last_rate_seen = None
        saw_events = False
        try:
            with path.open("r", encoding="utf-8", errors="ignore") as handle:
                for line in handle:
                    if "rate_limits" not in line:
                        continue
                    event = _json_line(line)
                    payload = event.get("payload", {}) if isinstance(event, dict) else {}
                    if not isinstance(payload, dict) or payload.get("type") != "token_count":
                        continue
                    limits = payload.get("rate_limits")
                    if isinstance(limits, dict):
                        saw_events = True
                        event_seen = _parse_timestamp(event.get("timestamp"))
                        last_rate_seen = _max_datetime(last_rate_seen, event_seen)
                        if _limits_are_usable(limits):
                            last_limits = limits
                            last_seen = event_seen
                            if limits.get("plan_type"):
                                last_plan = limits.get("plan_type")
        except OSError:
            return None, None, None, saw_events, last_rate_seen
        return last_limits, last_plan, last_seen, saw_events, last_rate_seen

    @staticmethod
    def _parse_limit(label: str, raw: object, stale: bool = False, seen_at: str = "unknown") -> LimitWindow:
        if not isinstance(raw, dict):
            return LimitWindow(label=label, available=False, stale=stale, seen_at=seen_at)

        reset_at = _parse_reset_datetime(raw.get("resets_at") or raw.get("reset_at"))
        has_reset = reset_at is not None and reset_at <= datetime.now()
        used = 0 if has_reset else _safe_int(raw.get("used_percent"), 0)
        if used == 0 and raw.get("limit"):
            limit = _safe_int(raw.get("limit"), 0)
            remaining = _safe_int(raw.get("remaining"), limit)
            if limit and limit > 0:
                used = int(max(0, min(100, (1 - remaining / limit) * 100)))

        used = max(0, min(100, used or 0))
        remaining_percent = max(0, min(100, 100 - used))
        return LimitWindow(
            label=label,
            used_percent=used,
            remaining_percent=remaining_percent,
            resets_at="reset reached; waiting for Codex to write a new sample"
            if has_reset
            else _format_reset(raw.get("resets_at") or raw.get("reset_at")),
            window_minutes=_safe_int(raw.get("window_minutes"), None),
            available=True,
            stale=stale,
            seen_at=seen_at,
        )

    def _scan_token_totals(self, files: list[Path], snap: UsageSnapshot) -> None:
        today = datetime.now().date()
        last_7 = today - timedelta(days=6)
        last_30 = today - timedelta(days=29)

        for path in files:
            tokens = self._extract_last_total_tokens(path)
            if not tokens:
                continue

            snap.scanned_files += 1
            snap.all_sessions.add(tokens)

            session_date = _date_from_path(path)
            if session_date is None:
                try:
                    session_date = datetime.fromtimestamp(path.stat().st_mtime).date()
                except OSError:
                    session_date = today

            if session_date == today:
                snap.today.add(tokens)
            if session_date >= last_7:
                snap.last_7_days.add(tokens)
            if session_date >= last_30:
                snap.last_30_days.add(tokens)

    @staticmethod
    def _extract_last_total_tokens(path: Path) -> TokenBucket | None:
        last = None
        try:
            with path.open("r", encoding="utf-8", errors="ignore") as handle:
                for line in handle:
                    if "total_token_usage" not in line:
                        continue
                    event = _json_line(line)
                    payload = event.get("payload", {}) if isinstance(event, dict) else {}
                    if not isinstance(payload, dict) or payload.get("type") != "token_count":
                        continue
                    info = payload.get("info", {})
                    if not isinstance(info, dict):
                        continue
                    total = info.get("total_token_usage")
                    if isinstance(total, dict):
                        last = _token_bucket(total)
        except OSError:
            return None
        return last


def _json_line(line: str) -> dict:
    try:
        value = json.loads(line)
        return value if isinstance(value, dict) else {}
    except json.JSONDecodeError:
        return {}


def _safe_int(value: object, default: int | None = 0) -> int | None:
    if value is None:
        return default
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def _limits_are_usable(limits: dict) -> bool:
    primary = limits.get("primary")
    secondary = limits.get("secondary")
    return isinstance(primary, dict) or isinstance(secondary, dict)


def _token_bucket(raw: dict) -> TokenBucket:
    cached = (
        (_safe_int(raw.get("cached_input_tokens"), 0) or 0)
        + (_safe_int(raw.get("cache_read_input_tokens"), 0) or 0)
        + (_safe_int(raw.get("cache_creation_input_tokens"), 0) or 0)
    )
    return TokenBucket(
        input_tokens=_safe_int(raw.get("input_tokens"), 0) or 0,
        cached_input_tokens=cached,
        output_tokens=_safe_int(raw.get("output_tokens"), 0) or 0,
        reasoning_output_tokens=_safe_int(raw.get("reasoning_output_tokens"), 0) or 0,
        reported_total_tokens=_safe_int(raw.get("total_tokens"), 0) or 0,
    )


def _parse_timestamp(value: object) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            parsed = parsed.astimezone()
        return parsed.replace(tzinfo=None)
    except ValueError:
        return None


def _parse_reset_datetime(value: object) -> datetime | None:
    if value in (None, ""):
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(float(value))
    text = str(value)
    if text.isdigit():
        return datetime.fromtimestamp(float(text))
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            parsed = parsed.astimezone()
        return parsed.replace(tzinfo=None)
    except ValueError:
        return None


def _format_reset(value: object) -> str:
    dt = _parse_reset_datetime(value)
    if dt is None:
        return "unknown" if value in (None, "") else str(value)

    delta = dt - datetime.now()
    seconds = max(0, int(delta.total_seconds()))
    minutes = seconds // 60
    if minutes >= 60 * 24:
        return f"{minutes // (60 * 24)}d {(minutes // 60) % 24}h"
    if minutes >= 60:
        return f"{minutes // 60}h {minutes % 60:02d}m"
    return f"{minutes}m"


def _format_seen_at(value: datetime | None) -> str:
    if value is None:
        return "unknown"
    if value.date() == datetime.now().date():
        return value.strftime("%H:%M")
    return value.strftime("%Y-%m-%d %H:%M")


def _max_datetime(a: datetime | None, b: datetime | None) -> datetime | None:
    if a is None:
        return b
    if b is None:
        return a
    return max(a, b)


def _date_from_path(path: Path):
    match = re.search(r"rollout-(\d{4}-\d{2}-\d{2})", path.name)
    if not match:
        match = re.search(r"(\d{4})[\\/](\d{2})[\\/](\d{2})", str(path))
        if match:
            text = "-".join(match.groups())
        else:
            return None
    else:
        text = match.group(1)
    try:
        return datetime.strptime(text, "%Y-%m-%d").date()
    except ValueError:
        return None
