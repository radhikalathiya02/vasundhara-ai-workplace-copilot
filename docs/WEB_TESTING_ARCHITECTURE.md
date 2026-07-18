# Website Testing Platform — Architecture & Research Document

**Status:** Research / design only. No implementation.
**Author:** Design phase for Vasundhara ATF (Web Testing Module)
**Companion to:** the existing APK Automation Testing Framework (`com.vasundhara.atf`)
**Goal:** A "paste one URL → get a complete website audit report" system, engineered to enterprise grade and built to live inside the existing Spring Boot app using the same session/lock/report patterns.

---

## 1. Executive Summary

We are adding a **Website Testing** capability that mirrors the existing Android testing flow: the user provides a single input (a URL instead of an APK), the system autonomously crawls and analyzes the entire site, and produces one consolidated, severity-ranked report.

The report covers **13 analysis domains**, matching the parity of the Android side:

1. Functional bugs & JavaScript console errors
2. Network / HTTP errors & failed requests
3. Broken links & missing resources
4. Performance (Core Web Vitals + Lighthouse)
5. Accessibility (WCAG 2.2 A/AA)
6. SEO
7. Best-practices (Lighthouse "Best Practices" + custom)
8. Security (headers, TLS, mixed content, exposure)
9. Responsive design across breakpoints
10. UI/UX consistency (design-system drift, visual regressions)
11. Forms / buttons / navigation interaction validation
12. Screenshots (full-page, per-breakpoint, element-level evidence)
13. AI-assisted review (visual + semantic), degrading gracefully like the APK side

**The single most important architectural decision:** the highest-quality web-analysis tools (Playwright, Lighthouse, axe-core, Crawlee, pa11y) are **Node.js/Chromium-native**. The backend is **Java/Spring Boot**. Rather than reimplement these in Java (inferior, high-maintenance) we run a **Node "Web Analysis Worker"** as a sidecar process that Spring Boot orchestrates — exactly analogous to how the APK side orchestrates external `adb`/Appium processes today. Spring owns sessions, locking, persistence, reporting, and the UI; Node owns the browser and the audits.

---

## 2. Design Goals & Non-Goals

**Goals**
- **One input.** URL only. Everything else is auto-detected or defaulted.
- **Whole-site.** Crawl and analyze every reachable in-scope page, not just the landing page.
- **Modular.** Each analysis is an independent, swappable analyzer. Adding a new check = adding one module.
- **Parallel where safe, sequential where required.** Maximize throughput without corrupting per-page browser state.
- **Scalable to any size.** Bounded queues, politeness, concurrency caps, and hard budgets so a 50,000-page site degrades gracefully instead of exploding.
- **Consistent with the existing app.** Reuse the session model, `ExecutionLockService`, `ReportStore` + DB duality, controller conventions, the SPA rendering pattern, and the AI graceful-degradation rule.
- **Deterministic + reproducible reports** with stable issue IDs for diffing runs over time.

**Non-Goals (v1)**
- Authenticated deep-flows behind complex SSO (v2 — see §20).
- Full synthetic monitoring / scheduled uptime (future).
- Load / stress testing (separate concern; Lighthouse ≠ load test).
- Fixing issues automatically (report only).

---

## 3. Technology Stack — Options, Trade-offs, Recommendation

### 3.1 The core boundary: Java orchestrator + Node analysis worker

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Pure Java** (Selenium/Playwright-Java, jsoup, custom Lighthouse port) | One language, one process, no IPC | Lighthouse & axe-core are JS; Playwright-Java lacks the richest tracing; you'd reimplement audits and fall behind upstream | ❌ Rejected — reinvents the ecosystem |
| **Pure Node** (rewrite the whole app) | Best tooling, single runtime for web | Throws away the existing Spring app, sessions, auth, DB, UI, APK module | ❌ Rejected — not incremental |
| **Java orchestrator + Node sidecar worker** ✅ | Keep Spring for orchestration/UI/DB/auth; use best-in-class Node tools for analysis; clean process boundary; matches the existing "Spring drives external adb/Appium" pattern | One extra runtime to ship (Node) and an IPC contract to maintain | ✅ **Recommended** |

**Recommendation:** **Java (Spring Boot) orchestrator + Node.js analysis worker.** Spring already shells out to `adb`/`appium`; a Node worker is the same pattern. The IPC contract is a small, versioned JSON protocol (§6.4).

### 3.2 Browser automation engine

| Tool | Pros | Cons |
|---|---|---|
| **Playwright** ✅ | Multi-browser (Chromium/Firefox/WebKit), auto-waiting, network interception, tracing, video, device emulation presets, first-class CDP access, actively maintained | Node-native (fits our worker) |
| Puppeteer | Mature, Chromium-focused, lighter | Chromium-only, fewer ergonomics than Playwright |
| Selenium (Java) | Java-native | Verbose, flaky waits, weak network/tracing, no built-in device emulation |
| Cypress | Great DX for E2E | In-browser runtime, not built for headless crawl-and-audit at scale |

**Recommendation:** **Playwright (Node).** It gives us network interception (for network/link/security checks), CDP (for Lighthouse + performance traces), device descriptors (responsive), screenshots/video, and console+error hooks — all the primitives every analyzer needs, from one engine.

### 3.3 Crawler

| Tool | Pros | Cons |
|---|---|---|
| **Crawlee** (Apify) ✅ | Purpose-built Node crawler: request queue, auto-scaling pool, dedup, politeness, `robots.txt`, retries, session pool, pluggable Playwright/Cheerio crawlers, sitemap ingestion | Node (fine — same worker) |
| Custom BFS over Playwright | Full control | You'll reimplement queueing, dedup, backpressure, retries — exactly what Crawlee already hardened |
| jsoup/crawler4j (Java) | Java-native, fast static crawl | No JS rendering; can't see SPA routes or client-rendered links |

**Recommendation:** **Crawlee with a hybrid strategy** — a cheap **Cheerio (HTTP) pass** for fast link discovery on static/SSR pages, escalating to a **Playwright pass** for pages that need JS rendering (SPA, client-routed links). Crawlee manages the queue, dedup, concurrency, and `robots.txt` for us.

### 3.4 The analyzer libraries

| Domain | Recommended | Alternatives / notes |
|---|---|---|
| Performance + Best-practices + PWA | **Lighthouse** (programmatic, driven over Playwright's CDP endpoint) | PageSpeed Insights API (rate-limited, external); WebPageTest (heavy) |
| Core Web Vitals field-style metrics | **Lighthouse** lab metrics + **`web-vitals`** lib for in-page LCP/CLS/INP | — |
| Accessibility | **axe-core** (via `@axe-core/playwright`) ✅ primary | **pa11y** (adds HTML CodeSniffer ruleset) as an optional second engine for coverage; IBM Equal Access as a third |
| SEO | **Custom analyzer** over rendered DOM + Lighthouse SEO category | — |
| HTML validation | **Nu HTML Checker (`vnu`)** or `html-validate` | — |
| Security headers / TLS | **Custom analyzer** + `ssl-checker`; optionally shell to **testssl.sh** for deep TLS | Mozilla Observatory API (external) |
| Broken links | Playwright/Crawlee network layer + HEAD/GET revalidation | `linkinator`, `broken-link-checker` |
| Visual / UI consistency | **Playwright screenshots + `pixelmatch`/`odiff`** for diffs; DOM-derived design-token extraction | Applitools (commercial, excellent but paid) |
| CSS/JS quality signals | coverage API (unused CSS/JS), console error capture | — |
| AI review | **Existing `AiVisionClient`** (Claude/OpenAI/Ollama) reused for screenshot + DOM review | — |

**Runtime shipping:** Node 20 LTS + a pinned Playwright browser bundle. Package the worker as `web-worker/` with its own `package.json`, launched by Spring via `ProcessBuilder` (like Appium). For deployment, a **Docker image** with Chromium deps pre-installed is strongly recommended (avoids the classic missing-shared-library pain).

---

## 4. High-Level System Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot App (existing)                          │
│                                                                            │
│  SPA (index.html)  ──HTTP──▶  WebTestController  /api/web                   │
│                                     │                                       │
│                         ExecutionLockService (global single-run lock)      │
│                                     │                                       │
│                         WebTestOrchestrator (async @Async)                 │
│                            │            │            │                      │
│                    WebSessionStore  ReportStore   RunBridgeService          │
│                    (ConcurrentMap)  (live cache)  (shadow TestRun)          │
│                            │            │            │                      │
│                            └──── DB (H2/Postgres via JPA + Flyway) ────┘    │
│                                     │                                       │
│                    WebWorkerClient (JSON-over-stdio / local HTTP)          │
└─────────────────────────────────────┼──────────────────────────────────────┘
                                       │  versioned JSON protocol (§6.4)
                        ┌──────────────▼───────────────┐
                        │   Node Web Analysis Worker    │
                        │                               │
                        │  Orchestrator (job runner)    │
                        │   ├─ Crawler (Crawlee)        │
                        │   ├─ PageAnalyzer pool        │
                        │   │    ├─ Lighthouse          │
                        │   │    ├─ axe-core / pa11y    │
                        │   │    ├─ SEO analyzer        │
                        │   │    ├─ Security analyzer   │
                        │   │    ├─ Link/Network capture│
                        │   │    ├─ Responsive capture  │
                        │   │    ├─ Screenshot service  │
                        │   │    └─ Interaction checker │
                        │   └─ Playwright browser pool  │
                        └───────────────┬───────────────┘
                                        │ progress + findings (streamed)
                                        ▼
                               Artifacts on disk
                          (screenshots, traces, HAR, JSON)
```

**Component ownership**

- **Spring Boot** — auth/security (unchanged), the SPA, the REST surface, session lifecycle, the global execution lock, persistence, artifact serving with the existing path-traversal guard, report assembly, AI calls (reusing `ai/`), and driving the Node worker's lifecycle.
- **Node worker** — everything that touches a browser: crawling, rendering, and all 13 analyzers. It is **stateless between jobs** and reports findings back as structured JSON; Spring is the source of truth.

**Why this split scales:** Spring stays the single control plane (locking, history, UI) while the CPU/RAM-heavy browser work is isolated in a process we can independently cap, restart on crash (mirroring `DeviceWatchdog`), and later scale horizontally to N worker machines behind a queue.

---

## 5. End-to-End Workflow (URL → Report)

```
1.  User enters URL in SPA → POST /api/web/scan { url, options? }
2.  Spring validates URL, checks ExecutionLockService, acquires lock,
    creates WebTestSession (RUNNING), returns sessionId immediately (async).
3.  Spring launches/reuses the Node worker and sends a JOB message.
4.  PRE-FLIGHT (worker): resolve DNS, fetch robots.txt + sitemap.xml,
    fetch homepage, detect SPA vs SSR, detect tech stack, set scope rules.
5.  CRAWL (worker): BFS discovery of in-scope pages until queue empty
    or budget hit (max pages / max depth / time). Emits page list + link graph.
6.  PER-PAGE ANALYSIS (worker): for each discovered page, run the analyzer
    pipeline (§8) — some analyzers parallel, some sequential per page.
7.  SITE-LEVEL ANALYSIS (worker): checks that need the whole graph
    (broken-link graph, sitemap coverage, duplicate-title/meta, design-token drift).
8.  NORMALIZE + DEDUP (worker→Spring): raw findings → canonical Issue model,
    merged/deduped, severity-scored.
9.  AI ENRICHMENT (Spring, optional): screenshot + DOM review, plain-English
    explanations & remediation. Degrades to null on failure — never blocks.
10. PERSIST: ReportStore (live) + DB backup + RunBridge shadow record.
11. REPORT: assemble consolidated report (JSON + HTML), release lock,
    mark session COMPLETED.
12. UI: SPA polls session, streams live progress, then renders the report.
```

Progress is streamed the whole way (page counts, per-analyzer status, live screenshot) so the UI matches the "live screen" experience of the APK runs.

---

## 6. Backend Processing Pipeline (detail)

### 6.1 Session lifecycle (Spring)
Reuse the existing session pattern (like `AnalysisSession`, `CompatSession`):

```
WebTestSession {
  id, url, normalizedOrigin, options,
  state: QUEUED | CRAWLING | ANALYZING | ENRICHING | COMPLETED | FAILED | STOPPED,
  progress: { pagesDiscovered, pagesAnalyzed, currentPage, phase, pct },
  startedAt, finishedAt,
  liveScreenPath,          // latest screenshot for the UI
  findings: List<Issue>,   // accumulates
  summary: { countsBySeverity, countsByCategory, score }
}
```

States map to progress phases. `STOPPED` is honored cooperatively at page boundaries (like the existing `/stop` endpoints).

### 6.2 Concurrency & the execution lock
The app enforces **one module runs at a time** globally. A web scan acquires the same `ExecutionLockService` lock so it can't collide with an APK run. **Within** a scan, parallelism is internal to the worker (browser pool). This keeps the existing invariant intact.

### 6.3 Orchestrator responsibilities (Spring `WebTestOrchestrator`, `@Async`)
- Acquire lock, create session, spawn/attach worker.
- Send job, receive streamed progress + findings, update `ReportStore`.
- Enforce global budgets (wall-clock timeout, max pages) and STOP signals.
- On worker crash: mark FAILED with partial results (never lose what was gathered), release lock. Mirror `DeviceWatchdog` restart semantics.
- Trigger AI enrichment, persist, build report, release lock.

### 6.4 The Java↔Node IPC contract (versioned)
Two viable transports:

| Transport | Pros | Cons |
|---|---|---|
| **JSON-lines over stdio** ✅ (v1) | Simplest, no port, process-scoped, easy lifecycle via `ProcessBuilder` | One worker per process |
| **Local HTTP (worker as tiny Fastify server)** | Multiple concurrent jobs, easy to move to remote workers later | Port management, more moving parts |

**Recommendation:** start with **stdio JSON-lines** (matches the single-run lock — only one job at a time anyway), with the message schema designed so migrating to HTTP/queue later is a transport swap, not a contract change.

Message types (both directions):
```
→ worker:  { type:"JOB",   jobId, url, options }
→ worker:  { type:"STOP",  jobId }
← spring:  { type:"PROGRESS", jobId, phase, pagesDiscovered, pagesAnalyzed, currentPage, screenshotPath }
← spring:  { type:"FINDING",  jobId, issue }            // streamed as found
← spring:  { type:"PAGE_DONE", jobId, pageReport }
← spring:  { type:"DONE",     jobId, summary }
← spring:  { type:"ERROR",    jobId, fatal, message }
```
Every message carries `protocolVersion`. Artifacts are written to the shared artifacts dir; messages carry **paths**, not blobs, so Spring serves them through the existing artifact endpoint.

---

## 7. Crawler Design

### 7.1 Scope resolution (pre-flight)
From the single URL, derive the crawl scope automatically:
- **Normalize** the URL (scheme, trailing slash, strip fragments).
- **Origin scope** by default: same registrable domain (configurable to same-origin-only or include subdomains).
- Fetch and honor **`robots.txt`** (politeness + disallowed paths). Provide an explicit "authorized scan" toggle for sites the user owns.
- Ingest **`sitemap.xml`** (and nested sitemaps) as high-priority seed URLs.
- Detect **SPA vs SSR** (does the homepage render meaningful DOM without JS?) to decide the default crawl strategy.
- **Tech fingerprint** (framework, CMS, server headers) — informs analyzer heuristics and the report's "site profile".

### 7.2 Discovery strategy (hybrid, tiered)
1. **Seed** = homepage + sitemap URLs.
2. **Cheap pass (Cheerio/HTTP):** fetch HTML, extract `<a href>`, canonical, and static resource refs. Fast, low cost.
3. **Render pass (Playwright)** — triggered when a page is JS-heavy or the cheap pass found too few links: render, wait for network-idle, extract links added by client routing, and capture the fully-rendered DOM (analysis always uses the rendered DOM).
4. **Normalize + dedup** URLs (Crawlee request queue handles this): strip tracking params, unify trailing slashes, collapse case per host rules, respect canonical tags to avoid duplicate-content crawl explosions.
5. **Enqueue** in-scope, not-yet-seen URLs; repeat BFS.

### 7.3 What gets discovered
- **Pages:** every in-scope HTML document (link graph nodes).
- **Assets:** CSS, JS, images, fonts, media, XHR/fetch endpoints — captured via Playwright's network interception per page (used by link/network/performance/security analyzers).
- **Links:** internal (crawl + validate) and external (validate status only, do **not** crawl).
- **Forms & interactive elements:** cataloged per page for the interaction checker (§14).

### 7.4 Politeness, safety & budgets (critical for "any size")
- **Concurrency cap** (per-host and global) via Crawlee's autoscaled pool.
- **Rate limiting / crawl delay** honoring `robots.txt` and a configurable politeness delay.
- **Hard budgets:** `maxPages`, `maxDepth`, `maxDurationMs`, `maxRequestsPerPage` — defaults sane, all overridable. When a budget is hit, stop cleanly and **log what was skipped** (never silently truncate — report "analyzed 5,000 of ~12,000 discovered pages").
- **Trap avoidance:** detect infinite calendars/faceted-filter loops via URL-similarity + depth caps.
- **Retry with backoff** on transient 5xx / network errors.
- **De-scoping rules:** skip `mailto:`, `tel:`, `javascript:`, logout links, and (by default) anything that looks state-mutating on GET when auth is present.

---

## 8. Analyzer Pipeline — Parallel vs Sequential

### 8.1 The governing principle
**Pages are the unit of parallelism; analyzers within a single page are mostly sequential because they share one browser context.** Running two analyzers that both drive the *same* page's DOM/network simultaneously corrupts state. So:

- **Parallel across pages:** N browser contexts analyze N different pages at once (pool size = tuned to CPU/RAM).
- **Sequential within a page** for anything that navigates/mutates or needs an exclusive trace (Lighthouse, interaction tests).
- **Parallel within a page** only for read-only, non-navigating analyzers that operate on an already-captured artifact (DOM snapshot, HAR, screenshot) — those can run concurrently off the captured data.

### 8.2 Per-page pipeline (ordered)

```
PAGE PIPELINE (per page, in its own browser context)
│
├─ 1. NAVIGATE + INSTRUMENT (sequential, must be first)
│     • attach console listener, page error listener, request/response listeners
│     • goto(url), wait for network-idle / load
│     • capture: rendered DOM (HTML), HAR (all requests/responses),
│       console log, JS exceptions, coverage (unused CSS/JS)
│
├─ 2. CAPTURE ARTIFACTS (sequential)
│     • full-page screenshot at default viewport
│     • responsive screenshots across breakpoints (§9)
│
├─ 3. LIGHTHOUSE AUDIT (sequential — needs exclusive control of the page/CDP)
│     • Performance, Accessibility, Best-Practices, SEO, PWA categories
│
├─ 4. READ-ONLY ANALYZERS (parallel — all operate on captured DOM/HAR/screenshot)
│     ├─ axe-core / pa11y accessibility (on live DOM — but non-navigating)
│     ├─ SEO analyzer (DOM + headers)
│     ├─ Security analyzer (headers, mixed content, cookies from HAR)
│     ├─ Link extractor (from DOM + HAR) → feeds site-level validation
│     ├─ Console/network error classifier (from captured logs/HAR)
│     └─ HTML validation (vnu on captured HTML)
│
└─ 5. INTERACTION CHECKS (sequential, LAST — they mutate page state)
      • forms, buttons, nav, keyboard/focus order (§14)
```

Rationale for ordering: instrumentation must precede navigation to catch load-time console/network errors; Lighthouse needs the page to itself; interaction tests are destructive to page state so they run last.

### 8.3 Site-level pipeline (after all pages)
Runs once, needs the whole graph:
- **Broken-link validation** — revalidate every unique discovered URL (HEAD then GET fallback), map each broken target back to all referencing pages.
- **Sitemap coverage** — pages in sitemap not crawled / crawled pages not in sitemap.
- **Duplicate meta** — duplicate `<title>`/description/H1 across pages.
- **Design-token drift** — aggregate colors/fonts/spacing/button styles across pages to flag UI inconsistency (§13).
- **Orphan pages** — in sitemap but unlinked internally.

### 8.4 Parallel vs sequential — summary table

| Work | Mode | Why |
|---|---|---|
| Different pages | **Parallel** (browser pool) | Independent contexts |
| Navigate/instrument/capture (one page) | Sequential | Must precede everything |
| Lighthouse (one page) | Sequential, exclusive | Needs sole CDP control + clean trace |
| axe/SEO/security/link-extract/html-validate (one page) | **Parallel** | Read-only over captured artifacts |
| Interaction/form tests (one page) | Sequential, last | Mutate DOM/navigation |
| Broken links, dedup meta, token drift | Sequential, site-level | Need full graph |
| AI enrichment | Parallel, post-hoc (Spring) | Operates on finished artifacts |

---

## 9. Screenshots & Responsive Testing

### 9.1 Screenshot service
- **Full-page** screenshot per page at the default desktop viewport (evidence + AI review input).
- **Element-level** screenshots attached to individual findings (e.g., the exact failing contrast element, the broken image, the overflowing container) — greatly improves report usefulness.
- **Live screenshot** of the currently-analyzed page streamed to the UI (path in `PROGRESS` messages), reusing the existing `/live-screen` UX.
- Stored under the artifacts dir; served via the existing path-traversal-guarded artifact endpoint.

### 9.2 Responsive testing across breakpoints
Use **Playwright device descriptors + explicit breakpoints**:

| Class | Example viewport | Emulation |
|---|---|---|
| Mobile S | 360×640 | touch, mobile UA, DPR 2/3 |
| Mobile L | 414×896 | touch, mobile UA |
| Tablet | 768×1024 | touch |
| Laptop | 1366×768 | desktop |
| Desktop | 1920×1080 | desktop |

Per breakpoint, detect:
- **Horizontal overflow / broken layout** (`scrollWidth > clientWidth`, elements outside viewport).
- **Overlapping / clipped elements** (bounding-box intersection heuristics).
- **Tap-target size** (interactive elements below the 24×24 / 44×44 CSS-px accessibility thresholds).
- **Content reflow** failures, off-screen nav, unusable menus.
- **Missing/incorrect responsive images** (`srcset`/`sizes`) and fixed-width elements.
- Per-breakpoint screenshot for evidence.

Responsive capture happens in step 2 of the per-page pipeline; findings feed both the "Responsive" and "UI/UX" categories.

---

## 10. Lighthouse Integration

- Run **Lighthouse programmatically** inside the Node worker, pointed at the **same Chromium instance** Playwright launched (connect Lighthouse to the browser's CDP endpoint/port). This avoids launching a second browser and keeps auth/cookies consistent.
- Run all categories: **Performance, Accessibility, Best-Practices, SEO, PWA**.
- Extract both **category scores** and **individual audit results** (each failed audit → an Issue with the Lighthouse `id`, description, and affected elements).
- Configure both **mobile** (throttled, default) and **desktop** presets; report both, headline the mobile score (Google's default ranking lens).
- Because Lighthouse is expensive (~10–30s/page), for very large sites apply a **sampling policy**: full Lighthouse on a representative sample (homepage + one page per template/route-type, detected via URL patterns/DOM similarity) plus all user-flagged critical pages, while lightweight metrics (§12) run on every page. **Log the sampling** so the report is honest about coverage.
- Lighthouse is the **primary** source for Performance and Best-Practices, and a **corroborating** source for Accessibility (cross-checked against axe) and SEO (cross-checked against the custom SEO analyzer). Overlapping findings are merged in dedup (§16).

---

## 11. Accessibility Testing (WCAG)

- **Primary engine: axe-core** via `@axe-core/playwright`, run against the fully-rendered DOM of every page. Configure rule tags for **WCAG 2.0/2.1/2.2 Level A & AA** plus best-practice rules.
- **Optional second engine: pa11y** (HTML CodeSniffer ruleset) for pages/rules where a second opinion adds coverage; results deduped against axe.
- **Each violation → Issue** with: WCAG success criterion (e.g., 1.4.3 Contrast), impact (critical/serious/moderate/minor → mapped to our Severity), the CSS selector + element screenshot, and the remediation guidance axe provides.
- **Beyond automated rules** (automated tools catch ~30–50% of WCAG issues), add targeted checks Playwright can do that rule engines under-cover:
  - **Keyboard navigation & focus order** — tab through interactive elements, verify visible focus, no keyboard traps, logical order.
  - **Color contrast** — already in axe, but re-verified on responsive breakpoints.
  - **Landmark / heading structure** — logical H1→H6, single main landmark, skip-link presence.
  - **Form labels / ARIA** — inputs have accessible names.
  - **Reduced-motion / prefers-color-scheme** respect.
- **AI assist (graceful):** feed screenshots to `AiVisionClient` to flag issues automated tools miss (e.g., meaningful images with unhelpful alt text, low-contrast text baked into images). Never blocks.
- Report a **per-page and site-level WCAG conformance summary** (pass/fail by criterion, A vs AA).

---

## 12. SEO Analysis

Custom analyzer over the rendered DOM + response headers + Lighthouse SEO category. Checks:

**On-page / metadata**
- `<title>` present, length, uniqueness across site.
- meta description present, length, uniqueness.
- Single meaningful `<h1>`; sane heading hierarchy.
- Canonical tag correctness; self-referencing canonicals.
- `robots` meta / `X-Robots-Tag` (accidental `noindex`).
- Open Graph + Twitter Card completeness (social preview).
- `lang` attribute; `hreflang` for multi-locale sites.

**Content / structure**
- Structured data (JSON-LD / schema.org) presence + validity.
- Image `alt` coverage (overlaps a11y).
- Semantic HTML usage.
- Word-count / thin-content signals.
- Internal link depth (pages too many clicks from home).

**Technical SEO**
- `sitemap.xml` present, valid, referenced in `robots.txt`.
- `robots.txt` sanity (not accidentally blocking everything).
- HTTPS + no mixed content (overlaps security).
- Mobile-friendliness (overlaps responsive + Lighthouse).
- Canonicalization of www/non-www, http/https, trailing slash.
- 404/soft-404 handling; redirect chains/loops.
- Core Web Vitals (ranking signal — from §13).

Each becomes an Issue with severity by SEO impact (e.g., missing title = high, missing OG image = low).

---

## 13. Performance Metrics Collection

Two complementary layers:

1. **Lighthouse lab metrics** (throttled, reproducible): LCP, TBT, CLS, Speed Index, TTI, FCP, plus the composite Performance score and diagnostic audits (render-blocking resources, unused JS/CSS, oversized images, unminified assets, long main-thread tasks, excessive DOM size).
2. **In-page Core Web Vitals** via the `web-vitals` library injected on navigation: **LCP, CLS, INP** (INP replaced FID) as observed on the real render — corroborates lab numbers.

Additional signals captured from Playwright/HAR per page:
- **Resource waterfall** (from HAR): count, total transfer size, per-type breakdown, slowest requests, uncompressed/uncached resources, missing `cache-control`.
- **Coverage API:** % unused CSS/JS bytes.
- **Request counts** and third-party weight (analytics/ads/fonts).
- **Time-to-first-byte** and server response timing.

Report per-page metrics **and** site aggregates (median/p75 LCP etc. — p75 mirrors how Core Web Vitals are officially assessed). Flag pages failing the Good/Needs-Improvement/Poor thresholds.

---

## 14. JavaScript, Network Errors & Interaction Validation

### 14.1 JS console & runtime errors
Instrumentation attached **before** navigation captures:
- `console.error` / `console.warn` messages.
- Uncaught exceptions (`pageerror`) with stack traces.
- Unhandled promise rejections.
- CSP violation reports.
Each classified by severity (uncaught exception = high; warn = low) and attributed to the page + source file/line.

### 14.2 Network / HTTP errors & missing resources
From Playwright request/response + HAR per page:
- **Failed requests** (DNS/connection/timeout/aborted).
- **4xx/5xx responses** for any resource (page, image, script, font, XHR/API).
- **Missing resources** (404 assets) → broken-image / broken-script findings.
- **Mixed content** (HTTP resource on HTTPS page).
- **CORS failures**, blocked requests.
- **Redirect chains / loops**, slow requests over a threshold.

### 14.3 Broken links & HTTP errors (site-level revalidation)
- Aggregate every unique discovered URL (internal + external + asset).
- Revalidate with **HEAD**, falling back to **GET** for servers that reject HEAD.
- Classify: OK / redirect (with final status) / 4xx / 5xx / timeout / DNS-fail.
- **Map each broken URL back to every referencing page + the anchor text/selector** so the report says exactly where to fix it.
- External links validated for status only (not crawled); rate-limited and cached to avoid hammering third parties.

### 14.4 Forms, buttons, navigation, interaction validation
For each page's cataloged interactive elements (runs **last** in the per-page pipeline because it mutates state; ideally on a fresh context per page to avoid contaminating other analyzers):
- **Forms:** every form has a submittable action; required fields enforce validation; client-side validation messages appear; submitting with valid dummy data does not throw JS errors or 5xx (non-destructive: skip forms that look like real purchases/account changes unless explicitly authorized — safety first).
- **Buttons/CTAs:** clickable, have accessible names, don't 404, don't throw on click, aren't dead (`href="#"`/`javascript:void(0)` with no handler).
- **Navigation:** primary nav links resolve; dropdowns/menus open; mobile hamburger works at mobile breakpoints; breadcrumb correctness.
- **Keyboard/focus:** overlaps a11y (§11) — tab order, focus visibility, no traps, ESC closes modals.
- **Interactive widgets:** carousels/accordions/tabs/modals open/close without errors.

Safety guardrails: destructive-action detection (delete/pay/logout/submit-to-external), a global "do not submit forms" default with an opt-in, and never following logout links mid-crawl.

---

## 15. UI/UX Consistency & Visual Analysis

Two techniques:

1. **Design-token extraction & drift (site-level):** across all pages, harvest computed styles of key elements — palette (distinct colors used for text/bg/buttons), typography (font families, sizes, weights), spacing scale, border-radii, button/heading styles. Flag **inconsistency**: e.g., 14 shades of "primary blue", 9 button styles, mismatched heading sizes for the same level, inconsistent spacing. This is the objective, quantifiable core of "UI/UX consistency".
2. **Visual regression / anomaly (optional, per-page):** `pixelmatch`/`odiff` to compare against a baseline (for re-runs) or to detect obvious layout breakage (huge blank areas, invisible text via same-color-on-color, overlapping elements from bounding-box math).

Plus heuristic UX checks: contrast (from a11y), tap-target size (from responsive), text truncation/overflow, missing favicon, no visible focus states, inconsistent link styling, images without dimensions (causing CLS).

**AI layer (graceful):** feed per-page and per-breakpoint screenshots to `AiVisionClient` for holistic UX critique (visual hierarchy, alignment, crowding, readability) — exactly mirroring `AiScreenReviewer` on the APK side. Purely additive; null on failure.

---

## 16. Security Checks

Lightweight, non-intrusive, **passive** security posture (not a pentest):
- **Security headers:** `Content-Security-Policy`, `Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options`/frame-ancestors, `Referrer-Policy`, `Permissions-Policy` — presence & sane values.
- **TLS/HTTPS:** HTTPS enforced, HTTP→HTTPS redirect, no mixed content, certificate validity/expiry, weak protocol/cipher (optional deep check via `testssl.sh`).
- **Cookies:** `Secure`, `HttpOnly`, `SameSite` flags on session cookies.
- **Information exposure:** verbose `Server`/`X-Powered-By` version banners, exposed source maps in prod, leaked stack traces on error pages, exposed `.git`/`.env`/backup files (safe, well-known-path probes only), directory listing.
- **Outdated client libraries** with known CVEs (fingerprint JS lib versions → advisory feed).
- **Forms:** password fields over HTTPS only; CSRF-token presence heuristic; autocomplete on sensitive fields.
- **CSP violations** observed at runtime (from §14.1).

Explicitly **out of scope for v1** (and gated behind an "I own this site / authorized" attestation even in future): active injection/XSS/SQLi probing, brute force, anything that mutates data or could be construed as an attack. Keep it strictly passive and authorization-aware.

---

## 17. Issue Model, Deduplication & Merging

### 17.1 Canonical Issue model
Every analyzer emits into one normalized shape (reuse the existing `Finding`/`Severity` philosophy):
```
Issue {
  id,                       // stable hash (see 17.2)
  category,                 // PERFORMANCE | A11Y | SEO | SECURITY | BROKEN_LINK |
                            // JS_ERROR | NETWORK | RESPONSIVE | UI_UX | BEST_PRACTICE | FUNCTIONAL
  severity,                 // CRITICAL | HIGH | MEDIUM | LOW | INFO
  title, description, recommendation,
  wcagCriterion?, lighthouseAuditId?, ruleId?,   // provenance
  source,                   // AXE | LIGHTHOUSE | PA11Y | CUSTOM | AI | NETWORK
  scope,                    // PAGE | SITE
  affectedPages: [url...],  // one issue can span many pages
  element?: { selector, snippet, screenshotPath },
  evidence: { screenshotPath?, harEntry?, logLine?, request? },
  confidence,               // 0..1 (AI/heuristic findings < 1)
  firstSeen, count
}
```

### 17.2 Deduplication strategy
Multiple sources will report the same problem (Lighthouse **and** axe both flag contrast; the same missing-alt image appears on 50 pages). Merge by a **canonical signature**:
- **Signature = hash(category + ruleId/auditId + normalized-selector + normalized-message).**
- **Cross-tool merge:** a mapping table equates equivalent rules across engines (e.g., axe `color-contrast` ≡ Lighthouse `color-contrast` ≡ WCAG 1.4.3) so they collapse to one Issue with `source: [AXE, LIGHTHOUSE]`.
- **Cross-page rollup:** the same signature on many pages becomes **one** site-level Issue with `affectedPages: [...]` and a `count`, instead of N duplicates — this is what keeps a big-site report readable.
- **Template detection:** issues appearing on every page of a template (header/footer) are grouped as "site-wide (in template)".
- **Confidence merge:** when sources agree, confidence rises; AI-only findings stay clearly labeled and lower-confidence.

### 17.3 Severity scoring
Normalize each tool's own scale to our 5-level Severity via a mapping table (axe impact, Lighthouse score-weight, HTTP status class, WCAG level, security header criticality). Compute a **site health score** (0–100) per category and overall, weighted by severity and prevalence — gives the report a single headline number like Lighthouse does.

---

## 18. Report Generation & Structure

### 18.1 Outputs
- **Live JSON** in `ReportStore` (the UI renders from this) + DB backup, mirroring the APK side.
- **Consolidated HTML report** (server-rendered, downloadable) — align it to the "Zinc / Deep Indigo" design system so it doesn't look like the un-aligned legacy report.
- **Machine JSON export** for CI integration.
- Optional **PDF/CSV** exports later (the app already does xlsx/csv/html exports elsewhere).

### 18.2 Report structure
```
1. Overview
   • URL, scan date, duration, pages discovered/analyzed, sampling notes
   • Overall health score + per-category scores (radar/gauge)
   • Severity distribution (critical→info counts)
   • Site profile (tech stack, page count, sitemap coverage)

2. Executive summary (AI-generated, graceful)
   • Top 5 most impactful issues in plain English

3. Per-category sections (each: score, issue list, affected pages)
   • Performance (Core Web Vitals table, worst pages, Lighthouse diagnostics)
   • Accessibility (WCAG conformance table, violations by criterion)
   • SEO • Security • Best Practices
   • Broken Links (table: URL, status, referenced-from)
   • JS/Console & Network errors
   • Responsive (per-breakpoint findings + screenshots)
   • UI/UX consistency (token drift visualizations)

4. Per-page drill-down
   • For each analyzed page: screenshot, per-category scores, its issues,
     responsive thumbnails, metrics

5. Evidence appendix
   • Screenshots, HAR downloads, link graph, raw Lighthouse JSON
```

Each Issue renders with severity chip, category, affected-pages count, evidence screenshot, and remediation. Filterable/sortable by severity + category (client-side, like existing views).

### 18.3 Diffing across runs
Because Issue IDs are stable hashes, two runs can be diffed: **new / fixed / persisting** issues — enabling "did our last deploy regress accessibility?" This is a high-value enterprise feature and comes almost for free from the stable-ID design.

---

## 19. Scalability & "Any Size" Handling

- **Bounded everything:** queue size, concurrency, depth, pages, wall-clock — no unbounded growth.
- **Browser pool** sized to host resources; contexts recycled; memory watchdog restarts the worker if RSS crosses a ceiling (mirror `DeviceWatchdog`).
- **Sampling for expensive audits** (Lighthouse, full interaction) with **honest coverage logging**; cheap checks (links, metadata, headers) run on all pages.
- **Streaming, not batch:** findings stream to the UI/DB as they're found, so partial results survive a crash and the user sees progress immediately.
- **Horizontal path (v2):** swap stdio for a **queue (e.g., Redis/BullMQ or the existing DB as a queue)** + multiple worker machines, each pulling page-jobs. The Issue model, dedup, and report assembly already assume distributed page-jobs, so this is an infra change, not a redesign.
- **Caching:** external-link status and third-party resource results cached within a run (and optionally across runs) to cut redundant network calls.
- **Backpressure:** if Spring can't keep up ingesting findings, the worker blocks on the stdio write — natural flow control.

---

## 20. Authentication & Advanced Crawling (v2 roadmap)

Out of v1 scope but designed-for:
- **Auth support:** a login step (credentials or a stored `storageState` cookie/session) so the crawler can test behind a login. Playwright's `storageState` makes this clean.
- **Multi-step user flows / E2E scenarios** (checkout, signup) as scripted journeys.
- **Scheduled monitoring** (reuse the app's scheduling patterns) for regression tracking over time.
- **CI webhook / API** to run scans on deploy and fail builds on new critical issues.

---

## 21. Fit With the Existing App (concrete mapping)

| Existing pattern | Web module counterpart |
|---|---|
| `AnalysisSession` / `CompatSession` (ConcurrentHashMap store) | `WebTestSession` + `WebSessionStore` |
| `AnalysisController` `/api/analyze` | `WebTestController` `/api/web` (scan, poll, stop, report, artifacts) |
| `ExecutionLockService` (one run at a time) | Same lock acquired by web scans |
| `ReportStore` live cache + DB backup | Same duality for web findings |
| `RunBridgeService` shadow `TestRun` | Web scans create shadow runs → unified history |
| External `adb`/Appium processes driven by Spring | External **Node worker** driven by Spring (`ProcessBuilder`, watchdog) |
| `ai/` clients, graceful degradation | Reused for web screenshot/DOM review, same null-safe rule |
| `/live-screen` PNG endpoint | `/api/web/sessions/{id}/live-screen` |
| Artifact serving with path-traversal guard | Same endpoint serves web screenshots/HAR |
| SPA renders views from JSON via CSS classes | New "Website Testing" sidebar module + views, same design system |
| Flyway migrations | New tables for web sessions/issues |

**New REST surface (proposed, matching conventions):**
```
POST   /api/web/scan                     { url, options } → sessionId
GET    /api/web/sessions/{id}            poll session + progress + findings
POST   /api/web/sessions/{id}/stop
GET    /api/web/sessions                 list history
GET    /api/web/sessions/{id}/report     consolidated JSON
GET    /api/web/sessions/{id}/report.html
GET    /api/web/sessions/{id}/live-screen (PNG)
GET    /api/web/sessions/{id}/artifacts/{*relative}   (guarded)
GET    /api/web/sessions/{id}/export.csv
```

---

## 22. Recommended Stack — Final

| Layer | Choice |
|---|---|
| Orchestration / API / UI / DB / auth | **Spring Boot (existing)** |
| Analysis runtime | **Node.js 20 LTS worker (sidecar)** |
| Browser automation | **Playwright** |
| Crawler | **Crawlee** (Cheerio + Playwright hybrid) |
| Perf / Best-practices / PWA | **Lighthouse** (programmatic, over Playwright's CDP) |
| Field CWV | **web-vitals** |
| Accessibility | **axe-core** (+ optional **pa11y**) |
| SEO | **Custom analyzer** + Lighthouse SEO |
| HTML validation | **vnu / html-validate** |
| Security | **Custom passive analyzer** (+ optional testssl.sh) |
| Broken links | Playwright/Crawlee network + HEAD/GET revalidation |
| Visual diff | **pixelmatch / odiff** |
| AI review | **Existing AiVisionClient** (Claude default, OpenAI, Ollama) |
| IPC | **JSON-lines over stdio** (v1) → queue/HTTP (v2) |
| Packaging | **Docker image** with Chromium + Node baked in |

---

## 23. Phased Implementation Roadmap

**Phase 0 — Foundations**
Node worker skeleton + stdio protocol; Spring `WebTestController`/`WebTestSession`/`WebTestOrchestrator`; lock integration; a scan that just loads the homepage and returns one screenshot end-to-end. Proves the boundary.

**Phase 1 — Crawl + core analyzers**
Crawlee crawler with scope/budgets; per-page pipeline with navigation instrumentation; JS/console + network capture; broken-link site-level pass; basic report + SPA view.

**Phase 2 — Audit depth**
Lighthouse, axe-core, SEO analyzer, security headers, performance metrics; Issue model + dedup/merge + severity scoring + health score.

**Phase 3 — Visual & responsive**
Responsive breakpoints, screenshots (full/element), UI/UX token-drift, interaction/form validation.

**Phase 4 — Polish & enterprise**
AI enrichment; consolidated HTML/PDF report aligned to the design system; run diffing; exports; sampling/coverage transparency.

**Phase 5 — Scale (v2)**
Auth crawling, queue-based horizontal workers, scheduling, CI webhook.

---

## 24. Key Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Chromium/Node deployment pain (missing libs) | Ship a Docker image with deps baked; pin Playwright browser version |
| Huge sites blow up time/memory | Hard budgets + sampling + memory watchdog + honest coverage logging |
| Lighthouse slowness dominates runtime | Sample by page-template; run cheap metrics everywhere |
| Duplicate-noise makes reports unreadable | Cross-tool + cross-page dedup is a first-class, tested component |
| Crawler traps / infinite URLs | Depth caps, URL-similarity trap detection, canonical respect |
| Accidentally attacking / mutating a site | Passive-only security, no-form-submit default, robots + authorization attestation, skip destructive links |
| Java↔Node contract drift | Versioned protocol, schema validation on both ends |
| Flaky results | Auto-waiting (Playwright), retries with backoff, network-idle gating, throttled Lighthouse for reproducibility |
| One-run-at-a-time lock limits throughput | Acceptable for v1 (matches current app); v2 horizontal workers lift it |

---

## 25. Open Questions to Confirm Before Build

1. **Deployment target** — is Docker acceptable for shipping the Node+Chromium worker, or must it be a bare-process install like the current adb/Appium setup?
2. **Scale expectation for v1** — typical site size (hundreds vs tens of thousands of pages)? Sets default budgets.
3. **Auth in scope for v1** or strictly public pages first?
4. **Security depth** — passive-only (recommended) confirmed, or is authorized active scanning wanted later?
5. **AI budget** — enrich every page, or only pages with findings, to control token cost?
6. **Report priority** — in-app interactive report first, or downloadable HTML/PDF first?

---

*End of research document. On approval, implementation proceeds per the Phase roadmap (§23), starting with Phase 0 to validate the Java↔Node boundary before building analyzer depth.*
