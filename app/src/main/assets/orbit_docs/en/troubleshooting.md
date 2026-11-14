# Common Mistakes

Pitfalls Orbit has actually hit, listed by symptom.
When an error does not say how to fix it, the same error repeats, or you are about to take a long way around, look for the same symptom here first.
If it is not here, read the doc for that feature; if nothing answers it, tell the user where you are stuck instead of forcing a workaround.

## git push / pull fails to authenticate

Symptom: `invalid credentials`, `Authentication failed`, or git stops to ask for a user name and password.

- Git on GitHub uses Basic auth: user name `x-access-token`, password the token. A `bearer` header through `http.extraheader` does not work.
- The vault's `terminal` proxy only allows the API hosts registered with the key (such as `api.github.com`). Git uses `github.com`, which is not one of them, so git cannot go through that proxy.
- To use git in the terminal, the token must be one the user allowed into the terminal (it has an `env_name` in `list`). If not, ask with `request_update`. `env` loads the real value, which git can use directly.
- In one terminal command, load it and hand it to git through a credential helper, so the token never appears in the command line or output:

```
<load command from env> && git -c credential.helper= -c credential.helper='!f(){ test "$1" = get && echo username=x-access-token && echo "password=$GITHUB_TOKEN"; }; f' push origin <branch>
```

Replace `GITHUB_TOKEN` with the actual variable name. Never put the token in the remote URL, `.git/config` or any file.
When git works, use git; do not route around it through the REST API (blobs, trees, commits one by one): it takes many steps, is easy to get wrong, and runs no git hooks.

## A terminal command returns running with no output

Symptom: `terminal` returns `status: running` and a `runId`, and `output` is empty.

- The command is still running. Call `terminal_wait` with the `runId` to keep waiting and get the latest output and final status; use `terminal_cancel` to stop it.
- Do not start other commands to guess whether the process is alive or the output exists.
- For commands with long logs (builds, downloads), redirect output to a file and read the part you need when it ends.

## The terminal says parameters are missing

Symptom: `Missing required parameters: …`.

`terminal` (and `bash`) needs all of `command`, `background`, `timeoutMs` and `yieldMs` on every call.
Usually `background="false"`, `timeoutMs="1800000"`, `yieldMs="10000"`; use `background="true"` only to run in the background and return at once.

## Building an Android project on the phone fails at strip

Symptom: `StripDebugSymbolsRunnable` fails, `llvm-strip` cannot start, `cannot execute: required file not found`.

- The terminal is an ARM (aarch64) Ubuntu; the NDK only ships x86_64 tools, which cannot run here. Kotlin/Java compilation, resources and dex can still be checked.
- Do not skip strip with `-x stripDebugDebugSymbols` or similar: packaging uses that step's output, so the APK ends up with no `.so` at all. The build succeeds and the app crashes when opened.
- When the project only ships prebuilt `.so` files and compiles no native code (no `externalNativeBuild`, CMake or ndk-build), you can keep Gradle from finding the NDK (for example, rename the NDK folder for the build and rename it back after). AGP then reports `Unable to strip the following libraries, packaging them as they are` and packages the libraries unchanged. Check the number of `.so` files in the APK afterwards.
- When the project compiles native code, or a fixed `ndkVersion` makes Gradle download the NDK, a complete APK cannot be built on the phone. Only check compilation, and tell the user the full build needs a computer.
- Tell the user before using a workaround; suggest a computer when one is available.

## The build reports inconsistent JVM targets

Symptom: `Inconsistent JVM-target compatibility`, for example Java 17 vs Kotlin 21.

Read both versions in the error, then check whether the project sets `jvmTarget` or `jvmToolchain` for Kotlin.
A common case: the project sets Java to 17 and leaves Kotlin unset, so Kotlin follows the current JDK (21, say). Then point `JAVA_HOME` at JDK 17 for this build only.
When the project sets different versions itself, prepare the JDK it asks for. Do not change the user's project settings just to build on the phone, unless the user asks.
