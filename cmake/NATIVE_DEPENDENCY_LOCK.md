# Native Dependency Lock

Pinned commit SHAs for all native dependencies fetched at CMake configure time.
Resolved on 2026-07-22 and verified by a full Nightly build.

| Dependency | Repository | Pinned SHA | Previously tracked ref |
|---|---|---|---|
| sherpa-ncnn | https://github.com/k2-fsa/sherpa-ncnn.git | `c61e50d61e9fbed5972afa4d95bc560e168affe2` | master |
| ncnn | https://github.com/Tencent/ncnn.git | `a4d2ea1d4422c9e849f166fd7a4aefb52f942f6a` | master |
| WAMR | https://github.com/bytecodealliance/wasm-micro-runtime.git | `2ae370d2219b56fa294294b3dae67e588c431eae` | main |
| MNN | https://github.com/alibaba/MNN.git | `b418a758628ac9c5b2422975922cc31736992280` | master |
| KleidiAI | https://github.com/ARM-software/kleidiai.git | `fa0a8e861d58451e0a3a0d1588bc0d49bad23743` | v1.16.0 |
| llama.cpp | https://github.com/ggml-org/llama.cpp.git | `c5a4a0bb832fcdb44487996150b3141490fdff69` | master |
| QuickJS | https://github.com/bellard/quickjs.git | `04be246001599f5995fa2f2d8c91a0f198d3f34c` | master |
| Saba | https://github.com/benikabocha/saba.git | `29b8efa8b31c8e746f9a88020fb0ad9dcdcf3332` | master |
| Bullet3 | https://github.com/bulletphysics/bullet3.git | `63c4d67e337017f9d8b298c900e9aabdb69296e7` | master |
| ufbx | https://github.com/ufbx/ufbx.git | `fcc5d6ba444cfd3eb80677dba5e37e493941abe5` | main |

## Why pin

- Reproducible builds: the same Operit commit always compiles identical native source.
- Stable ccache hit rates: upstream ref movement no longer triggers mass recompilation.
- Build integrity: no sudden build breakage from unrelated upstream changes.

## How to upgrade

1. Resolve the new SHA with `git ls-remote <repo> refs/heads/<branch>` or `refs/tags/<tag>`.
2. Update the SHA in the corresponding `CMakeLists.txt` / `*.cmake` file.
3. Update this manifest.
4. Run a full build and verify the affected module still compiles and passes device smoke tests.
5. Upgrade dependencies individually, not in bulk, so failures are attributable.