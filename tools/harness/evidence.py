"""Versioned receipts, serialized updates and one build runner per machine/user."""
from __future__ import annotations
import contextlib
import hashlib
import json
import os
import tempfile
import time
from pathlib import Path
from . import state

SCHEMA = 2
ENGINE = "skerry-harness-v2.1"


def directory(cwd=None):
    git_dir = state.git_dir(cwd)
    if not git_dir:
        raise ValueError("not inside a Git repository")
    return Path(git_dir, "skerry-harness-v2")


def path(cwd=None):
    return directory(cwd) / "evidence.json"


def load(cwd=None):
    target = path(cwd)
    if not target.exists():
        return {"schema": SCHEMA}
    try:
        data = json.loads(target.read_text())
    except (OSError, ValueError) as exc:
        raise ValueError(f"cannot read {target}: {exc}; preserve and repair the evidence file") from exc
    if not isinstance(data, dict) or data.get("schema") != SCHEMA:
        raise ValueError(f"unsupported evidence schema in {target}")
    for field in ("receipts", "tasks", "pending", "reviews"):
        if field in data and not isinstance(data[field], dict):
            raise ValueError(f"invalid evidence field: {field}")
    return data


def _private_dir(target):
    target.mkdir(parents=True, exist_ok=True, mode=0o700)
    target.chmod(0o700)


@contextlib.contextmanager
def file_lock(target, wait=True):
    """OS locks are released on crashes. Windows locks the first byte."""
    _private_dir(target.parent)
    with target.open("a+b") as handle:
        target.chmod(0o600)
        if os.name == "nt":
            import msvcrt
            handle.seek(0)
            handle.write(b"0")
            handle.flush()
            handle.seek(0)
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK if wait else msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                raise RuntimeError("another harness runner holds the lock") from exc
        else:
            import fcntl
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | (0 if wait else fcntl.LOCK_NB))
            except BlockingIOError as exc:
                raise RuntimeError("another harness runner holds the lock") from exc
        try:
            yield
        finally:
            if os.name == "nt":
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def update(mutate, cwd=None):
    target = path(cwd)
    with file_lock(directory(cwd) / "state.lock"):
        data = load(cwd)
        result = mutate(data)
        fd, temporary = tempfile.mkstemp(prefix="evidence-", dir=target.parent)
        try:
            with os.fdopen(fd, "w") as handle:
                json.dump(data, handle, sort_keys=True, indent=2)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, target)
            target.chmod(0o600)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        return result


@contextlib.contextmanager
def build_lock(cwd=None):
    cache = Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache")))
    with file_lock(cache / "skerry-harness/build.lock", wait=False):
        yield


def snapshot(stage, cwd=None):
    result = {"engine": ENGINE, "name": stage.name, "command": list(stage.command),
              "inputs": stage.inputs(cwd)}
    if stage.name == "checks":
        from . import policy
        result.update(base=state.merge_base(cwd=cwd), kind=policy.classify(cwd)["kind"])
    if stage.command and stage.command[0] == "./gradlew":
        result["environment"] = {name: os.environ.get(name, "") for name in
                                 ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "DISPLAY",
                                  "SKERRY_SERVER_ONLY", "GRADLE_USER_HOME")}
    return result


def key(snapshot):
    return hashlib.sha256(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()


def current(stage, cwd=None):
    receipt = load(cwd).get("receipts", {}).get(key(snapshot(stage, cwd)), {})
    return receipt.get("ok") is True


def record_stage(stage, ok, detail, cwd=None, before=None):
    now = snapshot(stage, cwd)
    if before is not None and before != now:
        raise ValueError(f"{stage.name}: inputs changed while it ran; result discarded")
    receipt = {"ok": ok, "snapshot": now, "detail": detail, "at": time.time()}
    update(lambda data: data.setdefault("receipts", {}).update({key(now): receipt}), cwd)
