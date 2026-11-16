"""Shared RPM pacing for every VoxTicket evaluation script that calls an API.

Why this exists: Groq already returned 429 rate-limit errors at single-session
load during earlier VoxTicket runs, so no evaluation script may fire requests
as fast as it can. The default is deliberately conservative (10 requests per
minute). Raise it only if your key's plan allows it, with --rpm.

Behaviour:
  * A minimum interval between requests (60 / rpm seconds).
  * On HTTP 429: honour the server's Retry-After header when present
    (seconds), otherwise exponential backoff 30s, 60s, 120s, 240s, 240s...
    up to max_retries attempts. Every 429 and every backoff second is
    recorded on the limiter and must be reported as data, never hidden.
  * Other HTTP statuses are returned, not raised, so a probe can record them.
  * Network errors return status 0 with the error text.

Standard library only.
"""

import base64
import json
import time
import urllib.error
import urllib.request

DEFAULT_RPM = 10
DEFAULT_MAX_RETRIES = 5
BACKOFF_SEQUENCE = [30, 60, 120, 240, 240, 240]


class RateLimiter:
    """Minimum-interval pacing plus 429 backoff accounting."""

    def __init__(self, rpm=DEFAULT_RPM, max_retries=DEFAULT_MAX_RETRIES):
        if rpm <= 0:
            raise ValueError("rpm must be positive")
        self.rpm = rpm
        self.min_interval = 60.0 / rpm
        self.max_retries = max_retries
        self._last_call = 0.0
        self.calls = 0
        self.rate_limit_hits = 0
        self.backoff_seconds_total = 0.0
        self.rate_limit_events = []

    def wait_before(self):
        """Sleep until the minimum interval since the previous call elapsed."""
        now = time.monotonic()
        wait = self.min_interval - (now - self._last_call)
        if wait > 0:
            time.sleep(wait)
        self._last_call = time.monotonic()

    def stats(self):
        return {
            "rpm_configured": self.rpm,
            "min_interval_seconds": round(self.min_interval, 3),
            "calls_made": self.calls,
            "rate_limit_hits_429": self.rate_limit_hits,
            "backoff_seconds_total": round(self.backoff_seconds_total, 1),
            "rate_limit_events": self.rate_limit_events,
        }


def _backoff_for(attempt, retry_after):
    if retry_after is not None:
        try:
            return max(0, int(retry_after))
        except (TypeError, ValueError):
            pass
    idx = min(attempt, len(BACKOFF_SEQUENCE) - 1)
    return BACKOFF_SEQUENCE[idx]


def paced_request(limiter, method, url, data=None, headers=None, timeout=180):
    """One paced HTTP request with 429 retry.

    Returns (status:int, body:str, latency_ms:float). Status 0 means the
    request never reached the server (network error); the error text is in
    the body so the caller can record it.
    """
    payload = None
    hdrs = dict(headers or {})
    if data is not None:
        payload = json.dumps(data).encode("utf-8") if not isinstance(data, bytes) else data
        hdrs.setdefault("Content-Type", "application/json")
    attempt = 0
    while True:
        limiter.wait_before()
        started = time.monotonic()
        req = urllib.request.Request(url, data=payload, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8", errors="replace")
                limiter.calls += 1
                return resp.status, body, (time.monotonic() - started) * 1000.0
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            limiter.calls += 1
            latency_ms = (time.monotonic() - started) * 1000.0
            if exc.code == 429 and attempt < limiter.max_retries:
                limiter.rate_limit_hits += 1
                backoff = _backoff_for(attempt, exc.headers.get("Retry-After"))
                limiter.backoff_seconds_total += backoff
                limiter.rate_limit_events.append(
                    {"url": url, "attempt": attempt + 1, "backoff_seconds": backoff}
                )
                print(
                    f"  [rpm] 429 from {url} (attempt {attempt + 1}); "
                    f"backing off {backoff}s (configured {limiter.rpm} rpm)"
                )
                time.sleep(backoff)
                attempt += 1
                continue
            if exc.code == 429:
                limiter.rate_limit_hits += 1
            return exc.code, body, latency_ms
        except Exception as exc:  # network error, DNS, refused connection
            return 0, f"{type(exc).__name__}: {exc}", (time.monotonic() - started) * 1000.0


def basic_auth_header(username, password):
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return {"Authorization": f"Basic {token}"}
