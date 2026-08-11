from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


def windows_short_path(path: Path) -> str:
    import ctypes

    buffer = ctypes.create_unicode_buffer(32768)
    length = ctypes.windll.kernel32.GetShortPathNameW(str(path), buffer, len(buffer))
    return buffer.value if 0 < length < len(buffer) else str(path)


ROOT = Path(__file__).resolve().parents[2]
CPP_ROOT = ROOT / "app" / "src" / "main" / "cpp"
HARNESS = ROOT / "ci" / "test" / "native" / "stream_xml_plugin_test.cpp"
PLUGIN = CPP_ROOT / "streamnative" / "plugins" / "StreamXmlPlugin.cpp"
MARKDOWN_PLUGIN = CPP_ROOT / "streamnative" / "plugins" / "StreamMarkdownPlugin.cpp"
OPERATORS = CPP_ROOT / "streamnative" / "StreamOperators.cpp"


def java_include_dirs() -> list[Path]:
    homes: list[Path] = []
    configured = os.environ.get("JAVA_HOME")
    if configured:
        homes.append(Path(configured))
    javac = shutil.which("javac")
    if javac:
        homes.append(Path(javac).resolve().parent.parent)

    platform_dir = "win32" if os.name == "nt" else ("darwin" if sys.platform == "darwin" else "linux")
    for home in homes:
        include = home / "include"
        platform = include / platform_dir
        if include.is_dir() and platform.is_dir():
            return [include, platform]
    raise AssertionError("A JDK with JNI headers is required for the native Markdown session regression")


def find_compiler() -> tuple[str, str] | None:
    configured = os.environ.get("CXX")
    if configured and shutil.which(configured):
        resolved = str(shutil.which(configured))
        return ("msvc" if Path(resolved).name.lower() == "cl.exe" else "unix", resolved)
    for candidate in ("c++", "g++", "clang++"):
        resolved = shutil.which(candidate)
        if resolved:
            return ("unix", resolved)

    if os.name == "nt":
        vswhere = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / (
            "Microsoft Visual Studio/Installer/vswhere.exe"
        )
        if vswhere.is_file():
            result = subprocess.run(
                [
                    str(vswhere),
                    "-latest",
                    "-products",
                    "*",
                    "-requires",
                    "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                    "-property",
                    "installationPath",
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            installation = Path(result.stdout.strip()) if result.returncode == 0 else None
            if installation:
                vcvars = installation / "VC/Auxiliary/Build/vcvars64.bat"
                if vcvars.is_file():
                    return ("msvc-vcvars", windows_short_path(vcvars))
    return None


class NativeStreamXmlPluginTest(unittest.TestCase):
    def test_production_plugin_matches_display_block_grammar(self) -> None:
        compiler = find_compiler()
        self.assertIsNotNone(compiler, "A host C++17 compiler is required for the native XML regression")
        includes = [CPP_ROOT, *java_include_dirs()]
        sources = [PLUGIN, MARKDOWN_PLUGIN, OPERATORS, HARNESS]
        with tempfile.TemporaryDirectory(prefix="operit-stream-xml-") as temp_dir:
            executable = Path(temp_dir) / ("stream_xml_plugin_test.exe" if os.name == "nt" else "stream_xml_plugin_test")
            compiler_kind, compiler_path = compiler
            if compiler_kind == "msvc-vcvars":
                include_args = " ".join(f"/I{windows_short_path(path)}" for path in includes)
                source_args = " ".join(str(path) for path in sources)
                temp_short = windows_short_path(Path(temp_dir))
                command = [
                    "cmd.exe",
                    "/d",
                    "/c",
                    (
                        f'call {compiler_path} >nul && cl /nologo /std:c++17 /EHsc '
                        f'{include_args} {source_args} /Fo:{temp_short}\\ /Fe:{executable}'
                    ),
                ]
            elif compiler_kind == "msvc":
                command = [
                    compiler_path,
                    "/nologo",
                    "/std:c++17",
                    "/EHsc",
                    *(f"/I{path}" for path in includes),
                    *(str(path) for path in sources),
                    f"/Fo:{temp_dir}\\",
                    f"/Fe:{executable}",
                ]
            else:
                command = [
                    compiler_path,
                    "-std=c++17",
                    *(arg for path in includes for arg in ("-I", str(path))),
                    *(str(path) for path in sources),
                    "-o",
                    str(executable),
                ]
            compile_result = subprocess.run(
                command,
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                check=False,
            )
            self.assertEqual(
                0,
                compile_result.returncode,
                f"native XML regression failed to compile:\n{compile_result.stdout}\n{compile_result.stderr}",
            )
            run_result = subprocess.run(
                [str(executable)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                check=False,
            )
            self.assertEqual(
                0,
                run_result.returncode,
                f"native XML regression failed:\n{run_result.stdout}\n{run_result.stderr}",
            )


if __name__ == "__main__":
    unittest.main()
