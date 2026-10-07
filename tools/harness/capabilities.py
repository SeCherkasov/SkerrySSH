"""Select ECC sources on demand; external prompts never own project policy or build verdicts."""
import os
from pathlib import Path
from . import policy

# Topics are hints from paths plus explicit task semantics, not a claim of complete code analysis.
ROUTES = {
    "harness": (("agent-harness-construction",), ()),
    "kotlin": (("kotlin-patterns", "kotlin-testing"), ()),
    "coroutines": (("kotlin-coroutines-flows",), ()),
    "ui": (("compose-multiplatform-patterns", "accessibility"), ("a11y-architect",)),
    "accessibility": (("accessibility",), ("a11y-architect",)),
    "server": (("kotlin-ktor-patterns",), ()),
    "security": (("security-review",), ()),
    "database": (("database-migrations", "kotlin-exposed-patterns"), ("database-reviewer",)),
    "architecture": (("intent-driven-development",), ("code-explorer", "code-architect")),
    "performance": ((), ("performance-optimizer",)),
    "build": ((), ("kotlin-build-resolver",)),
    "errors": ((), ("silent-failure-hunter",)),
    "coverage": (("kotlin-testing",), ("pr-test-analyzer",)),
}
ROLE_TOPICS = {
    "explorer": {"architecture"},
    "skerry-kotlin-reviewer": {"kotlin", "coroutines", "ui", "server", "coverage"},
    "skerry-security-reviewer": {"security", "database", "server"},
}
INSTRUCTION = ("Read each selected available SKILL.md before relevant work; follow its references on demand. "
               "Resolve unresolved names using the session's available-skills catalogue, or report unavailable guidance. "
               "Missing sources are unavailable, never silently applied. Pass selected names, source paths and task scope "
               "to subagents; do not inline the full ECC catalogue. Current user instructions and project contracts "
               "outrank generic examples: kotlin.test/JUnit 5, hand-written fakes, commonMain, no Android ViewModels/Room. "
               "Specialists are suggestions, not launches or completed reviews; read their source before delegation. "
               "Keep reviews read-only and run all builds in the parent through the harness.")


def plugin_root(explicit=None):
    selected = explicit or os.environ.get("ECC_PLUGIN_ROOT")
    if selected:
        return Path(selected).expanduser().resolve()
    cache = Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex")), "plugins/cache/ecc/ecc")
    candidates = sorted(p for p in cache.glob("*") if (p / "skills").is_dir())
    # Multiple versions do not prove which is enabled in the current session.
    return candidates[0].resolve() if len(candidates) == 1 else None


def source(name, kind, root):
    relative = f"skills/{name}/SKILL.md" if kind == "skill" else f"agents/{name}.md"
    path = root / relative if root else None
    return {"name": "ecc:" + name, "source": relative, "path": str(path) if path else None,
            "status": ("available" if path.is_file() else "missing") if path else "unresolved"}


def plan(cwd=None, focus=(), ecc_root=None, role=None):
    unknown = set(focus) - ROUTES.keys()
    if unknown:
        raise ValueError("unknown ECC focus: " + ", ".join(sorted(unknown)))
    task = policy.classify(cwd)
    topics = set(focus)
    paths = task["code_paths"]
    for path in paths:
        lowered = path.lower()
        if policy.harness_input(path):
            topics.add("harness")
            continue
        if path.endswith(".kt"):
            topics.add("kotlin")
        if path.startswith(("composeApp/src/", "androidApp/src/")) or "/ui/" in path:
            topics.add("ui")
        if path.startswith("server/src/") and path.endswith(".kt"):
            topics.add("server")
        if any(s.lower() in lowered for s in policy.SECURITY_SEGMENTS):
            topics.add("security")
        if "/terminal/" in lowered or "/graphics/" in lowered:
            topics.add("performance")
        if path.endswith(".sql") or any(s in lowered for s in ("/migration/", "/database/", "/db/")):
            topics.add("database")
    if role in ROLE_TOPICS:
        topics &= ROLE_TOPICS[role]
    root = plugin_root(ecc_root)
    skill_names = sorted({name for topic in topics for name in ROUTES[topic][0]})
    specialist_names = sorted({name for topic in topics for name in ROUTES[topic][1]}) if role is None else []
    specialists = []
    for name in specialist_names:
        item = source(name, "agent", root)
        item["profile"] = ("explorer" if name == "code-explorer" else
                           "worker" if name == "kotlin-build-resolver" else "skerry-reviewer")
        item["execution_role"] = "explorer" if name == "code-explorer" else "default"
        item["constraints"] = "Narrow read-only analysis; builds stay in the parent; no Git or source mutations."
        specialists.append(item)
    return {"focus": sorted(topics), "skills": [source(name, "skill", root) for name in skill_names],
            "specialists": specialists, "instruction": INSTRUCTION}
