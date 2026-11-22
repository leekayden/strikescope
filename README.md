# StrikeScope — Bird Strike Risk Explorer

A Java Swing + FlatLaf desktop app that turns an airport's own wildlife-strike
history into a **relative risk index by month and hour of day**, so a
fixed-size wildlife-control team can be pre-positioned in the windows where
strikes historically cluster.

Student project — "AI in Engineering Application Task" (AY2026/27).

A browser prototype of the same idea lives in `../strikescope-html`; this
desktop app is the full version and covers everything it does (and more:
real `.xlsx` import, damage-weighted view, phase-of-flight/damage/year
profiles, cross-airport heatmap, format documentation).

## Run it

- **Packaged (recommended):** double-click `StrikeScope-<version>.jar` from the
  [latest release](https://github.com/leekayden/strikescope/releases) — one
  self-updating file, needs Java 17+ (e.g. Eclipse Temurin). No installer.
- **From source:** `run.bat` (dev launcher); rebuild with `build.bat`; make the
  distributable jar with `build-release.bat <version>`.

## Releases & silent auto-update

Releasing a new version is one step: bump `AppVersion.VERSION`, commit, then
`git tag v<version> && git push origin v<version>`. A GitHub Actions workflow
builds the fat jar and attaches it to the release automatically.

Installed copies of the app then do the rest with no user interaction:

1. On startup (and every 6 h) the app queries GitHub's public
   `releases/latest` API for `leekayden/strikescope` and compares the tag with
   its own `AppVersion.VERSION`.
2. A newer tag triggers a background download of the release's
   `StrikeScope-*.jar` into an `updates/` folder next to the app, validated as
   a complete jar before use.
3. Windows locks the jar a running JVM was started from, so the swap is done
   by a tiny generated helper script that waits for the app to exit, moves the
   new jar over the old one and relaunches the app. Updates detected shortly
   after launch apply immediately (one invisible restart); updates detected
   later in a session are staged and installed at the next start, before the
   UI appears.

Update diagnostics land in `strikescope-update.log` next to the jar. Running
from a classes folder (dev mode) detects updates but never self-modifies.

## Screens

- **Dashboard** — airport + date + hour, risk gauge, 7-day × 24-hour heatmap,
  auto-generated briefing export (.txt).
- **Risk Explorer** — month × hour heatmap plus strikes/day-by-month and
  hour-weight bar profiles; optional damage-weighted view.
- **Airports** — cross-airport comparison heatmap: one row per airport
  (sorted by strike volume, filterable by minimum records), one column per
  month, each row scaled to that airport's own peak. With the real FAA file
  this shows all 2,800+ airports at once (`ZZZZ` = FAA code for strikes
  reported without an identifiable airport).
- **Airport Profile** — top species, phase of flight, damage donut, strikes
  per year.
- **Validation** — train/test backtest with capture curve, top-10%/20%
  capture rates and day-level correlation.
- **Data & Import** — record table, per-airport summary table (records, year
  coverage, damage %, time coverage), FAA import, demo loader, and a
  **FAA file format…** dialog documenting the exact NWSD Excel layout and
  which columns the app reads. The status line after each import reports
  which columns were matched.

## Where the data comes from

- **Real data (free, legal, public domain):** FAA National Wildlife Strike
  Database — https://wildlife.faa.gov. The bulk download is offered as Excel
  or Access; choose Excel and import the .xlsx directly via
  **Data & Import → Import FAA data (Excel / CSV)…**. The full export
  (~160 MB, 359k+ records, 1990–2026, header row 1, 102 columns A–CX) streams
  in about a minute; verified import: 359,495 records, 0 skipped, 2,806
  airports, all 13 fields StrikeScope uses matched by name.
- **Demo dataset (built in):** ~15,000 synthetic records for KDEN, VIDP and
  WSSS. Clearly labelled as demo everywhere — the numbers are invented. Three
  airports only because it is a demo; real data brings hundreds of modelable
  airports (≥ 50 records each).

## Real-data result (verified 2026-10-31)

For KDEN (Denver Intl), model trained on 1990–2018, tested on 2019–2026:
the top 10% of risk-ranked days captured **24.0%** of all strikes (chance:
10%), the top 20% captured **43.0%** (chance: 20%), day-level correlation
r = 0.60. Top species: Horned lark (3,663 of 12,058 strikes) — consistent
with published FAA wildlife hazard reports for Denver.

## The model (explainable on purpose)

    risk(month, hour) = mean reported strikes/day in that month
                        × hour-of-day activity weight

normalised to the airport's own peak → index 0–100. Hour weights blend
strikes with recorded clock times and the Dawn/Day/Dusk/Night buckets of the
rest. No black box: every number traces back to a count.

## Validation (Validation screen)

Backtest protocol: fit the model on years up to the chosen cutoff, rank every
calendar day in the test years by predicted risk, and measure what share of
actual strikes falls in the top 10% / top 20% of days. A useless model scores
10% / 20% (the dashed diagonal). The demo data scores ≈14% / ≈26%.

## Honest limitations

- The database counts **reported** strikes; reporting improved since 1990 and
  airports with active programmes report more. Cross-airport comparison mostly
  measures reporting effort.
- The index is relative, not a probability.
- Traffic exposure (movements/yr) is not yet normalised — see FAA/BTS data.
- Weather conditioning (wind, visibility) is future work; the current index is
  pure climatology. METAR archives (mesonet.agron.iastate.edu) are the natural
  next data source.

## Source layout

    src/StrikeScopeApp.java    main + FlatLaf setup + headless self-test
    src/UpdateManager.java     silent self-updater (GitHub Releases)
    src/AppVersion.java        version constant (must match the release tag)
    src/FaaCsvParser.java      tolerant FAA CSV/XLSX parser (+ StrikeRecord)
    src/FaaXlsxReader.java     streaming .xlsx reader (zip + StAX, no deps)
    src/StrikeData.java        dataset + aggregate profiles
    src/RiskModel.java         climatology model + backtest
    src/DemoData.java          synthetic demo dataset
    src/Charts.java            hand-painted gauge/heatmap/bars/line/donut
    src/DashboardPanel.java    risk index, 7-day outlook, briefing export
    src/AnalysisPanels.java    explorer / airport profile / validation
    src/AirportsPanel.java     cross-airport comparison heatmap
    src/DataPanel.java         tables, import, demo loader, format dialog
    src/MainFrame.java         sidebar navigation + About & Ethics

Single dependency: FlatLaf (lib/flatlaf.jar, Apache 2.0).
