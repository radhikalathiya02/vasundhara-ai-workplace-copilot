# APK Automation Testing Framework

A Java/Spring Boot framework that lets a QA engineer **upload any Android APK through a web
dashboard** and automatically runs a full suite of end-to-end tests against a connected
device/emulator — **without writing a single test case**. The app is installed, statically
analysed, autonomously crawled via Appium, stress-tested via the Android monkey, profiled and
mined for crashes, then a consolidated report is produced.

## Test categories

| Category | Needs Appium | What it does |
|---|---|---|
| **Security Validation** | – | Debuggable / backup / cleartext flags, exported components, signing scheme, dangerous permissions, target-SDK currency. |
| **Compatibility Testing** | – | APK SDK range & native ABIs vs. the live device, Play target-API rules, localisation breadth. |
| **Functional Testing** | ✓ | Confirms the app launches and its controls drive real state changes. |
| **UI/UX Testing** | ✓ | Off-screen controls, overlapping tap targets, clutter, slow transitions. |
| **Accessibility Testing** | ✓ | Missing labels, sub-48dp touch targets, unlabeled inputs, duplicate descriptions. |
| **End-to-End Testing** | ✓ | Activity/flow coverage reached by driving the app end to end. |
| **Exploratory Testing** | ✓ | Reports the autonomous crawl breadth and any anomalies. |
| **Negative Testing** | ✓ | Hostile input, rotation, background/foreground, connectivity loss, back-spam. |
| **Monkey Testing** | – | Thousands of random events via `adb shell monkey`; reports crashes/ANRs. |
| **Performance Testing** | – | Cold-start latency (`am start -W`), memory PSS, rendering jank (gfxinfo). |
| **Crash Testing** | – | Mines logcat (main + crash buffer) for fatal exceptions, native crashes, ANRs, StrictMode. |
| **AdMob / Firebase Ads** | – | Detects ad/analytics SDKs, verifies ads load and Firebase initialises at runtime. |
| **Regression Testing** | – | Diffs this run against the previous run of the same package. |

## How it works

```
Upload APK ─► Static analysis (apk-parser + manifest + dex scan)
           ─► Install on device (adb)
           ─► ONE shared Appium UI crawl  ──► functional / uiux / accessibility / e2e / exploratory / negative
           ─► adb stress & metrics        ──► performance / monkey / crash / ads
           ─► Regression diff vs. baseline
           ─► HTML + JSON report
```

The heart of the framework is `ExplorationEngine` — a model-based crawler that launches the app,
parses the live UI hierarchy, records unique screens (with screenshots), and taps/types
unused interactive elements, scrolling and backing out of dead ends. The single crawl result
feeds every interactive category, so the app is only driven once per run.

## Prerequisites

- **JDK 21+** (built/tested on Temurin 25)
- **Maven 3.9+**
- **Android SDK platform-tools** (`adb`) on `PATH` or configured below
- **Appium 2/3** running with the **uiautomator2** driver: `appium driver install uiautomator2 && appium`
- A connected device or emulator authorised for adb (`adb devices`)

## Build & run

```bash
mvn clean package -DskipTests
java -jar target/app-test-framework.jar
# dashboard: http://localhost:8080
```

Override budgets / paths at launch:

```bash
java -jar target/app-test-framework.jar \
  --atf.crawl-max-steps=120 \
  --atf.monkey-events=1500 \
  --atf.appium-server-url=http://127.0.0.1:4723 \
  --atf.adb-path=/path/to/adb \
  --atf.device-serial=EMULATOR-5554   # blank = auto-pick first online device
```

All settings live in `src/main/resources/application.yml` under the `atf:` prefix and can be
overridden via CLI args or environment variables (`ATF_CRAWLMAXSTEPS`, etc.).

## Authentication

The dashboard and API are protected by a form-based sign-in (Spring Security). A single account
is provisioned from configuration — default **`admin` / `admin`**. Override it:

```bash
java -jar target/app-test-framework.jar \
  --atf.auth-username=qa --atf.auth-password='S3cret!'
# or env: ATF_AUTHUSERNAME / ATF_AUTHPASSWORD
```

Unauthenticated requests redirect to `/login.html`; **Sign Out** (top-right of the dashboard, or
`GET /logout`) invalidates the session and returns to the login page.

## Using the dashboard

1. Open `http://localhost:8080` and **sign in** (default `admin` / `admin`).
   The header shows the signed-in user, the detected device and Appium status.
2. Drop or browse to an `.apk`.
3. Tick the test categories to run (all selected by default).
4. Click **Start Automated Testing**. The run appears in the list and updates live.
5. Click a run to see KPIs, the APK overview, and a per-category breakdown of findings and
   screenshots. **Open full report ↗** renders a standalone HTML report.

## REST API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/device` | Connected device profile + Appium reachability |
| `GET` | `/api/categories` | Available test categories |
| `POST` | `/api/runs` | Multipart `file` (.apk) + repeated `categories` params → starts a run |
| `GET` | `/api/runs` | All runs (summary) |
| `GET` | `/api/runs/{id}` | Full run JSON (findings, metrics, steps) |
| `GET` | `/api/runs/{id}/report.html` | Standalone HTML report |
| `GET` | `/api/runs/{id}/artifacts/**` | Run artifacts (screenshots) |

Example:

```bash
curl -X POST http://localhost:8080/api/runs \
  -F file=@MyApp.apk \
  -F categories=security -F categories=functional -F categories=crash
```

## Project layout

```
com.vasundhara.atf
├── config/      AtfProperties, async executor
├── apk/         ApkAnalyzer (static analysis)
├── device/      AdbClient, DeviceManager, DriverFactory
├── engine/      ExplorationEngine (crawler), TestOrchestrator, TestContext, TestCategory
├── tests/       the 13 TestCategory implementations
├── report/      ReportStore, ReportService (HTML)
├── web/         ApiController + dashboard DTOs
└── util/        ProcessRunner, LogcatAnalyzer
resources/static/index.html   the dashboard SPA
```

Adding a new category is a matter of implementing `TestCategory` as a `@Component` and adding
its key to `TestOrchestrator.CATEGORY_ORDER`.

## Notes & limitations

- One physical device hosts one run at a time (the run executor is single-threaded).
- The crawler is heuristic: deep flows gated behind login or specific input may not be reached.
  Increase `--atf.crawl-max-steps` for broader coverage.
- Regression baselines are kept in memory for the server's lifetime (no persistence across restarts).
- Static SDK detection scans DEX byte signatures; obfuscated/renamed packages may evade it.
- Some signing schemes (v3/v4-only) may not be surfaced by the bundled apk-parser.
