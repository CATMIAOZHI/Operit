# Draws the README pictures (docs/assets/readme/screens/en|zh) from Orbit's real screens on this
# computer, with the made-up demo data in app/src/test/.../readme/ReadmeScreenshots.kt.
# Needs a JDK 21 and the Visual Studio C++ build tools (for the chat's markdown library).
# Usage, from the repository root:  powershell -File tools/readme/readme-shots.ps1
$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location $root
$logs = Join-Path $root "app/build/readme-logs"
New-Item -ItemType Directory -Force $logs | Out-Null

# Runs a command with its output in a log, and stops it (with everything it started) after $minutes.
function Invoke-Logged([string]$name, [string]$file, [string]$arguments, [int]$minutes) {
    $log = Join-Path $logs "$name.log"
    $p = Start-Process -FilePath $file -ArgumentList $arguments -NoNewWindow -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    # Without holding the handle, PowerShell loses the exit code of a redirected process.
    $null = $p.Handle
    if (-not $p.WaitForExit($minutes * 60 * 1000)) {
        & taskkill /PID $p.Id /T /F | Out-Null
        Get-Content $log, "$log.err" -Tail 40 -ErrorAction SilentlyContinue
        throw "$name took longer than $minutes minutes and was stopped. Log: $log"
    }
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) {
        Get-Content $log, "$log.err" -Tail 40 -ErrorAction SilentlyContinue
        throw "$name failed (exit code $($p.ExitCode)). Log: $log"
    }
}

# The chat lays out its text with a small C++ library; build it for this computer, fresh each time.
$native = Join-Path $root "app/build/readme-native"
New-Item -ItemType Directory -Force $native | Out-Null
Remove-Item -Force "$native\streamnative.dll" -ErrorAction SilentlyContinue
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw "The Visual Studio C++ build tools are needed." }
$jdk = Get-ChildItem "$env:ProgramFiles\*\jdk-21*" -Directory | Select-Object -First 1
if (-not $jdk) { throw "A JDK 21 is needed." }
$src = "app/src/main/cpp/streamnative"
$files = @("native_xml_splitter.cpp", "native_markdown_splitter.cpp", "StreamOperators.cpp", "plugins/StreamXmlPlugin.cpp",
    "plugins/BaseJsonPlugin.cpp", "plugins/StreamJsonPlugin.cpp", "plugins/StreamPureJsonPlugin.cpp",
    "plugins/StreamMarkdownPlugin.cpp", "StreamBuilders.cpp", "HotStream.cpp", "StringExtensions.cpp") | ForEach-Object { "`"$src/$_`"" }
$cl = "cl /nologo /std:c++17 /O2 /EHsc /MT /utf-8 /LD /I`"$($jdk.FullName)\include`" /I`"$($jdk.FullName)\include\win32`" " +
    "/I`"app/src/main/cpp`" /Fo`"$native\\`" /Fe`"$native\streamnative.dll`" $($files -join ' ')"
Invoke-Logged "native" "cmd.exe" "/c `"`"$vs\VC\Auxiliary\Build\vcvars64.bat`" >nul && $cl`"" 10
if (-not (Test-Path "$native\streamnative.dll")) { throw "Building the markdown library failed. Log: $logs\native.log" }

$shots = Join-Path $root "docs/assets/readme/screens"
Invoke-Logged "gradle" (Join-Path $root "gradlew.bat") `
    ":app:testDebugUnitTest --tests `"com.ai.assistance.operit.readme.*`" `"-PorbitReadmeShots=$shots`" --no-daemon --console=plain" 30
Get-ChildItem -Recurse $shots -Filter *.png | ForEach-Object { $_.FullName }
