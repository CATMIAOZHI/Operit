#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF' >&2
Usage: run_android_checks.sh --lane <jvm|full> [--instrumentation] [--no-daemon]

Run Android Gradle checks for the given lane.

Options:
  --lane <jvm|full>    required: select the check lane
  --instrumentation     also compile Android instrumentation tests
  --no-daemon           pass --no-daemon to Gradle (default: enabled)
  --continue            pass --continue to Gradle so the build doesn't stop on first failure
  --stacktrace          pass --stacktrace to Gradle
  --profile             pass --profile to Gradle (generates build reports)
  --console <mode>      pass --console=<mode> to Gradle (e.g. plain)
  --extra-tasks <...>   additional Gradle tasks to append

Environment:
  ANDROID_BUILD_TOOLS_VERSION  default: 35.0.0
EOF
    exit 2
}

LANE=""
INSTRUMENTATION=false
NO_DAEMON_FLAG="--no-daemon"
CONTINUE_FLAG=""
STACKTRACE_FLAG="--stacktrace"
PROFILE_FLAG=""
CONSOLE_FLAG=""
EXTRA_TASKS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --lane) LANE="$2"; shift 2 ;;
        --instrumentation) INSTRUMENTATION=true; shift ;;
        --no-daemon) NO_DAEMON_FLAG="--no-daemon"; shift ;;
        --continue) CONTINUE_FLAG="--continue"; shift ;;
        --stacktrace) STACKTRACE_FLAG="--stacktrace"; shift ;;
        --profile) PROFILE_FLAG="--profile"; shift ;;
        --console) CONSOLE_FLAG="--console=$2"; shift 2 ;;
        --extra-tasks)
            shift
            while [[ $# -gt 0 && "$1" != --* ]]; do
                EXTRA_TASKS+=("$1")
                shift
            done
            ;;
        *) usage ;;
    esac
done

if [[ "$LANE" != "jvm" && "$LANE" != "full" ]]; then
    echo "run_android_checks.sh: invalid lane '$LANE' (expected jvm or full)" >&2
    usage
fi

chmod +x ./gradlew

tasks=()
case "$LANE" in
    jvm)
        tasks=(":app:testDebugUnitTest" ":app:lintDebug")
        ;;
    full)
        tasks=(":app:assembleDebug" ":app:testDebugUnitTest" ":app:lintDebug")
        ;;
esac

if [[ "$INSTRUMENTATION" == "true" ]]; then
    tasks+=(":app:compileDebugAndroidTestKotlin" ":app:compileDebugAndroidTestJavaWithJavac")
fi

tasks+=("${EXTRA_TASKS[@]}")

gradle_flags=("$STACKTRACE_FLAG" "$NO_DAEMON_FLAG")
[[ -n "$CONTINUE_FLAG" ]] && gradle_flags+=("$CONTINUE_FLAG")
[[ -n "$PROFILE_FLAG" ]] && gradle_flags+=("$PROFILE_FLAG")
[[ -n "$CONSOLE_FLAG" ]] && gradle_flags+=("$CONSOLE_FLAG")

echo "run_android_checks.sh: lane=$LANE tasks=${tasks[*]}"
./gradlew "${tasks[@]}" "${gradle_flags[@]}"
