from __future__ import annotations

import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import time
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
COMPILE_TIMEOUT_SECONDS = 120
HARNESS_TIMEOUT_SECONDS = 20
TERMINATION_TIMEOUT_SECONDS = 10


def attach_windows_kill_job(process: subprocess.Popen[str]) -> bool:
    if os.name != "nt":
        return False

    import ctypes
    from ctypes import wintypes

    class IoCounters(ctypes.Structure):
        _fields_ = [
            ("ReadOperationCount", ctypes.c_ulonglong),
            ("WriteOperationCount", ctypes.c_ulonglong),
            ("OtherOperationCount", ctypes.c_ulonglong),
            ("ReadTransferCount", ctypes.c_ulonglong),
            ("WriteTransferCount", ctypes.c_ulonglong),
            ("OtherTransferCount", ctypes.c_ulonglong),
        ]

    class BasicLimitInformation(ctypes.Structure):
        _fields_ = [
            ("PerProcessUserTimeLimit", ctypes.c_longlong),
            ("PerJobUserTimeLimit", ctypes.c_longlong),
            ("LimitFlags", wintypes.DWORD),
            ("MinimumWorkingSetSize", ctypes.c_size_t),
            ("MaximumWorkingSetSize", ctypes.c_size_t),
            ("ActiveProcessLimit", wintypes.DWORD),
            ("Affinity", ctypes.c_size_t),
            ("PriorityClass", wintypes.DWORD),
            ("SchedulingClass", wintypes.DWORD),
        ]

    class ExtendedLimitInformation(ctypes.Structure):
        _fields_ = [
            ("BasicLimitInformation", BasicLimitInformation),
            ("IoInfo", IoCounters),
            ("ProcessMemoryLimit", ctypes.c_size_t),
            ("JobMemoryLimit", ctypes.c_size_t),
            ("PeakProcessMemoryUsed", ctypes.c_size_t),
            ("PeakJobMemoryUsed", ctypes.c_size_t),
        ]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    kernel32.CreateJobObjectW.restype = wintypes.HANDLE
    kernel32.SetInformationJobObject.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    kernel32.SetInformationJobObject.restype = wintypes.BOOL
    kernel32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    kernel32.AssignProcessToJobObject.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL

    job = kernel32.CreateJobObjectW(None, None)
    if not job:
        return False
    info = ExtendedLimitInformation()
    info.BasicLimitInformation.LimitFlags = 0x00002000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    if not kernel32.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info)):
        kernel32.CloseHandle(job)
        return False
    process_handle = wintypes.HANDLE(int(process._handle))  # type: ignore[attr-defined]
    if not kernel32.AssignProcessToJobObject(job, process_handle):
        kernel32.CloseHandle(job)
        return False
    setattr(process, "_operit_kill_job", int(job))
    return True


def close_windows_kill_job(process: subprocess.Popen[str]) -> bool:
    handle = getattr(process, "_operit_kill_job", None)
    if os.name != "nt" or handle is None:
        return False
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    setattr(process, "_operit_kill_job", None)
    return bool(kernel32.CloseHandle(wintypes.HANDLE(handle)))


def terminate_process_tree(process: subprocess.Popen[str]) -> bool:
    if os.name == "nt":
        if close_windows_kill_job(process):
            return True
        try:
            result = subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                capture_output=True,
                check=False,
                timeout=TERMINATION_TIMEOUT_SECONDS,
            )
            if result.returncode == 0 or process.poll() is not None:
                return True
        except (OSError, subprocess.TimeoutExpired):
            pass

        # A failed taskkill cannot be treated as successful tree termination. Kill and reap the
        # direct process within a second bounded window, then report failure to the caller instead
        # of entering an unbounded communicate() while a descendant may still own the pipes.
        try:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=TERMINATION_TIMEOUT_SECONDS)
        except (OSError, subprocess.TimeoutExpired):
            pass
        return False
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return True


def run_bounded(
    command: list[str],
    *,
    cwd: Path,
    timeout_seconds: int,
    description: str,
) -> subprocess.CompletedProcess[str]:
    process_options: dict[str, object] = {}
    if os.name == "nt":
        process_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        process_options["start_new_session"] = True

    process = subprocess.Popen(
        command,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        **process_options,
    )
    attach_windows_kill_job(process)
    try:
        stdout, stderr = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as error:
        tree_terminated = terminate_process_tree(process)
        if process.poll() is None:
            try:
                process.wait(timeout=TERMINATION_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                process.kill()
                try:
                    process.wait(timeout=TERMINATION_TIMEOUT_SECONDS)
                except subprocess.TimeoutExpired:
                    pass
        stdout = error.stdout or ""
        stderr = error.stderr or ""
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()
        raise AssertionError(
            f"{description} exceeded {timeout_seconds}s; "
            f"process-tree termination={'succeeded' if tree_terminated else 'failed'}\n"
            f"stdout:\n{stdout}\nstderr:\n{stderr}"
        ) from error

    close_windows_kill_job(process)
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


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
                timeout=TERMINATION_TIMEOUT_SECONDS,
            )
            installation = Path(result.stdout.strip()) if result.returncode == 0 else None
            if installation:
                vcvars = installation / "VC/Auxiliary/Build/vcvars64.bat"
                if vcvars.is_file():
                    return ("msvc-vcvars", windows_short_path(vcvars))
    return None


class NativeStreamXmlPluginTest(unittest.TestCase):
    def test_bounded_runner_terminates_spawned_process_tree(self) -> None:
        with tempfile.TemporaryDirectory(prefix="operit-stream-timeout-") as temp_dir:
            marker = Path(temp_dir) / "child-survived.txt"
            child_code = (
                "import time; from pathlib import Path; "
                f"time.sleep(3); Path({str(marker)!r}).write_text('alive', encoding='utf-8')"
            )
            parent_code = (
                "import subprocess, sys, time; "
                f"subprocess.Popen([sys.executable, '-c', {child_code!r}]); time.sleep(60)"
            )

            with self.assertRaisesRegex(AssertionError, "exceeded 1s; process-tree termination=succeeded"):
                run_bounded(
                    [sys.executable, "-c", parent_code],
                    cwd=ROOT,
                    timeout_seconds=1,
                    description="process-tree regression",
                )

            time.sleep(4)
            self.assertFalse(marker.exists(), "timed-out child process survived its parent")

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
            compile_result = run_bounded(
                command,
                cwd=ROOT,
                timeout_seconds=COMPILE_TIMEOUT_SECONDS,
                description="native XML regression compiler",
            )
            self.assertEqual(
                0,
                compile_result.returncode,
                f"native XML regression failed to compile:\n{compile_result.stdout}\n{compile_result.stderr}",
            )
            run_result = run_bounded(
                [str(executable)],
                cwd=ROOT,
                timeout_seconds=HARNESS_TIMEOUT_SECONDS,
                description="native XML regression harness",
            )
            self.assertEqual(
                0,
                run_result.returncode,
                f"native XML regression failed:\n{run_result.stdout}\n{run_result.stderr}",
            )


if __name__ == "__main__":
    unittest.main()
