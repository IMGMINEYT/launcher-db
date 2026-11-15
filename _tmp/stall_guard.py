"""Stall guard for transfer phases: bounded stall timeout + bounded retries.

A transfer phase (download/upload) runs as an asyncio task. A watchdog
cancels the attempt when no progress callback fires within stall_timeout.
Attempts are bounded. A flood-wait sleeps its required time and consumes one
attempt; a flood-wait beyond max_flood_sleep fails the job. Any other
exception propagates immediately. When attempts are exhausted the caller
gets JobFailed - never an unbounded hang, never a false success.
"""
import asyncio
import logging
import time

log = logging.getLogger("renamer.stall")

MAX_ATTEMPTS = 3          # total tries per phase, including the first
STALL_TIMEOUT = 180.0     # seconds without any progress callback -> cancel attempt
WATCH_INTERVAL = 10.0     # watchdog poll cadence
MAX_FLOOD_SLEEP = 900.0   # a flood-wait longer than this fails the job instead


class Stalled(Exception):
    """One attempt produced no progress within stall_timeout."""


class JobFailed(Exception):
    """The phase did not complete within the bounded attempts."""

    def __init__(self, phase, attempts, reason):
        super().__init__(f"{phase} failed after {attempts} attempt(s): {reason}")
        self.phase = phase
        self.attempts = attempts
        self.reason = str(reason)


async def run_with_stall_guard(phase, job_id, attempt_factory, progress, *,
                               max_attempts=MAX_ATTEMPTS,
                               stall_timeout=STALL_TIMEOUT,
                               watch_interval=WATCH_INTERVAL,
                               max_flood_sleep=MAX_FLOOD_SLEEP,
                               flood_errors=(),
                               sleep=asyncio.sleep,
                               clock=time.monotonic):
    """Run attempt_factory(attempt) under a progress watchdog.

    progress is a dict; the transfer's progress callback must set
    progress["last"] = clock() on every callback. Returns the attempt's
    result. Raises JobFailed after max_attempts, or the attempt's own
    exception (other than flood_errors) immediately.
    """
    last_err = None
    for attempt in range(1, max_attempts + 1):
        progress["last"] = clock()
        task = asyncio.ensure_future(attempt_factory(attempt))
        try:
            while True:
                if task.done():
                    return task.result()
                if clock() - progress["last"] > stall_timeout:
                    task.cancel()
                    try:
                        await task
                    except asyncio.CancelledError:
                        pass
                    raise Stalled(f"no progress for {stall_timeout:.0f}s")
                await sleep(watch_interval)
        except Stalled as e:
            last_err = e
        except flood_errors as e:
            wait = float(getattr(e, "seconds", 0) or 0)
            last_err = e
            if wait > max_flood_sleep:
                raise JobFailed(phase, attempt, f"flood-wait {wait:.0f}s exceeds cap")
            await sleep(wait)
    raise JobFailed(phase, max_attempts, last_err)
