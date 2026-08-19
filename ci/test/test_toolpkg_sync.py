from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "example_packages"))

from sync_example_packages import (  # noqa: E402
    _exclude_expected_incompatible,
    _manifest_runtime_files,
    _pack_toolpkg_folder,
    _should_skip_copy,
)


class ToolPkgRuntimeFilesTest(unittest.TestCase):
    def test_should_skip_copy_requires_matching_destination_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.js"
            destination = Path(directory) / "destination.js"
            source.write_text("exports.one = 1;\n", encoding="utf-8")
            destination.write_text("exports.one = 1;\n", encoding="utf-8")

            # Signature matches and destination bytes match source: skip.
            self.assertTrue(
                _should_skip_copy(
                    dry_run=False,
                    destination=destination,
                    destination_name="destination.js",
                    output_state={"destination.js": "stale"},
                    plan_signature="stale",
                    source=source,
                )
            )

            # Destination was modified externally even though the recorded
            # signature still matches: must not skip so sync restores it.
            destination.write_text("exports.one = 2;\n", encoding="utf-8")
            self.assertFalse(
                _should_skip_copy(
                    dry_run=False,
                    destination=destination,
                    destination_name="destination.js",
                    output_state={"destination.js": "stale"},
                    plan_signature="stale",
                    source=source,
                )
            )

    def test_sandboxpackage_installer_is_valid_javascript(self) -> None:
        subprocess.run(
            [
                "node",
                "--check",
                str(REPO_ROOT / "tools" / "sandboxpackage_dev_install_or_update.js"),
            ],
            check=True,
            capture_output=True,
            text=True,
        )

    def test_expected_incompatible_packages_are_excluded_from_build_check(self) -> None:
        items = ["github.js", "deepsearching", "pdf_vision_parser.js", "subagent"]

        filtered = _exclude_expected_incompatible(
            items,
            ["deepsearching", "pdf_vision_parser.js", "subagent"],
        )

        self.assertEqual(["github.js"], filtered)

    def test_ignored_runtime_files_are_included_in_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(
                ["git", "init", "-b", "main"],
                cwd=repository,
                check=True,
                capture_output=True,
            )
            package = repository / "example"
            package.mkdir()
            (package / "modules").mkdir()
            (package / ".gitignore").write_text("main.js\nmodules/\n", encoding="utf-8")
            (package / "manifest.json").write_text(
                json.dumps(
                    {
                        "toolpkg_id": "com.operit.test",
                        "main": "main.js",
                        "wasm_modules": [{"id": "core", "path": "modules/core.wasm"}],
                    }
                ),
                encoding="utf-8",
            )
            (package / "main.js").write_text("exports.test = true;\n", encoding="utf-8")
            (package / "modules" / "core.wasm").write_bytes(b"\x00asm")
            archive = repository / "example.toolpkg"

            _pack_toolpkg_folder(repository, package, archive)

            with zipfile.ZipFile(archive) as stream:
                names = set(stream.namelist())
            self.assertIn("main.js", names)
            self.assertIn("modules/core.wasm", names)

    def test_missing_runtime_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory)
            (package / "manifest.json").write_text(
                json.dumps({"toolpkg_id": "com.operit.test", "main": "dist/main.js"}),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(FileNotFoundError, "Missing ToolPkg runtime file"):
                _manifest_runtime_files(package)

    def test_runtime_path_cannot_escape_package(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory)
            (package / "manifest.json").write_text(
                json.dumps({"toolpkg_id": "com.operit.test", "main": "../outside.js"}),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "escapes the package directory"):
                _manifest_runtime_files(package)

    def test_runtime_symlink_cannot_escape_package(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / "package"
            package.mkdir()
            outside = root / "outside.js"
            outside.write_text("outside\n", encoding="utf-8")
            (package / "main.js").symlink_to(outside)
            (package / "manifest.json").write_text(
                json.dumps({"toolpkg_id": "com.operit.test", "main": "main.js"}),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "symbolic link"):
                _manifest_runtime_files(package)


if __name__ == "__main__":
    unittest.main()
