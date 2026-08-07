"""Skerry's development harness: what a change is, what it owes, and what has been verified.

`state`   — content digests of the worktree and the record of verified stages
`policy`  — the kind of change, the areas it touches, and the requirements that follow
`checks`  — project rules decided by pattern rather than by a reviewing model
`gate`    — the runner; the only thing that marks a stage green

The Claude Code hooks in `.claude/hooks/` read the same modules, so the rule enforced at commit
time and the rule reported by `gate.py status` cannot drift apart.
"""
