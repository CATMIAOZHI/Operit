from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PR_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "pr-check.yml"
ANDROID_BUILD_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "android-build.yml"
ANDROID_ACTION = REPO_ROOT / ".github" / "actions" / "android-checks" / "action.yml"
ANDROID_RUNNER = REPO_ROOT / "ci" / "script" / "run_android_checks.sh"


class WorkflowContractTest(unittest.TestCase):
    def test_pr_jobs_use_precise_scope_conditions(self) -> None:
        workflow = PR_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("if: needs.plan.outputs.web == 'true'", workflow)
        self.assertIn("if: needs.plan.outputs.toolpkg == 'true'", workflow)
        self.assertNotIn("outputs.web == 'true' ||", workflow)
        self.assertNotIn("outputs.toolpkg == 'true' ||", workflow)
        self.assertNotIn("name: PR gate", workflow)

    def test_both_android_workflows_use_shared_implementation(self) -> None:
        pr_workflow = PR_WORKFLOW.read_text(encoding="utf-8")
        build_workflow = ANDROID_BUILD_WORKFLOW.read_text(encoding="utf-8")

        self.assertEqual(pr_workflow.count("uses: ./.github/actions/android-checks"), 2)
        self.assertIn("uses: ./.github/actions/android-checks", build_workflow)
        self.assertIn("run_android_checks.sh --lane full", build_workflow)
        self.assertIn("--lane build --task", build_workflow)

    def test_pr_dependency_caches_are_explicitly_read_only(self) -> None:
        workflow = PR_WORKFLOW.read_text(encoding="utf-8")
        action = ANDROID_ACTION.read_text(encoding="utf-8")

        self.assertEqual(workflow.count("dependency-cache-read-only: 'true'"), 2)
        self.assertIn("uses: actions/cache/restore@", action)
        self.assertIn("uses: actions/cache/save@", action)
        self.assertIn("dependency-cache-read-only: 'false'", ANDROID_BUILD_WORKFLOW.read_text(encoding="utf-8"))

    def test_external_actions_are_pinned_to_commits(self) -> None:
        for path in (PR_WORKFLOW, ANDROID_BUILD_WORKFLOW, ANDROID_ACTION):
            with self.subTest(path=path):
                for line in path.read_text(encoding="utf-8").splitlines():
                    match = re.match(r"\s*uses:\s+([^\s#]+)", line)
                    if not match or match.group(1).startswith("./"):
                        continue
                    self.assertRegex(match.group(1), r"@[0-9a-f]{40}$")

    def test_stable_android_build_has_no_github_oauth_configuration(self) -> None:
        workflow = ANDROID_BUILD_WORKFLOW.read_text(encoding="utf-8")
        action = ANDROID_ACTION.read_text(encoding="utf-8")

        for source in (workflow, action):
            self.assertNotIn("OPERIT_GITHUB_CLIENT_ID", source)
            self.assertNotIn("OPERIT_GITHUB_OAUTH_BROKER_BASE_URL", source)
            self.assertNotIn("GITHUB_CLIENT_ID", source)
            self.assertNotIn("GITHUB_OAUTH_BROKER_BASE_URL", source)
            self.assertNotIn("GITHUB_CLIENT_SECRET", source)


class AndroidRunnerContractTest(unittest.TestCase):
    def run_with_fake_gradle(self, *arguments: str) -> tuple[subprocess.CompletedProcess[str], list[str]]:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            runner = workspace / "run_android_checks.sh"
            shutil.copyfile(ANDROID_RUNNER, runner)
            (workspace / "gradlew").write_text(
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > gradle-arguments.txt\n",
                encoding="utf-8",
                newline="\n",
            )
            result = subprocess.run(
                ["bash", runner.name, *arguments],
                cwd=workspace,
                capture_output=True,
                text=True,
            )
            arguments_path = workspace / "gradle-arguments.txt"
            gradle_arguments = arguments_path.read_text(encoding="utf-8").splitlines() if arguments_path.exists() else []
            return result, gradle_arguments

    def test_full_lane_uses_app_tasks_and_profile_flags(self) -> None:
        result, arguments = self.run_with_fake_gradle("--lane", "full", "--profile", "--console", "plain")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            arguments,
            [
                ":app:assembleDebug",
                ":app:testDebugUnitTest",
                ":app:lintDebug",
                "--stacktrace",
                "--no-daemon",
                "--profile",
                "--console=plain",
            ],
        )

    def test_build_lane_preserves_selected_task_and_optional_checks(self) -> None:
        result, arguments = self.run_with_fake_gradle(
            "--lane",
            "build",
            "--task",
            ":app:assembleNightly",
            "--unit-tests",
            "--lint",
            "--continue",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            arguments,
            [
                ":app:assembleNightly",
                ":app:testDebugUnitTest",
                ":app:lintDebug",
                "--stacktrace",
                "--no-daemon",
                "--continue",
            ],
        )

    def test_invalid_lane_is_rejected_before_gradle(self) -> None:
        result, arguments = self.run_with_fake_gradle("--lane", "invalid")

        self.assertEqual(result.returncode, 2)
        self.assertEqual(arguments, [])

    def test_build_lane_requires_a_task(self) -> None:
        result, arguments = self.run_with_fake_gradle("--lane", "build")

        self.assertEqual(result.returncode, 2)
        self.assertEqual(arguments, [])


if __name__ == "__main__":
    unittest.main()
