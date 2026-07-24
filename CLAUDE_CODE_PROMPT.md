# Build: IoniqScope — personal OBD-II app for Hyundai Ioniq 6

## Role & goal
Build a **personal, non-commercial** Android app (no ads, no analytics, no login, no cloud)
that connects to a **Vgate iCar Pro BLE 4.0** ELM327 adapter and reads live data from a
**Hyundai Ioniq 6 (E-GMP platform)**. This is a hobby project for a single user.
Prioritize a clean, working, maintainable app over feature count.

## Provided files (already written — INTEGRATE, do not rewrite)
Located in `core/`:
- **`ObdEngine.kt`** — `Transport` interface, `ClassicBtTransport`, `Elm327` layer,
  `StandardPids`, `DtcReader`, `ObdEngine` polling loop, and an empty `EgmpPids` placeholder.
  Package: `com.berke.ioniqscope.obd`.
- **`BleTransport.kt`** — BLE (GATT) `Transport` skeleton for the Vgate iCar Pro.
  ⚠️ You MUST verify/complete the service & characteristic UUIDs against the real device
  (log discovered services; common candidates: FFE0/FFE1 or FFF0/FFF1/FFF2).
- **`PerformanceMeter.kt`** — 0-100 km/h, speed/distance splits, max speed, with
  interpolation + auto launch detection. Package: `com.berke.ioniqscope.performance`.

Drop these into the `app` module under the matching packages and build around them.

## Tech stack (required)
- Kotlin, single `app` module, Gradle **Kotlin DSL**.
- **Jetpack Compose + Material 3**, dark-mode first, clean visual hierarchy.
- **MVVM**: ViewModel + StateFlow, Kotlin Coroutines/Flow.
- minSdk **26**, targetSdk + compileSdk = latest stable.
- Compose **Navigation** with a bottom navigation bar.
- **Room** for trip logs + run history; CSV export via SAF (`ACTION_CREATE_DOCUMENT`).
- No third-party analytics/ads/crash SDKs. No network calls at all.

## Screens (build all)
1. **Connect** — BLE scan, list adapters, connect/disconnect, show connection + ELM init
   status. Persist last device (DataStore).
2. **Dashboard** — live gauge cards from `ObdEngine.state`: speed, 12V module voltage,
   ambient temp (`StandardPids`). Real-time updates; make adding PIDs trivial.
3. **Performance** — large **0-100 km/h** timer via `PerformanceMeter`. Show live speed,
   elapsed, current-run splits (0-50 / 0-100 / 0-120 km/h, 0-100 m, 0-402 m), max speed.
   Save completed runs to Room; show history with the best 0-100 highlighted.
   During a run, poll ONLY speed at ~20 Hz: `startPolling(listOf(StandardPids.speed), 50)`.
4. **Diagnostics** — read DTCs (`DtcReader.readCodes()`), list them; a **Clear codes**
   button that requires a **confirmation dialog** before calling `clearCodes()`.
5. **Trip Log** — start/stop logging; while active run a **foreground service** that writes
   timestamped samples to Room; export a trip to CSV via the system file picker.
6. **Settings** — units (km/h default), adapter type (BLE default / Classic BT), which PIDs
   the dashboard polls, poll interval.

## Permissions & manifest
- Manifest + runtime: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+, `neverForLocation`
  where valid), `ACCESS_FINE_LOCATION` (BLE scan on API < 31), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Clean runtime permission flow with a rationale screen before scanning.
- Foreground service shows a persistent notification while logging.

## Architecture wiring
- One `ObdConnectionManager` (singleton / simple DI) owns `Transport` + `Elm327` +
  `ObdEngine` + `PerformanceMeter`, exposes StateFlows; ViewModels observe it.
  Handle reconnect + graceful disconnect on lifecycle.
- Wire `ObdEngine.onSample` → feed speed into `PerformanceMeter.onSpeed(...)` AND the trip logger.

## Guardrails (do NOT violate)
- **No fabricated OBD data.** If a PID/formula isn't in the provided files or verified by the
  user, leave a clearly-marked `TODO`. Never guess hex PIDs, DIDs, or parse offsets.
  Keep `EgmpPids` empty until the user supplies verified E-GMP values.
- **No monetization, ads, tracking, telemetry, or network calls.**
- **Confirm before irreversible actions** (clearing DTCs).
- **Safety:** the performance/timer feature is for closed-road/track use — add a short
  disclaimer and keep the in-motion UI glanceable (large text, minimal taps).
- Everything stays on-device.
- If real Vgate BLE UUIDs differ from the skeleton, detect at runtime, log discovered
  services, and surface a clear error instead of failing silently.

## Build order (phases — commit after each, keep it building)
- **P0** — Scaffold Gradle project/module/packages, drop in provided files, manifest +
  permissions, bottom-nav shell with empty screens. Must build & run.
- **P1** — BLE connect flow (scan → connect → ELM init). Verify against the real adapter; fix UUIDs.
- **P2** — Dashboard live gauges.
- **P3** — Performance meter screen + Room run history.
- **P4** — Diagnostics (DTC read/clear + confirm dialog).
- **P5** — Trip logging foreground service + CSV export.
- **P6** — Settings. Then leave `EgmpPids` documented as a TODO (SoC / HV V·A / power kW /
  cell temps) — do NOT implement without verified PIDs.

## Acceptance criteria
- Builds with a single `./gradlew assembleDebug`.
- Connects to the Vgate iCar Pro BLE and shows live **speed** on the Dashboard.
- Performance screen yields a plausible 0-100 km/h time (interpolated), auto-detects launch,
  and saves runs.
- DTC read works; clear requires confirmation.
- Trip logging survives screen-off via the foreground service; CSV export opens the file picker.
- No ads / analytics / network anywhere in the codebase.

## Notes for the agent
- After the first successful connection, ask the user to paste the logged BLE
  service/characteristic UUIDs so you can lock them into `BleTransport`.
- For E-GMP battery PIDs later, point the user to the **EVNotify** open-source repo
  (`GPlay97/EVNotify`) and Ioniq/EV6 community PID tables; integrate ONLY verified values.
