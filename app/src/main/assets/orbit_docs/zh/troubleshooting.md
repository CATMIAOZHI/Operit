# 常见错误

这里按“症状”记下 Orbit 实际踩过的坑。
报错没说清怎么改、同一个错重复出现，或者想换一条绕远的路之前，先在这里找同样的症状。
找不到时，按报错去读对应功能的文档；都没有答案，就告诉用户卡在哪里，不要自己硬绕。

## git push / pull 提示认证失败

症状：`invalid credentials`、`Authentication failed`，或者 git 停下来问用户名密码。

- GitHub 的 git 走 Basic 认证：用户名填 `x-access-token`，密码填令牌。用 `http.extraheader` 加 `bearer` 头不行。
- 保险箱的 `terminal` 代理只放行存密钥时登记的 API 主机（比如 `api.github.com`），git 用的 `github.com` 不在里面，git 不能走这个代理。
- 要在终端里用 git，令牌得是用户允许载入终端的（`list` 里有 `env_name`）。没有就用 `request_update` 请用户打开。`env` 载入的是真值，可以直接给 git 用。
- 在同一条终端命令里先载入，再用凭证助手把令牌交给 git，令牌不会出现在命令行和输出里：

```
<env 返回的载入命令> && git -c credential.helper= -c credential.helper='!f(){ test "$1" = get && echo username=x-access-token && echo "password=$GITHUB_TOKEN"; }; f' push origin <分支>
```

把 `GITHUB_TOKEN` 换成实际的变量名。不要把令牌写进 remote 地址、`.git/config` 或任何文件。
git 能用时就用 git，不要改用 REST 接口一个个造 blob、tree、commit 来绕开：步骤多、容易出错，也跑不了 git hooks。

## 终端命令返回 running，没有输出

症状：`terminal` 返回 `status: running` 和 `runId`，`output` 是空的。

- 命令还在跑。用 `terminal_wait` 带上 `runId` 继续等，拿到最新输出和结束状态；不想等了用 `terminal_cancel`。
- 不用另开命令去猜进程还在不在、产物有没有生成。
- 日志很长的命令（编译、下载）把输出重定向到文件，结束后再读需要的部分。

## 终端提示缺少参数

症状：`Missing required parameters: …`。

`terminal`（`bash` 同样）每次都要传齐 `command`、`background`、`timeoutMs`、`yieldMs`。
一般用 `background="false"`、`timeoutMs="1800000"`、`yieldMs="10000"`；只有要放到后台、立刻返回时才用 `background="true"`。

## 在手机上编译 Android 工程，strip 失败

症状：`StripDebugSymbolsRunnable` 失败，`llvm-strip` 无法启动，报 `cannot execute: required file not found`。

- 终端是 ARM（aarch64）的 Ubuntu，NDK 里的工具只有 x86_64 版本，在这里跑不起来。Kotlin/Java 编译、资源、dex 这些步骤可以照常检查。
- 不要用 `-x stripDebugDebugSymbols` 之类跳过 strip：打包用的是这一步的产物，跳过后 APK 里一个 `.so` 都没有，编译不报错，装上就崩。
- 工程只带现成的 `.so`、自己不编译原生代码（没有 `externalNativeBuild`、CMake 或 ndk-build）时，可以让 Gradle 找不到 NDK（比如临时把 NDK 目录改名，用完改回来）。这时 AGP 会提示 `Unable to strip the following libraries, packaging them as they are`，把库原样打进去。出包后检查 APK 里的 `.so` 数量对不对。
- 工程要编译原生代码，或者 `ndkVersion` 写死后 Gradle 会去下载 NDK，就没法在手机上完整出包。只做编译检查，告诉用户完整出包要在电脑上做。
- 用变通做法前先告诉用户；能在电脑上编译就建议用电脑。

## 编译提示 JVM 目标版本不一致

症状：`Inconsistent JVM-target compatibility`，比如 Java 是 17、Kotlin 是 21。

先看报错里两边的版本，再看工程有没有给 Kotlin 设 `jvmTarget` 或 `jvmToolchain`。
常见情况是工程把 Java 设成 17、Kotlin 没设，于是跟着当前 JDK（比如 21）走。这时只在这次编译里把 `JAVA_HOME` 指向 JDK 17。
工程自己设了不同的版本时，按工程的要求准备 JDK。不要为了在手机上编过去去改用户工程的配置，除非用户要求。
