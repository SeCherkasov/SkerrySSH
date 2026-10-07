"""Execute planned stages; collect only the outputs owned by each test task."""
from __future__ import annotations
import os
import re
import shutil
import signal
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from . import checks, evidence, policy, resources, state


def test_verdict(module, task, cwd, allow_failure=False):
    files = sorted(Path(cwd, module, "build/test-results", task).glob("TEST-*.xml"))
    if not files:
        raise ValueError(f"{module}:{task}: no JUnit results")
    counts = [0, 0, 0, 0]
    for file in files:
        try:
            root = ET.parse(file).getroot()
            if root.tag not in ("testsuite", "testsuites"):
                raise ValueError("unknown JUnit root")
            suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
            if not suites:
                raise ValueError("empty JUnit report")
            for suite in suites:
                values = [int(suite.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")]
                if any(n < 0 for n in values) or sum(values[1:]) > values[0]:
                    raise ValueError("invalid JUnit counts")
                for i, value in enumerate(values):
                    counts[i] += value
        except (OSError, ET.ParseError, ValueError) as exc:
            raise ValueError(f"invalid JUnit result {file}: {exc}") from exc
    tests, failures, errors, skipped = counts
    if not tests or tests == skipped:
        raise ValueError(f"{module}:{task}: no executed tests")
    if allow_failure:
        if not failures or errors:
            raise ValueError("RED requires an assertion failure, not an empty run or test error")
    elif failures or errors:
        raise ValueError(f"{module}:{task}: {failures} failures, {errors} errors")
    return f"{tests} tests, {skipped} skipped, {len(files)} suites"


def execute(command, log, cwd, timeout=3600):
    """Only terminate the process group this runner created, never other projects' daemons."""
    log.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    token = uuid.uuid4().hex
    env = dict(os.environ, **{resources.MARKER: token})
    with os.fdopen(fd, "w") as handle:
        process = subprocess.Popen(command, cwd=cwd, stdout=handle, stderr=subprocess.STDOUT,
                                   start_new_session=os.name != "nt", env=env)
        try:
            return process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            if os.name == "nt":
                process.terminate()
            else:
                os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    process.kill()
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise
        finally:
            # Even a successful wrapper can leave a detached JVM behind. The inherited marker
            # identifies it after reparenting without touching another project's processes.
            stopped = resources.cleanup(token)
            if stopped:
                handle.write(f"\nharness: reaped runner-owned processes {stopped}\n")


def run(stages, cwd=None):
    cwd = state.repo_root(cwd)
    with evidence.build_lock(cwd):
        for stage in stages:
            if evidence.current(stage, cwd):
                print(f"current: {stage.name}", flush=True)
                continue
            before = evidence.snapshot(stage, cwd)
            started = time.monotonic()
            log = evidence.directory(cwd) / "logs" / f"{stage.name.replace(':', '-')}.log"
            ok, detail = False, ""
            print(f"running: {stage.name}", flush=True)
            try:
                if stage.name == "checks":
                    findings = checks.run(cwd)
                    for finding in findings:
                        print(finding, flush=True)
                    ok = not any(f.severity == checks.BLOCK for f in findings)
                    detail = f"{len(findings)} findings"
                else:
                    if stage.test_task:
                        # Remove this task's outputs before execution, so stale XML cannot pass.
                        module = stage.name.split(":", 1)[1]
                        output = Path(cwd, module, "build/test-results", stage.test_task)
                        if output.exists():
                            shutil.rmtree(output)
                    command = list(stage.command)
                    code = execute(command, log, cwd)
                    ok, detail = code == 0, f"exit {code}; {log}"
                    if ok and stage.test_task:
                        detail += "; " + test_verdict(module, stage.test_task, cwd)
            except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
                ok, detail = False, str(exc)
            evidence.record_stage(stage, ok, detail, cwd, before=before)
            print(f"{'ok' if ok else 'FAILED'}: {stage.name} ({time.monotonic()-started:.1f}s) — {detail}", flush=True)
            if not ok:
                if log.exists():
                    print("\n".join(log.read_text(errors="replace").splitlines()[-25:]), flush=True)
                return False
    return True


def red(test_file, pattern, cwd=None):
    cwd = state.repo_root(cwd)
    if not pattern or pattern.startswith("-") or not state.is_test(test_file):
        raise ValueError("RED requires a test file and a test name/glob")
    with evidence.build_lock(cwd):
        source = state.tree_digest("src", cwd)
        test_hash = state.worktree_entries(cwd).get(test_file)
        if not test_hash:
            raise ValueError("test file is missing")
        log = evidence.directory(cwd) / "logs/red.log"
        if test_file.startswith("tools/harness/tests/") or test_file == "tools/harness/selftest.py":
            command = [policy.PY, "tools/harness/selftest.py", "-k", pattern.strip("*")]
            code = execute(command, log, cwd)
            text = log.read_text(errors="replace")
            if code == 0 or not re.search(r"Ran [1-9]\d* tests", text) or "FAILED (failures=" not in text:
                raise ValueError("RED did not execute a failing assertion; inspect " + str(log))
        else:
            module = policy.module_of(test_file)
            task = policy.TEST_TASKS.get(module)
            if not task:
                raise ValueError("no JVM test task for this file")
            output = Path(cwd, module, "build/test-results", task)
            if output.exists():
                shutil.rmtree(output)
            command = ["./gradlew", f":{module}:{task}", "--tests", pattern, "--rerun", "--max-workers=2",
                       "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process"]
            if module == "server":
                command.append("-PserverOnly")
            if execute(command, log, cwd) == 0:
                raise ValueError("RED test passed")
            test_verdict(module, task, cwd, allow_failure=True)
        if source != state.tree_digest("src", cwd) or test_hash != state.worktree_entries(cwd).get(test_file):
            raise ValueError("inputs moved during RED")
        record = {"branch": state.current_branch(cwd), "file": test_file, "hash": test_hash,
                  "pattern": pattern, "source": source, "at": time.time()}
        evidence.update(lambda data: data.setdefault("red", []).append(record), cwd)


def red_debt(cwd=None):
    if policy.classify(cwd)["kind"] != "bug":
        return []
    entries = state.worktree_entries(cwd)
    for record in evidence.load(cwd).get("red", []):
        if (record["branch"] == state.current_branch(cwd) and entries.get(record["file"]) == record["hash"]
                and record["source"] != state.tree_digest("src", cwd)):
            return []
    return ["red — record a failing regression before the fix; keep that test unchanged"]
