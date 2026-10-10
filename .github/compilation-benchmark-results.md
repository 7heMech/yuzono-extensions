# Compilation benchmark — 2026-10-10

Recommendation: warm shared Debug outputs in the master cache-producing build. Narrowing the included extension modules did not show a consistent benefit.

## Method

- Fork: `7heMech/yuzono-extensions`, branch `ci/compilation-benchmark`.
- Source baseline: `934831d0af3b568eabea0d5a8f7f3b2a0da952ca` (fork master at test time).
- Representative target: `:src:en:av1encodes:assembleDebug`.
- All samples apply the same source edit to the extension name to force extension compilation.
- Each sample starts on a fresh `ubuntu-latest` runner with Java 25 and the repository Gradle configuration.
- Seed an ordinary Release APK build, snapshot Gradle User Home, then run `:core:compileDebugKotlin` and snapshot the local build cache again.
- Every benchmark restores the same baseline snapshot; warmed samples additionally restore the Debug build-cache snapshot.
- Measure the entire Gradle build with a monotonic timer, excluding checkout, artifact transfer, and cache extraction. This is build time, not a measurement of Kotlin compilation alone.
- Module subset includes only AV1Encodes under `src`; core, libraries, and themes retain their existing inclusion rules.
- No project-local configuration-cache entries or build outputs are restored. Configuration must run afresh.
- Three samples per configuration per round; 24 measured APK builds passed.

## Results

### Round 1

[GitHub Actions run](https://github.com/7heMech/yuzono-extensions/actions/runs/38039069149)

Round 1 restores the existing full-layout cache for every sample. Round 2 seeds a separate cache for each module layout, to measure narrowed-layout performance after its cache has warmed.

| Included extensions | Cache seed | Samples (seconds) | Median (seconds) | core Debug compilation |
| --- | --- | --- | --- | --- |
| all | release | 44.666, 44.125, 46.779 | 44.666 | 0/3 from cache |
| all | debug | 33.348, 38.559, 40.444 | 38.559 | 3/3 from cache |
| subset | release | 52.587, 43.226, 45.204 | 45.204 | 0/3 from cache |
| subset | debug | 51.199, 42.120, 55.863 | 51.199 | 0/3 from cache |

### Round 2

[GitHub Actions run](https://github.com/7heMech/yuzono-extensions/actions/runs/38039415611)

Caches are seeded separately for the full and narrowed module layouts.

| Included extensions | Cache seed | Samples (seconds) | Median (seconds) | core Debug compilation |
| --- | --- | --- | --- | --- |
| all | release | 35.006, 43.433, 40.647 | 40.647 | 0/3 from cache |
| all | debug | 41.091, 30.731, 24.624 | 30.731 | 3/3 from cache |
| subset | release | 44.721, 41.427, 27.713 | 41.427 | 0/3 from cache |
| subset | debug | 37.452, 36.790, 29.145 | 36.790 | 3/3 from cache |

## Interpretation

- Across both full-layout rounds, median build time fell from **43.779s to 35.953s**, a **7.825s / 17.9%** reduction (six samples per configuration).
- Warming consistently restored core Debug compilation from cache: 6/6 full-layout warmed runs, versus 0/6 baseline runs. Extension Kotlin compilation executed in every measured sample.
- In round 1, narrowing the settings module list invalidated most existing task outputs (2 cached tasks versus 22–24 with the full layout). This was why a second round with matching layout caches was necessary.
- In round 2, full-layout medians were 40.647s baseline and 30.731s warmed; subset medians were 41.427s baseline and 36.790s warmed. Narrowing did not provide a reliable improvement.
- Standalone Debug warming cost 25–27s in the seed jobs. Adding that task to an existing master Gradle invocation may share initialization and task execution, but that incremental master cost was not measured.
- Based on the standalone seed cost and pooled build saving, roughly four subsequent comparable builds would amortize the warmup. This excludes cache-transfer and cache-write costs.
- Hosted runner variability is substantial, and this test covers one extension, not large chunks or all shared libraries.
- Suggested follow-up: include `:core:compileDebugKotlin` in the master job whose setup-gradle cache is writable, then verify real PR cache hits and whole-workflow timings. Leave module inclusion unchanged based on these results.

## Rerun

The branch replaces the existing dispatchable CI workflow with an isolated benchmark workflow. It runs only on manual dispatch and uploads logs/results rather than publishing extensions.

```sh
gh workflow run build_push.yml --repo 7heMech/yuzono-extensions --ref ci/compilation-benchmark
```

Raw results are preserved in `compilation-benchmark-results.json`; the original run logs and snapshots have two-day artifact retention.
