# IoniqScope

Personal Android OBD-II app for a **Hyundai Ioniq 6** via a **Vgate iCar Pro BLE 4.0**
adapter, plus an offline charging-station map for Türkiye. Hobby project — no ads,
no analytics, no accounts, no cloud.

> **Network policy changed when the charger map was added.** The app now holds the
> `INTERNET` permission and uses it for exactly two things: downloading map tiles,
> and refreshing the charging-station list on demand. **No vehicle data ever leaves
> the device** — no trip logs, no runs, no diagnostics, no 12V history, no
> telemetry, no analytics, no crash reporting. The map reads from a local cache, so
> once the station list has been downloaded it works with no signal at all.
> Location is requested as **coarse only**, and only to sort chargers by distance.

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17+ (built against Temurin 21) and an Android SDK with
`platforms;android-37.1` installed. Point Gradle at it with `ANDROID_HOME` or a
`local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

Verified green from a clean tree: `assembleDebug` produces a ~19 MB
`app-debug.apk`, with no compiler warnings and no lint issues.

### Toolchain

| Component | Version | Note |
|---|---|---|
| Gradle | 9.6.1 | wrapper checked in, distribution SHA-256 pinned |
| AGP | 9.3.1 | uses **built-in Kotlin** — see below |
| Kotlin | 2.4.10 | overrides AGP's bundled 2.2.10 via the buildscript classpath |
| KSP | 2.3.10 | KSP2 standalone versioning |
| compileSdk / targetSdk | 37 (`compileSdkMinor = 1`) | minSdk 26 |

Three build-file details that are load-bearing, not incidental:

- The `org.jetbrains.kotlin.android` plugin is **deliberately absent**. AGP 9
  compiles Kotlin itself and hard-errors if the standalone plugin is applied.
- KSP must be on the **2.3.x standalone line**. The older `2.2.10-2.0.2` line
  registers its generated sources through `kotlin.sourceSets`, which built-in
  Kotlin rejects outright.
- `compileSdk` cannot be lowered to 36: `androidx.core:1.19.0` and
  `androidx.lifecycle:2.11.0` both refuse to be consumed below API 37.

## Architecture

```
com.berke.ioniqscope
├── obd/            ObdEngine.kt, BleTransport.kt, BleScanner.kt   (transport + ELM327 + PIDs)
├── performance/    PerformanceMeter.kt                            (0-100, splits, distance)
├── connection/     ObdConnectionManager, PerfRunRecorder          (single owner of the adapter)
├── data/           Room entities/DAOs, SettingsRepository, CsvExporter, PidCatalog
├── service/        TripLoggingService                             (foreground service)
└── ui/             theme, nav, screens (Compose + Material 3)
```

`ObdConnectionManager` is the only thing that touches the adapter. An ELM327 link
carries exactly one command at a time, so one-shot commands (DTC read/clear) park
the poll loop via `exclusive { }` and restore it afterwards. ViewModels observe
StateFlows; nothing else opens a transport.

Screens claim the poll loop when they become visible: Dashboard polls the user's
selected PID set at the configured interval, Performance overrides it with
speed-only polling at 50 ms (~20 Hz).

## Screens

- **Connect** — permission rationale → BLE scan → connect → ELM init, with a live
  adapter log. Last device is remembered in DataStore.
- **Dashboard** — live gauge cards. PIDs that the car does not answer show `—` and
  are called out explicitly rather than rendered as zero.
- **Performance** — 0-100 km/h timer with automatic launch detection, splits, and
  Room-backed run history with the best 0-100 highlighted. Track use only.
- **Diagnostics** — mode 03 DTC read; clearing goes through a confirmation dialog.
- **Trips** — foreground-service logging that survives screen-off, plus CSV export
  through the system file picker (`ACTION_CREATE_DOCUMENT`).
- **Settings** — units, adapter type, dashboard PID selection, poll interval.
  Reachable from the gear in the top bar (the bottom bar holds the other five;
  six items crowd the labels off a phone-width `NavigationBar`).

## Two things only the hardware can tell you

### 1. Vgate BLE UUIDs

`BleTransport` does **not** assume FFE0/FFE1. It tries known candidate profiles
(FFE0/FFE1, FFF0/FFF1+FFF2, Nordic UART), then falls back to structural discovery
— any service exposing a notify characteristic plus a writable one. Either way it
dumps the full discovered GATT profile to the **adapter log on the Connect screen**.

After the first successful connection, read the real service/characteristic UUIDs
off that log and move them to the front of `candidates` in
[`BleTransport.kt`](app/src/main/java/com/berke/ioniqscope/obd/BleTransport.kt).

### 2. Ioniq 6 / E-GMP battery PIDs

`EgmpPids` is **empty on purpose**. State of charge, HV battery voltage/current,
power in kW and cell temperatures are not standard OBD-II PIDs — they need
manufacturer-specific UDS requests (`ATSH<ecu>` then `22 <did>`). Guessing a DID or
a parse offset would produce numbers that look plausible and are wrong, which is
worse than showing nothing.

Pull verified values from the [EVNotify](https://github.com/GPlay97/EVNotify) repo
or a Car Scanner Ioniq/EV6 profile and add them to `EgmpPids.set`. Anything added
there appears automatically in the Settings PID list and on the Dashboard —
`PidCatalog` already includes it, no further wiring needed.

## Handoff package

`core/` still holds the original hand-written files from the handoff package.
`ObdEngine.kt` and `PerformanceMeter.kt` were copied into the app module byte for
byte; `BleTransport.kt` was completed there (UUID discovery, MTU negotiation,
timeouts, disconnect callback). `core/` is outside the Gradle source set so it does
not compile — it can be deleted once you are happy with the integrated copies.

## Charging stations — and an honest word about the data

A dataset ships inside the APK, so the map is useful the moment the app opens, with
no signal and no button to find. Network is only touched to refresh it.

**Bundled: 16,104 stations across Türkiye (July 2026), every one of them carrying
its power rating, its connector types and an address**, under 613 operator names.
About half are DC. It is built from a full sweep of TomTom's POI search by
`tools/sweep_tomtom.py` and `tools/build_bundle_from_sweep.py`, and it costs the
APK 830 KB.

What it replaced is kept at `data/chargers_tr.merged.json` and still builds, via
`tools/build_charger_bundle.py`: a merge of Open Charge Map, ZES's and Trugo's own
lists, İBB's slice of the EPDK register, and OpenStreetMap. That found 6,102
stations and could state the power on under a third of them, because the only
source that reliably carries kW is Open Charge Map and the operators' feeds do not.
It is the fallback, and the one that needs no key at all.

Two live sources are wired behind a `ChargerSource` interface, for refreshing the
bundle without waiting for a release:

- **OpenStreetMap** (no key, no account) — queried through Overpass, constrained to
  the Türkiye boundary relation rather than a bounding box, because a box around
  Türkiye also catches Greece, Bulgaria, Cyprus and slices of the Caucasus and
  Levant; measured, that was about half of everything returned. On its own it
  measured 654 stations for Türkiye, 59 of them tagged DC — a base layer, not a
  picture, which is why nothing relies on it alone any more.
- **TomTom** — needs a free key you register yourself and paste into Settings.
  Sweeps the country the same way the bundle was built and lands on the same rows,
  since both are keyed by TomTom's own ids.

EPDK publishes the authoritative national list (every licensed operator must
report; it is what the official Şarj@TR app shows) and a REST web service is
referenced in İBB's open-data portal, but no publicly documented endpoint was
found. If one surfaces it drops in as another `ChargerSource` with nothing else
changing — see the `TODO(epdk)` in that file.

## Privacy

No analytics, ads, crash reporting, or account. Vehicle data — trips, runs,
diagnostics, 12V history — never leaves the device, and backup/data-extraction
rules exclude it from cloud backup and device transfer. Network use is limited to
map tiles and the charging-station refresh; see the note at the top.
