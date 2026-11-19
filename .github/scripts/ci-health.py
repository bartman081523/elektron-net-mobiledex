#!/usr/bin/env python3
"""Newest completed run per workflow in the fork's repos; exit 1 when a default
branch is red.

Kept as a script instead of an inline heredoc: a multi-line Python block inside
a YAML `run:` scalar breaks the indentation.
"""
import json
import subprocess
import sys

REPOS = [
    "bartman081523/elektron-net-mobiledex",
    "bartman081523/elektron-net-mobile",
    "bartman081523/elektron-net",
    "bartman081523/elektron-net-electrs",
]
RED = {"failure", "cancelled", "timed_out"}


def gh(args):
    out = subprocess.run(["gh", "api", *args], capture_output=True, text=True)
    return out.stdout.strip()


def main():
    lines = ["| repository | workflow | branch | result | link |", "|---|---|---|---|---|"]
    failed = 0
    for repo in REPOS:
        default = gh([f"repos/{repo}", "-q", ".default_branch"]) or "main"
        try:
            data = json.loads(gh([f"repos/{repo}/actions/runs?per_page=50"]))
        except json.JSONDecodeError:
            data = {"workflow_runs": []}
        seen = set()
        for run in data.get("workflow_runs", []):
            if run.get("status") != "completed":
                continue
            name = run.get("name") or run.get("path") or "?"
            if name in seen or name == "ci-health":
                continue
            seen.add(name)
            branch = run.get("head_branch") or ""
            default_run = branch == default
            result = run.get("conclusion") or ""
            url = run.get("html_url") or ""
            title = (run.get("display_title") or "")[:50]
            lines.append(f"| {repo} | {name} | {branch[:28]} | {result} | [{title}]({url}) |")
            if default_run and result in RED:
                failed += 1
    print("\n".join(lines))
    print(f"failed={failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
