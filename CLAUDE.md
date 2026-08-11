# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Full build (compile, test, package frontend into JAR)
mvn clean verify

# Run backend in development mode
mvn -pl simulation-launcher -am spring-boot:run

# Run only specific module tests
mvn -pl simulation-core test
mvn -pl simulation-application test

# Build final executable JAR
mvn package                                      # → simulation-launcher/target/three-body-lab.jar

# Run the packaged JAR (JDK 17 only, no Node.js needed)
java -jar simulation-launcher/target/three-body-lab.jar

# Frontend (in frontend/ directory)
npm install                                      # first time only
npm run dev                                      # dev server on :5173, proxies /api and /ws to :8721
npm run build                                    # type-check + Vite production build
npm test                                         # Vitest unit tests
npm run test:e2e                                 # Playwright E2E (uses mock mode, no backend needed)
npm run generate:contracts                       # regenerate src/generated/ from contracts/
npm run verify                                   # full frontend pipeline: contracts → types → test → build → E2E
```

## Module Architecture

```
simulation-core              Pure Java, zero framework dependencies. RK4 integrator, softened gravity, metrics, 7 presets (A–G), config validation. All SI units.
         ↓
simulation-application       Depends only on core + Jackson. Experiment state machine (QUEUED→RUNNING→PAUSED→COMPLETED/CANCELLED/FAILED), single-threaded sequential queue worker, file persistence, event dispatcher. No Spring/Swing/AWT.
         ↓
    ┌─────────┴─────────┐
    ↓                   ↓
simulation-web           simulation-swing
Spring Boot REST + WS    Old Swing GUI adapter
(12 endpoints, 6 WS      (delegates physics to core,
 event types)            no Spring dependency)
    ↓                   ↓
    └─────────┬─────────┘
              ↓
simulation-launcher       Entry point. Spring Boot @SpringBootApplication, auto-opens browser on startup.
                          Bundles frontend dist/ → classpath:/static/ via exec-maven-plugin.
```

**Key rule**: simulation-core and simulation-application must remain framework-free (no Spring, no Swing, no AWT, no filesystem in core). The web layer adapts application-layer abstractions to HTTP/WS.

The old monolithic file `src/main/java/com/threebody/ThreeBodySimulation.java` has been replaced by this modular structure and should not be referenced or restored. Tests live under `src/test/java/` in each module.

## Contract-First Development

`contracts/openapi.yaml` and `contracts/ws-events.schema.json` are the source of truth shared by backend and frontend.

After changing contracts:
```bash
cd frontend && npm run generate:contracts    # regenerates src/generated/openapi.ts + ws-events.ts
```

All frontend business code imports types from `src/contracts/index.ts` (the facade), never directly from `src/generated/`. The facade adds helpers: `allowedActions()`, `isTerminalStatus()`, `TERMINAL_STATUSES`, `LIVE_TRAIL_LIMIT` (8000), `ARCHIVE_POINT_LIMIT` (50000).

## Real-Time Data Flow

1. `ExperimentService.runLoop()` (single worker thread) runs RK4 steps
2. Publishes via `AsyncExperimentEventDispatcher`:
   - **latest-wins**: SNAPSHOT (60Hz), TRAJECTORY (60Hz), METRICS (2Hz) — newest overwrites
   - **reliable FIFO**: STATUS, NEAR_ENCOUNTER, ERROR — dequeued in order, capped at 512/256 then listener detached
3. `ExperimentWebSocketHandler` subscribes as an `ExperimentEventListener`, serializes to JSON envelopes, pushes to connected clients via per-session mailbox
4. `ArchiveBatchWriter` decouples simulation from disk I/O: batches 512 points, timer-flushes at 1s, downsamples when exceeding point limit

**Reconnect protocol**: Client calls `GET /experiments/{id}` for full state, then discards WebSocket messages with `sequence ≤ lastSequence`.

## Frontend Architecture

Two runtime modes controlled by `VITE_API_MODE`:
- **`live`** (default): proxies to local Java backend at `127.0.0.1:8721`
- **`mock`**: MSW intercepts all HTTP/WS; in-browser RK4 engine (`mockEngine.ts`) + state machine (`mockRepository.ts`) + WS broadcast loop (`mockScheduler.ts`) provide full offline capability

Store hierarchy:
- `useDraftStore` — parameter editing, local+server validation, unit conversion
- `useExperimentsStore` — CRUD, WebSocket lifecycle, live state interpolation (`SnapshotBuffer`), trail rings (`TrajectoryBuffer`), 3s polling fallback
- `usePreferencesStore` — projection plane, unit system, display toggles (persisted to localStorage)

Canvas rendering: three layered canvases (grid, trails, dynamic bodies) with adaptive DPR quality controller, Ramer-Douglas-Peucker trail simplification, and Hermite velocity-aware interpolation.

## Unit System

Backend: **SI only** (kg, m, m/s, s, J). Frontend displays in Astronomical units (solar mass, AU, km/s, years) when selected, but all API/WS/state data stays SI. Conversion is bidirectional and lossless in `lib/units.ts`.

## Persistence

- No database. JSON files in `%LOCALAPPDATA%\ThreeBodyLab` (Windows) or `~/.threebody-lab` (others).
- `experiments.json` — manifest (RW-lock protected, atomic writes via temp file + rename)
- `trajectory-{id}.json` — JSONL trajectory archives
- Corrupted manifests auto-isolated to `.corrupted/` subdirectory
- On startup, RUNNING experiments are restored to PAUSED (crash recovery)

## Concurrency Model

- Single worker thread processes the experiment queue sequentially (at most one experiment RUNNING)
- `Experiment` aggregate uses `volatile` fields + `synchronized` accessors (worker writes, REST threads read)
- `FileExperimentRepository` uses `ReentrantReadWriteLock`
- `AsyncExperimentEventDispatcher` uses per-listener mailboxes with daemon thread pool — slow listeners detached rather than blocked

## Coding Conventions

### Java
- 4-space indent, PascalCase classes/enums/records, camelCase methods/fields, UPPER_SNAKE_CASE constants
- Physical quantities: annotate units in field names or adjacent comments (e.g., `massKg`, `timeStepSeconds`)
- Prefer single-responsibility helper methods; clean up unused imports
- Preserve UTF-8 encoding and existing Chinese comments
- This project does not use a linter or formatter — match the surrounding code style
- Swing UI operations must execute on the Event Dispatch Thread

### Commits & Pull Requests
- Commit messages: brief Chinese descriptions, single-change focus. Do not mix unrelated changes.
- PR descriptions must include: behavior changes, verification steps, and related issues
- UI or interaction changes must include screenshots or short videos
- Modifying simulation constants or algorithms requires an explicit rationale and expected impact

### Exclusions
Never commit: `target/`, `node_modules/`, `dist/`, `frontend/.visual/`, `frontend/test-results/`, IDE config (`*.iml`, `.idea/`), build artifacts (`*.class`, `*.jar`)
