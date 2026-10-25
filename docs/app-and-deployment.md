# On-device benchmark app: what was built, and how to run it

This note records the Android side of the LLIE mobile benchmark as it stands
after the 2026-10-25 session: the app, the upload server, the export/parity
pipeline, and the measured results so far.

## 1. Model set (8 exported artifacts)

| model_id | family | params | ONNX bytes | parity (max abs err, host) |
|---|---|---:|---:|---:|
| zero-dce | curve adjustment | 79,416 | 324,671 | 2.4e-7 |
| sci-medium | illumination division | 252 | 3,560 | 1.8e-6 |
| ruas-lol | unrolled Retinex | 1,161 | 44,969 | 6.0e-7 |
| zero-dce-plus | curve (depthwise-separable, scale=4) | 10,561 | 52,713 | 5.4e-7 |
| pairlie | self-supervised decomposition (gamma 0.14) | 341,767 | 1,412,186 | 5.4e-7 |
| mobileie-6ch | reparameterized CNN | 23,882 | 101,471 | n/a (phase 1) |
| lyt-net | YUV transformer | 44,923 | 230,030 | 4.6e-4 |
| retinexformer-lolv1 | one-stage Retinex transformer | 1,605,701 | 6,778,299 | 6.0e-7 |

The three new models follow the existing gate: archived checkpoint (read-only
outside the repo) â†’ loader wrapper in
`benchmark/tools/compatibility/model_wrappers.py` â†’ pytest parity (â‰¤1e-3 on
3x64x96) â†’ ONNX export with dynamic H/W â†’ schema-checked manifest under
`benchmark/reports/compatibility/`.

Deviations from upstream defaults, all documented in `benchmark/models/README.md`:
- zero-dce-plus exports with `scale_factor=4` (upstream test uses 12 and crops
  to multiples of 12; 600x400, 512x512 and 64x96 are multiples of 4).
- pairlie reconstructs `clamp(L**0.14 * R, 0, 1)` in-graph.
- retinexformer clamps to `[0,1]` in-graph; checkpoint state is unwrapped from
  BasicSR's `{"params": ...}`.

## 2. Android app

Single-activity Kotlin app (Views, no Compose), package
`org.llie.mobilebenchmark`, versionName 0.2.0, protocol v2.

Screens/features:
- **Live monitor**: model name, phase badge, battery-temperature pill
  (green/amber/red), image/pass progress with percentage, elapsed time, last
  pass, median pass, ETA, PSS peak, thermal status, and a 2x4 per-core MHz
  grid with mini-bars. A 1 Hz ticker only renders cached values; a single
  background sampler thread does every battery/sysfs/file read.
- **Test all**: runs every staged model heavyâ†’light, waits for the battery to
  drop to 38 Â°C between models, skips models that already have a valid v2 run,
  and uploads each finished run. State lives in `TestAllState` (process-wide),
  so rotation/resize/fold re-attaches instead of starting a second queue.
- **Stop**: two-step confirm; latches `RunControl`; the current run closes with
  `success=false, failure_reason="stopped by user"` and the queue halts.
- **Setup from server**: downloads the 12.7 MB staging bundle
  (`models/`, `manifests/`, `images/eval15/`) and unpacks it defensively
  (absolute and `..` entries rejected). A new phone needs only the APK.
- **Device label**: one clearly bordered field; replaces `Build.MODEL-DEVICE`
  in the upload path and `meta`, so identical phones stay distinguishable.
- **Auto upload**: after every run (button or ADB) the app posts
  `<model>-run.json`, `<model>.status` and the output PNGs; failures are kept
  in a retry set and retried on next launch.
- **ADB path unchanged**: `am start -a org.llie.bench.RUN` with a `run_token`
  extra equal to `BuildConfig.RUN_TOKEN`; the activity ignores runs without it.

Measurement path is untouched by the UI: the pass callback reuses clock
boundaries that already exist, and the stop flag is a single volatile read per
pass. Protocol: batch 1, warm-up 5, 10 measured passes, 15 eval15 images,
`SystemClock.elapsedRealtimeNanos`.

## 3. Upload server

- Host `soobaroTR` (netlen ID `IBTH9PH`, 91.228.227.160, Ubuntu 22.04, Docker).
- Container `llie-receiver`, port `8770 â†’ 8000`, data on `/srv/llie-uploads`,
  token in `/opt/llie-receiver/.env` (0600, LF line endings â€” a CRLF token
  silently 401s every upload).
- Endpoints: `GET /health`, `POST /upload` (multipart `meta` + files),
  `GET /runs`, `GET /bundle/info`, `GET /bundle`.
- Storage layout: `/srv/llie-uploads/<device>/<model>/<run_id>/` plus one line
  per upload in `index.jsonl`. `run.json` carries the device fields
  (`model`, Android release/SDK, build fingerprint, SoC) so a run is traceable
  to the phone.
- `.env` example and Dockerfile/compose live in `benchmark/server/receiver/`;
  the bundle is built with `benchmark/server/make_bundle.py`.
- Transport is cleartext HTTP (Android requires `usesCleartextTraffic`), with a
  bearer token. A domain + Caddy/HTTPS is the next hardening step.

## 4. Build & release

- CI: `.github/workflows/android-ci.yml` (unit tests + `assembleDebug` +
  artifact upload) on push to main, PRs, and `workflow_dispatch`.
- ABI splits: `arm64-v8a`, `armeabi-v7a`, `x86_64`, plus a universal fallback
  (`android/app/build.gradle.kts`, `splits.abi`). Debug APK sizes ~21 MB /
  ~16 MB / ~24 MB, universal ~56 MB.
- Releases: `.github/workflows/release.yml` on tags `v*` builds the four APKs,
  writes `SHA256SUMS`, verifies `lib/<abi>/libonnxruntime.so`, and publishes a
  GitHub release.
- Server URL and token are baked into `BuildConfig` (`SERVER_URL`,
  `SERVER_TOKEN`) so nobody types them on the phone; the host-issued `run_token`
  is a separate build constant.

## 5. Verification record (25 Oct 2026)

- `python -m pytest host/tests tools/compatibility` â†’ all green
  (unit tests include: monitor progress/ETA/median, stop latch, pass callback,
  test-all ordering/skip logic, multipart encoding, bundle path traversal,
  device label sanitisation, activity recreation).
- Export suite: 7/7 models exported and passed host parity at 600Ã—400.
- Phone (2a) forced-run results: zero-dce-plus 84.3 ms / 15.19 dB; pairlie
  3664 ms / 19.51 dB (dominated by LYT-Net on this device); retinexformer
  6035 ms / 25.07 dB, PSS 1023 MB (new quality ceiling).
- Upload verified end-to-end twice (manual curl, and an app-driven run at
  `/srv/llie-uploads/2a-lab/...`).
- A stopped run uploaded correctly with `failure_reason: "stopped by user"`.
- `Setup from server` staged 31 files (8 models, 8 manifests, 15 images) on a
  fresh install without ADB.

## 6. Open items

- Screenshot of the monitor screen for the public repo (needs the phone
  reconnected).
- Redmi Note 10 Pro and Redmi 9T runs for the three new models
  (retinexformer needs `--timeout 3600` on the 9T; it is ~6 s/pass on the 2a).
- Paper regeneration (8-model tables, Pareto figure, architecture rows).
- Optional: HTTPS for the receiver, EncryptedSharedPreferences for the token,
  LiteRT backend, jacoco + Espresso coverage.
