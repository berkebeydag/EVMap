# IoniqScope

Personal Android OBD-II app for a **Hyundai Ioniq 6** via a **Vgate iCar Pro BLE 4.0**
adapter. Hobby project — no ads, no analytics, no accounts, no cloud, and no
`INTERNET` permission in the manifest at all.

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17+ (built against Temurin 21) and an Android SDK with **API 36**
installed. Point Gradle at it with `ANDROID_HOME` or a `local.properties`
containing `sdk.dir=/path/to/Android/Sdk`.

### Toolchain

| Component | Version | Note |
|---|---|---|
| Gradle | 9.6.1 | wrapper checked in, distribution SHA-256 pinned |
| AGP | 9.3.1 | uses **built-in Kotlin** — see below |
| Kotlin | 2.2.10 | bundled by AGP; only pinned here for the Compose compiler plugin |
| KSP | 2.3.10 | KSP2 standalone versioning |
| compileSdk / targetSdk | 36 | minSdk 26 |

> **AGP 9 note:** the `org.jetbrains.kotlin.android` plugin is *deliberately absent*.
> AGP 9 compiles Kotlin itself and rejects the standalone plugin. Likewise the older
> `2.2.10-2.0.2` KSP line is unusable here because it registers generated sources via
> `kotlin.sourceSets`, which built-in Kotlin disallows.

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

## Privacy

No `INTERNET` permission. No analytics, ads, crash reporting, or account. Trips,
runs and settings live only on the device, and backup/data-extraction rules exclude
them from cloud backup and device transfer.
