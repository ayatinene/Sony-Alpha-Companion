#!/usr/bin/env python3
"""Release gating and build-metadata helpers for the Android release workflow.

Pure helpers, so the release decision can be unit tested without a network or an
Android SDK (see test_release_metadata.py). The workflow feeds GitHub API
responses in as JSON files and consumes the printed key=value outputs.

The version and the exact source revision are read from the build metadata that
the Gradle build emits, never guessed from an artifact file name, and both the
release build workflow (Android Release Build: release unit tests, lint and
assembleRelease) and the interface workflow (Android UI) must have succeeded for
the same commit on the default branch. The metadata also records the build
variant so a debug build can never masquerade as the published Release artifact.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

BUILD_WORKFLOW = "Android Release Build"
UI_WORKFLOW = "Android UI"
REQUIRED_WORKFLOWS = (BUILD_WORKFLOW, UI_WORKFLOW)
DEFAULT_BRANCH = "main"
RELEASE_VARIANT = "release"
REVISION_PLACEHOLDER = "unknown"
VERSION_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")
HEX_PATTERN = re.compile(r"^[0-9a-f]{7,40}$")


class MetadataError(ValueError):
    """Build metadata is missing, malformed, or does not match the release commit."""


@dataclass(frozen=True)
class Decision:
    ready: bool
    reason: str


def release_tag(version_name: str, git_revision: str) -> str:
    return f"v{version_name}-{git_revision}"


def release_apk_name(version_name: str, git_revision: str, variant: str = RELEASE_VARIANT) -> str:
    return f"AlphaCompanion-{version_name}-{git_revision}-{variant}.apk"


def release_title(version_name: str, git_revision: str) -> str:
    return f"Sony Alpha Companion {version_name} ({git_revision})"


def _clean(value: Any) -> str:
    return value if isinstance(value, str) else ""


def validate_build_metadata(metadata: Any, head_sha: str, expected_variant: str = RELEASE_VARIANT) -> tuple[str, str]:
    """Return ``(version_name, git_revision)`` or raise :class:`MetadataError`."""
    if not isinstance(metadata, Mapping):
        raise MetadataError("build metadata is missing or not an object")
    version = _clean(metadata.get("versionName")).strip()
    revision = _clean(metadata.get("gitRevision")).strip()
    full_sha = _clean(metadata.get("gitSha")).strip()
    variant = _clean(metadata.get("variant")).strip()
    dirty = metadata.get("dirty")
    if not version or not VERSION_PATTERN.match(version):
        raise MetadataError(f"unusable versionName: {version!r}")
    if variant != expected_variant:
        raise MetadataError(
            f"build metadata variant {variant!r} is not {expected_variant!r}; "
            "a Release artifact must not be a debug masquerade"
        )
    if not revision or revision == REVISION_PLACEHOLDER or not HEX_PATTERN.match(revision):
        raise MetadataError(f"unusable gitRevision: {revision!r}")
    if full_sha and not HEX_PATTERN.match(full_sha):
        raise MetadataError(f"unusable gitSha: {full_sha!r}")
    if dirty is not False:
        raise MetadataError("build metadata reports a dirty working tree" if dirty else "build metadata has no dirty flag")
    if not re.fullmatch(r"[0-9a-f]{40}", head_sha or ""):
        raise MetadataError(f"release commit SHA is not a full commit id: {head_sha!r}")
    if full_sha != head_sha:
        raise MetadataError(f"build metadata gitSha {full_sha!r} does not match release commit {head_sha!r}")
    if not head_sha.startswith(revision):
        raise MetadataError(f"gitRevision {revision!r} is not a prefix of release commit {head_sha!r}")
    return version, revision


def _latest(runs: Sequence[Mapping[str, Any]]) -> Optional[Mapping[str, Any]]:
    if not runs:
        return None
    return sorted(runs, key=lambda run: _clean(run.get("created_at")), reverse=True)[0]


def evaluate_workflow(workflow: str, runs: Sequence[Mapping[str, Any]]) -> Decision:
    """Judge one workflow's push runs for the release commit."""
    latest = _latest(runs)
    if latest is None:
        return Decision(False, f"{workflow}: no run for this commit")
    status = _clean(latest.get("status"))
    conclusion = _clean(latest.get("conclusion"))
    if status != "completed":
        return Decision(False, f"{workflow}: latest run is {status or 'pending'}")
    if conclusion != "success":
        return Decision(False, f"{workflow}: latest run concluded {conclusion or 'unknown'}")
    return Decision(True, f"{workflow}: success")


def _repo_full_name(run: Mapping[str, Any]) -> str:
    for key in ("head_repository", "repository"):
        value = run.get(key)
        if isinstance(value, Mapping) and isinstance(value.get("full_name"), str):
            return value["full_name"]
    return ""


def select_runs(payload: Any, head_sha: str, default_branch: str = DEFAULT_BRANCH, repository: str = "") -> list[Mapping[str, Any]]:
    """Extract this commit's push runs from a workflow-runs API response.

    Only runs of the same commit, event, branch and repository qualify. A run
    from another branch or a fork must never satisfy the release gate, even when
    it carries the same SHA.
    """
    if not isinstance(payload, Mapping):
        return []
    runs = payload.get("workflow_runs")
    if not isinstance(runs, list):
        return []
    return [
        run
        for run in runs
        if isinstance(run, Mapping)
        and _clean(run.get("head_sha")) == head_sha
        and _clean(run.get("event")) == "push"
        and _clean(run.get("head_branch")) == default_branch
        and (not repository or _repo_full_name(run) == repository)
    ]


def select_build_run_id(runs: Sequence[Mapping[str, Any]]) -> str:
    latest = _latest(runs)
    return "" if latest is None else str(latest.get("id", ""))


def decide_release(
    *,
    repository: str,
    default_branch: str,
    workflow_name: str,
    event: str,
    head_branch: str,
    head_repository: str,
    head_sha: str,
    conclusion: str,
    runs_by_workflow: Mapping[str, Sequence[Mapping[str, Any]]],
) -> Decision:
    """Decide whether the completed workflow run may publish a release.

    A push to the default branch only, and every required workflow must have a
    completed, successful run for the same commit; a run that is still queued or
    in progress keeps the release pending until its own completion event.
    """
    if workflow_name not in REQUIRED_WORKFLOWS:
        return Decision(False, f"unexpected triggering workflow: {workflow_name!r}")
    if event != "push":
        return Decision(False, f"trigger event {event!r} is not a push")
    if head_repository != repository:
        return Decision(False, "workflow run belongs to a different repository")
    if head_branch != default_branch:
        return Decision(False, f"branch {head_branch!r} is not {default_branch!r}")
    if conclusion != "success":
        return Decision(False, f"triggering run concluded {conclusion!r}")
    reasons = []
    for workflow in REQUIRED_WORKFLOWS:
        verdict = evaluate_workflow(workflow, runs_by_workflow.get(workflow, []))
        reasons.append(verdict.reason)
        if not verdict.ready:
            return Decision(False, "; ".join(reasons))
    return Decision(True, "; ".join(reasons))


def _single_line(text: str) -> str:
    return " ".join(text.split())


def _read_json(path: Optional[str]) -> Any:
    if not path:
        return {}
    file = Path(path)
    if not file.is_file():
        return {}
    try:
        return json.loads(file.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"invalid JSON in {path}: {error}") from error


def _write_outputs(path: Optional[str], outputs: Mapping[str, str]) -> None:
    lines = "".join(f"{key}={_single_line(str(value))}\n" for key, value in outputs.items())
    sys.stdout.write(lines)
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(lines)


def _find_apk(artifacts: Path, expected_name: str) -> Path:
    matches = sorted(path for path in artifacts.rglob("*.apk") if path.is_file())
    exact = [path for path in matches if path.name == expected_name]
    if exact:
        return exact[0]
    if not matches:
        raise MetadataError(f"no APK found under {artifacts}")
    raise MetadataError(f"expected APK {expected_name!r} but the artifact contains: {', '.join(p.name for p in matches)}")


def _release_notes(version: str, revision: str, head_sha: str) -> str:
    return (
        "自动构建的预览版（Release，固定签名且不可调试）。\n\n"
        f"- 版本：{version} ({revision})\n"
        f"- 对应提交：{head_sha}\n"
        "- 验证：Android Release Build（Release 单元测试、Lint、assembleRelease、固定签名证书与非调试校验）"
        "与 Android UI 均已通过。\n"
        f"- 附件：{release_apk_name(version, revision)}\n\n"
        "源码、构建方式与许可见仓库 README、docs/ 与 licenses/。\n"
    )


def _command_decide(args: argparse.Namespace) -> int:
    runs_by_workflow = {
        BUILD_WORKFLOW: select_runs(_read_json(args.build_runs), args.head_sha, args.default_branch, args.repository),
        UI_WORKFLOW: select_runs(_read_json(args.ui_runs), args.head_sha, args.default_branch, args.repository),
    }
    decision = decide_release(
        repository=args.repository,
        default_branch=args.default_branch,
        workflow_name=args.workflow_name,
        event=args.event,
        head_branch=args.head_branch,
        head_repository=args.head_repository,
        head_sha=args.head_sha,
        conclusion=args.conclusion,
        runs_by_workflow=runs_by_workflow,
    )
    _write_outputs(
        args.github_output,
        {
            "decision": "release" if decision.ready else "skip",
            "reason": decision.reason,
            "build_run_id": select_build_run_id(runs_by_workflow[BUILD_WORKFLOW]) if decision.ready else "",
        },
    )
    return 0


def _command_prepare(args: argparse.Namespace) -> int:
    try:
        version, revision = validate_build_metadata(_read_json(args.metadata), args.head_sha)
        apk = _find_apk(Path(args.artifacts), release_apk_name(version, revision))
    except MetadataError as error:
        sys.stderr.write(f"release metadata rejected: {error}\n")
        return 1
    Path(args.notes_file).write_text(_release_notes(version, revision, args.head_sha), encoding="utf-8")
    _write_outputs(
        args.github_output,
        {
            "tag": release_tag(version, revision),
            "apk": str(apk),
            "release_name": release_title(version, revision),
            "version": version,
            "revision": revision,
            "notes_file": args.notes_file,
        },
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    decide = subparsers.add_parser("decide", help="decide whether this run may publish a release")
    decide.add_argument("--repository", required=True)
    decide.add_argument("--default-branch", default="main")
    decide.add_argument("--workflow-name", required=True)
    decide.add_argument("--event", required=True)
    decide.add_argument("--head-branch", required=True)
    decide.add_argument("--head-repository", required=True)
    decide.add_argument("--head-sha", required=True)
    decide.add_argument("--conclusion", required=True)
    decide.add_argument("--build-runs")
    decide.add_argument("--ui-runs")
    decide.add_argument("--github-output")
    decide.set_defaults(func=_command_decide)

    prepare = subparsers.add_parser("prepare-release", help="validate artifact metadata and print release outputs")
    prepare.add_argument("--metadata", required=True)
    prepare.add_argument("--artifacts", required=True)
    prepare.add_argument("--head-sha", required=True)
    prepare.add_argument("--notes-file", default="release-notes.md")
    prepare.add_argument("--github-output")
    prepare.set_defaults(func=_command_prepare)

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
