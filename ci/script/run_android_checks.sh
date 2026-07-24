#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF' >&2
Usage: run_android_checks.sh --lane <jvm|full|build> [options]

Run the shared Android Gradle task set for the given lane.

Options:
  --lane <lane>         required: select jvm, full, or build
  --task <task>         add a Gradle task (required for the build lane)
  --unit-tests          add :app:testDebugUnitTest
  --lint                add :app:lintDebug
  --instrumentation     also compile Android instrumentation tests
  --continue            pass --continue to Gradle so the build doesn't stop on first failure
  --profile             pass --profile to Gradle (generates build reports)
  --console <mode>      pass --console=<mode> to Gradle (e.g. plain)

The runner always passes --stacktrace and --no-daemon.
EOF
    exit 2
}

LANE=""
INSTRUMENTATION=false
RUN_UNIT_TESTS=false
RUN_LINT=false
CONTINUE_FLAG=""
PROFILE_FLAG=""
CONSOLE_FLAG=""
CUSTOM_TASKS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --lane)
            [[ $# -ge 2 ]] || usage
            LANE="$2"
            shift 2
            ;;
        --task)
            [[ $# -ge 2 ]] || usage
            CUSTOM_TASKS+=("$2")
            shift 2
            ;;
        --unit-tests) RUN_UNIT_TESTS=true; shift ;;
        --lint) RUN_LINT=true; shift ;;
        --instrumentation) INSTRUMENTATION=true; shift ;;
        --continue) CONTINUE_FLAG="--continue"; shift ;;
        --profile) PROFILE_FLAG="--profile"; shift ;;
        --console)
            [[ $# -ge 2 ]] || usage
            CONSOLE_FLAG="--console=$2"
            shift 2
            ;;
        *) usage ;;
    esac
done

tasks=()
case "$LANE" in
    jvm)
        tasks=(":app:testDebugUnitTest" ":app:lintDebug")
        ;;
    full)
        tasks=(":app:assembleDebug" ":app:testDebugUnitTest" ":app:lintDebug")
        ;;
    build)
        if (( ${#CUSTOM_TASKS[@]} == 0 )); then
            echo "run_android_checks.sh: build lane requires at least one --task" >&2
            exit 2
        fi
        tasks=("${CUSTOM_TASKS[@]}")
        ;;
    *)
        echo "run_android_checks.sh: invalid lane '$LANE' (expected jvm, full, or build)" >&2
        exit 2
        ;;
esac

if [[ "$RUN_UNIT_TESTS" == "true" ]]; then
    tasks+=(":app:testDebugUnitTest")
fi
if [[ "$RUN_LINT" == "true" ]]; then
    tasks+=(":app:lintDebug")
fi
if [[ "$INSTRUMENTATION" == "true" ]]; then
    tasks+=(":app:compileDebugAndroidTestKotlin" ":app:compileDebugAndroidTestJavaWithJavac")
fi

gradle_flags=("--stacktrace" "--no-daemon")
[[ -n "$CONTINUE_FLAG" ]] && gradle_flags+=("$CONTINUE_FLAG")
[[ -n "$PROFILE_FLAG" ]] && gradle_flags+=("$PROFILE_FLAG")
[[ -n "$CONSOLE_FLAG" ]] && gradle_flags+=("$CONSOLE_FLAG")

chmod +x ./gradlew
echo "run_android_checks.sh: lane=$LANE tasks=${tasks[*]}"
./gradlew "${tasks[@]}" "${gradle_flags[@]}"
