#!/usr/bin/env python3
"""Unit tests for the release gating and metadata helpers (no Android SDK needed):

    python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from release_metadata import (
    BUILD_WORKFLOW,
    UI_WORKFLOW,
    MetadataError,
    decide_release,
    evaluate_workflow,
    main,
    release_apk_name,
    release_tag,
    release_title,
    select_build_run_id,
    select_runs,
    validate_build_metadata,
)

SHA = "8ed893f3f6c05491be561aad206111ef3d3b7f5e"
SHORT = "8ed893f"
REPO = "ayatinene/Sony-Alpha-Companion"


def metadata(**overrides: object) -> dict:
    base = {"versionName": "0.1.16-preview", "versionCode": 17, "gitRevision": SHORT, "gitSha": SHA,
            "variant": "release", "dirty": False}
    base.update(overrides)
    return base


def run(workflow: str, *, status: str = "completed", conclusion: str = "success", sha: str = SHA,
        event: str = "push", created_at: str = "2026-11-10T00:00:00Z", run_id: int = 1,
        head_branch: str = "main", repository: str = REPO) -> dict:
    return {"id": run_id, "name": workflow, "event": event, "head_sha": sha, "head_branch": head_branch,
            "head_repository": {"full_name": repository}, "repository": {"full_name": repository},
            "status": status, "conclusion": conclusion, "created_at": created_at}


def decide(**overrides: object):
    params = dict(
        repository=REPO, default_branch="main", workflow_name=BUILD_WORKFLOW, event="push",
        head_branch="main", head_repository=REPO, head_sha=SHA, conclusion="success",
        runs_by_workflow={BUILD_WORKFLOW: [run(BUILD_WORKFLOW)], UI_WORKFLOW: [run(UI_WORKFLOW)]},
    )
    params.update(overrides)
    return decide_release(**params)  # type: ignore[arg-type]


class NamingTest(unittest.TestCase):
    def test_release_tag_is_unique_per_revision(self) -> None:
        self.assertEqual(release_tag("0.1.16-preview", SHORT), "v0.1.16-preview-8ed893f")
        self.assertNotEqual(release_tag("0.1.16-preview", SHORT), release_tag("0.1.16-preview", "deadbee"))

    def test_release_apk_name_carries_version_revision_and_variant(self) -> None:
        self.assertEqual(release_apk_name("0.1.16-preview", SHORT),
                         "AlphaCompanion-0.1.16-preview-8ed893f-release.apk")
        self.assertNotIn("debug", release_apk_name("0.1.16-preview", SHORT))

    def test_release_title_mentions_revision(self) -> None:
        self.assertEqual(release_title("0.1.16-preview", SHORT), "Sony Alpha Companion 0.1.16-preview (8ed893f)")


class ValidateMetadataTest(unittest.TestCase):
    def test_accepts_matching_metadata(self) -> None:
        self.assertEqual(validate_build_metadata(metadata(), SHA), ("0.1.16-preview", SHORT))

    def test_rejects_sha_mismatch(self) -> None:
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(), "0" * 40)

    def test_rejects_abbreviated_sha_as_commit(self) -> None:
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(), SHORT)

    def test_rejects_dirty_build(self) -> None:
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(dirty=True), SHA)
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(dirty="false"), SHA)

    def test_rejects_placeholder_revision(self) -> None:
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(gitRevision="unknown", gitSha=""), SHA)

    def test_rejects_revision_that_is_not_a_prefix(self) -> None:
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(gitRevision="deadbee"), SHA)

    def test_rejects_missing_or_malformed(self) -> None:
        with self.assertRaises(MetadataError):
            validate_build_metadata(None, SHA)
        with self.assertRaises(MetadataError):
            validate_build_metadata({"versionName": ""}, SHA)
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(versionName="0.1.16 preview"), SHA)

    def test_accepts_matching_release_variant(self) -> None:
        self.assertEqual(validate_build_metadata(metadata(variant="release"), SHA), ("0.1.16-preview", SHORT))

    def test_rejects_debug_variant_masquerade(self) -> None:
        with self.assertRaises(MetadataError):
            validate_build_metadata(metadata(variant="debug"), SHA)

    def test_rejects_missing_variant(self) -> None:
        payload = metadata()
        del payload["variant"]
        with self.assertRaises(MetadataError):
            validate_build_metadata(payload, SHA)


class EvaluateWorkflowTest(unittest.TestCase):
    def test_successful_completed_run(self) -> None:
        self.assertTrue(evaluate_workflow(BUILD_WORKFLOW, [run(BUILD_WORKFLOW)]).ready)

    def test_absent_run_blocks(self) -> None:
        self.assertFalse(evaluate_workflow(BUILD_WORKFLOW, []).ready)
        self.assertFalse(evaluate_workflow(UI_WORKFLOW, []).ready)

    def test_in_progress_blocks(self) -> None:
        self.assertFalse(evaluate_workflow(BUILD_WORKFLOW, [run(BUILD_WORKFLOW, status="in_progress", conclusion=None)]).ready)

    def test_failure_blocks_even_with_older_success(self) -> None:
        runs = [run(BUILD_WORKFLOW, conclusion="success", created_at="2026-11-10T00:00:00Z"),
                run(BUILD_WORKFLOW, conclusion="failure", created_at="2026-11-10T01:00:00Z")]
        self.assertFalse(evaluate_workflow(BUILD_WORKFLOW, runs).ready)

    def test_cancelled_blocks(self) -> None:
        self.assertFalse(evaluate_workflow(UI_WORKFLOW, [run(UI_WORKFLOW, conclusion="cancelled")]).ready)


class DecideReleaseTest(unittest.TestCase):
    def test_releases_when_both_workflows_pass(self) -> None:
        self.assertTrue(decide().ready)

    def test_missing_ui_run_blocks(self) -> None:
        decision = decide(runs_by_workflow={BUILD_WORKFLOW: [run(BUILD_WORKFLOW)], UI_WORKFLOW: []})
        self.assertFalse(decision.ready)
        self.assertIn(UI_WORKFLOW, decision.reason)

    def test_ui_workflow_failure_blocks(self) -> None:
        decision = decide(runs_by_workflow={BUILD_WORKFLOW: [run(BUILD_WORKFLOW)],
                                            UI_WORKFLOW: [run(UI_WORKFLOW, conclusion="failure")]})
        self.assertFalse(decision.ready)

    def test_ui_workflow_running_blocks_until_it_finishes(self) -> None:
        decision = decide(runs_by_workflow={BUILD_WORKFLOW: [run(BUILD_WORKFLOW)],
                                            UI_WORKFLOW: [run(UI_WORKFLOW, status="in_progress", conclusion=None)]})
        self.assertFalse(decision.ready)
        self.assertIn("in_progress", decision.reason)

    def test_missing_build_run_blocks_even_when_triggered_by_ui(self) -> None:
        decision = decide(workflow_name=UI_WORKFLOW,
                          runs_by_workflow={BUILD_WORKFLOW: [], UI_WORKFLOW: [run(UI_WORKFLOW)]})
        self.assertFalse(decision.ready)

    def test_pull_request_never_releases(self) -> None:
        self.assertFalse(decide(event="pull_request").ready)

    def test_manual_dispatch_never_releases(self) -> None:
        self.assertFalse(decide(event="workflow_dispatch").ready)

    def test_other_branch_never_releases(self) -> None:
        self.assertFalse(decide(head_branch="feat/smart-remote-content").ready)

    def test_foreign_repository_never_releases(self) -> None:
        self.assertFalse(decide(head_repository="someone/fork").ready)

    def test_failed_triggering_run_never_releases(self) -> None:
        self.assertFalse(decide(conclusion="failure").ready)

    def test_unexpected_triggering_workflow_is_rejected(self) -> None:
        self.assertFalse(decide(workflow_name="Some Other Workflow").ready)


class SelectRunsTest(unittest.TestCase):
    def test_filters_by_sha_and_push_event(self) -> None:
        payload = {"workflow_runs": [run(BUILD_WORKFLOW, run_id=1), run(BUILD_WORKFLOW, sha="1" * 40, run_id=2),
                                     run(BUILD_WORKFLOW, event="pull_request", run_id=3)]}
        self.assertEqual([item["id"] for item in select_runs(payload, SHA)], [1])

    def test_drops_other_branches_and_foreign_repositories(self) -> None:
        payload = {"workflow_runs": [run(BUILD_WORKFLOW, run_id=1),
                                     run(BUILD_WORKFLOW, run_id=2, head_branch="feat/x"),
                                     run(BUILD_WORKFLOW, run_id=3, repository="someone/fork")]}
        self.assertEqual([item["id"] for item in select_runs(payload, SHA, "main", REPO)], [1])

    def test_repository_filter_is_optional(self) -> None:
        payload = {"workflow_runs": [run(BUILD_WORKFLOW, run_id=1, repository="someone/fork")]}
        self.assertEqual([item["id"] for item in select_runs(payload, SHA)], [1])

    def test_tolerates_bad_payloads(self) -> None:
        self.assertEqual(select_runs(None, SHA), [])
        self.assertEqual(select_runs({"workflow_runs": "nope"}, SHA), [])

    def test_picks_newest_run_for_artifact_download(self) -> None:
        runs = select_runs({"workflow_runs": [run(BUILD_WORKFLOW, run_id=1, created_at="2026-11-10T00:00:00Z"),
                                              run(BUILD_WORKFLOW, run_id=2, created_at="2026-11-10T05:00:00Z")]}, SHA)
        self.assertEqual(select_build_run_id(runs), "2")


class CliTest(unittest.TestCase):
    def _write(self, directory: Path, name: str, payload: object) -> str:
        path = directory / name
        path.write_text(json.dumps(payload), encoding="utf-8")
        return str(path)

    def test_decide_cli_releases_and_reports_build_run(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            build_runs = self._write(directory, "build.json", {"workflow_runs": [run(BUILD_WORKFLOW, run_id=7)]})
            ui_runs = self._write(directory, "ui.json", {"workflow_runs": [run(UI_WORKFLOW)]})
            output = directory / "out.txt"
            code = main(["decide", "--repository", REPO, "--workflow-name", BUILD_WORKFLOW, "--event", "push",
                         "--head-branch", "main", "--head-repository", REPO, "--head-sha", SHA,
                         "--conclusion", "success", "--build-runs", build_runs, "--ui-runs", ui_runs,
                         "--github-output", str(output)])
            self.assertEqual(code, 0)
            text = output.read_text(encoding="utf-8")
            self.assertIn("decision=release", text)
            self.assertIn("build_run_id=7", text)

    def test_decide_cli_skips_for_pull_request(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            output = directory / "out.txt"
            code = main(["decide", "--repository", REPO, "--workflow-name", BUILD_WORKFLOW, "--event", "pull_request",
                         "--head-branch", "feat/x", "--head-repository", REPO, "--head-sha", SHA,
                         "--conclusion", "success", "--github-output", str(output)])
            self.assertEqual(code, 0)
            self.assertIn("decision=skip", output.read_text(encoding="utf-8"))

    def test_decide_cli_skips_when_ui_has_not_run(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            build_runs = self._write(directory, "build.json", {"workflow_runs": [run(BUILD_WORKFLOW)]})
            ui_runs = self._write(directory, "ui.json", {"workflow_runs": []})
            output = directory / "out.txt"
            code = main(["decide", "--repository", REPO, "--workflow-name", BUILD_WORKFLOW, "--event", "push",
                         "--head-branch", "main", "--head-repository", REPO, "--head-sha", SHA,
                         "--conclusion", "success", "--build-runs", build_runs, "--ui-runs", ui_runs,
                         "--github-output", str(output)])
            self.assertEqual(code, 0)
            self.assertIn("decision=skip", output.read_text(encoding="utf-8"))

    def test_prepare_release_cli_validates_metadata_and_apk(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            metadata_path = self._write(directory, "build-metadata.json", metadata())
            artifacts = directory / "artifacts" / "outputs" / "apk" / "release"
            artifacts.mkdir(parents=True)
            apk = artifacts / release_apk_name("0.1.16-preview", SHORT)
            apk.write_bytes(b"apk")
            output = directory / "out.txt"
            code = main(["prepare-release", "--metadata", metadata_path, "--artifacts", str(directory / "artifacts"),
                         "--head-sha", SHA, "--notes-file", str(directory / "notes.md"), "--github-output", str(output)])
            self.assertEqual(code, 0)
            text = output.read_text(encoding="utf-8")
            self.assertIn("tag=v0.1.16-preview-8ed893f", text)
            self.assertIn(apk.name, text)
            self.assertIn("0.1.16-preview", (directory / "notes.md").read_text(encoding="utf-8"))

    def test_prepare_release_notes_describe_release_not_debug(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            metadata_path = self._write(directory, "build-metadata.json", metadata())
            artifacts = directory / "artifacts"
            artifacts.mkdir()
            (artifacts / release_apk_name("0.1.16-preview", SHORT)).write_bytes(b"apk")
            self.assertEqual(main(["prepare-release", "--metadata", metadata_path, "--artifacts", str(artifacts),
                                   "--head-sha", SHA, "--notes-file", str(directory / "notes.md")]), 0)
            notes = (directory / "notes.md").read_text(encoding="utf-8")
            self.assertIn("Release", notes)
            self.assertNotIn("Debug", notes)
            self.assertNotIn("debug", notes)

    def test_prepare_release_cli_fails_on_debug_variant_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            metadata_path = self._write(directory, "build-metadata.json", metadata(variant="debug"))
            self.assertEqual(main(["prepare-release", "--metadata", metadata_path,
                                   "--artifacts", str(directory), "--head-sha", SHA]), 1)

    def test_prepare_release_cli_fails_on_mismatched_sha(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            metadata_path = self._write(directory, "build-metadata.json", metadata())
            self.assertEqual(main(["prepare-release", "--metadata", metadata_path,
                                   "--artifacts", str(directory), "--head-sha", "0" * 40]), 1)

    def test_prepare_release_cli_fails_when_apk_name_was_not_applied(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            metadata_path = self._write(directory, "build-metadata.json", metadata())
            artifacts = directory / "artifacts"
            artifacts.mkdir()
            (artifacts / "app-release-unsigned.apk").write_bytes(b"apk")
            self.assertEqual(main(["prepare-release", "--metadata", metadata_path,
                                   "--artifacts", str(artifacts), "--head-sha", SHA]), 1)

    def test_prepare_release_cli_rejects_a_debug_apk_masquerade(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            metadata_path = self._write(directory, "build-metadata.json", metadata())
            artifacts = directory / "artifacts"
            artifacts.mkdir()
            (artifacts / "AlphaCompanion-0.1.16-preview-8ed893f-debug.apk").write_bytes(b"apk")
            self.assertEqual(main(["prepare-release", "--metadata", metadata_path,
                                   "--artifacts", str(artifacts), "--head-sha", SHA]), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
