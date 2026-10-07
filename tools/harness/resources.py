"""Find and reap only processes carrying the current runner's inherited launch marker."""
import os
import signal
import time
from pathlib import Path

MARKER = "SKERRY_HARNESS_RUN"


def owned_processes(token):
    processes = []
    proc = Path("/proc")
    if not proc.is_dir():
        return processes
    needle = f"{MARKER}={token}".encode()
    for directory in proc.iterdir():
        if not directory.name.isdigit():
            continue
        try:
            if directory.stat().st_uid != os.getuid():
                continue
            if needle not in (directory / "environ").read_bytes().split(b"\0"):
                continue
            stat = (directory / "stat").read_text().rsplit(") ", 1)[1].split()
            if stat[0] != "Z":
                processes.append((int(directory.name), stat[19]))  # pid + process start time
        except (OSError, IndexError):
            continue
    return processes


def _signal(processes, sig):
    for pid, started in processes:
        try:
            stat = Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()
            if stat[19] == started:  # refuse a reused PID
                os.kill(pid, sig)
        except (OSError, IndexError):
            continue


def cleanup(token):
    processes = owned_processes(token)
    if not processes:
        return []
    _signal(processes, signal.SIGTERM)
    deadline = time.monotonic() + 2
    while owned_processes(token) and time.monotonic() < deadline:
        time.sleep(0.05)
    remaining = owned_processes(token)
    _signal(remaining, signal.SIGKILL)
    deadline = time.monotonic() + 1
    while owned_processes(token) and time.monotonic() < deadline:
        time.sleep(0.05)
    if owned_processes(token):
        raise RuntimeError("runner-owned JVM/process survived cleanup; inspect it before the next build")
    return [pid for pid, _ in processes]
