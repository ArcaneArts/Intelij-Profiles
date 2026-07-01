# Profiles plugin redesign — design spec

Date: 2026-07-01
Status: Approved

## Problem

The Profiles plugin (an IntelliJ project-profile switcher) has two defects:

1. **Switching garbles the UI.** Switching to a profile opens its projects, but with 6+ projects the
   toolbar / tab bar renders corrupted (overlapping / garbled). Root cause (confirmed against platform
   source): `ProfileSwitchEngine.applyPass` opens up to 32 projects **concurrently**
   (`OPEN_BATCH_SIZE = 32`, `async{ openInOwnFrameAsync(path) }.awaitAll()`) and closes up to 32
   concurrently. JetBrains' own `RecentProjectsManagerBase.openOneByOne()` **strictly serializes**
   `openProjectAsync` whenever a frame already exists (always true for a running-IDE switch). Racing
   frame-builds + toolbar/native-tab construction on the EDT corrupts rendering and amplifies a still-open
   macOS native-tab bug (IJPL-43592). There is no frame-ready settle between opens.

2. **The toolbar "bar" itself is buggy.** The widget has no live subscription to switch state — it
   relies on the throttled `ActivityTracker.inc()`, so it can stick on "Switching to X…". It injects
   raw user emoji with no width cap into a fixed-width combo, causing width blow-out / misalignment.
   `SwitchStatus.Failed` is a dead render path.

Separately, the plugin leans on internal IntelliJ APIs. The user wants to minimize internal-API usage
while keeping the same supported version range.

## Goals

- Eliminate the switch-time UI corruption by driving opens/closes **serially with settle**, mirroring
  the platform.
- Rewrite the switch engine from scratch around a serial sequencing model.
- Rewrite the toolbar widget on public API; fix stale label, emoji width, and the dead `Failed` path.
- Reduce internal-API usage to the irreducible minimum, isolated behind one gateway with public
  fallbacks (pragmatic reduction — direct calls, not reflection).
- Keep the supported version range: **build 253+ (2025.3.2+), open-ended upper bound.**

## Non-goals

- No backward-compatibility shims (project rule: make breaking changes directly, delete old code).
- No change to persistence format, the `Profile` model, JSON import/export, folder scanning, settings
  UI, or the action set beyond what the rewrite requires.
- Not attempting to remove the two internal APIs that have no public equivalent on 253+ (forced
  new-frame open; veto-free close) — confirmed impossible without behavior loss.

## Decisions (from brainstorming)

- **Internal-API stance:** Pragmatic reduction. Remove internal APIs that have public equivalents;
  isolate the 2 unavoidable ones behind a defensive gateway with public fallbacks + capability probing.
  Keep current UX. Verifier reports 1-2 benign items the Marketplace already accepts.
- **Overhaul scope:** Full engine rewrite (new serial sequencing model), retaining the correct patterns
  (level-triggered reconcile, injectable gateway test seam, pure `ReconcilePlanner`/`TargetResolver`).
- **Symptom:** windows open in separate frames but toolbar/tab bar garbles; shows at 6+ projects.
- **Version range:** 253+ (2025.3.2+), open-ended.

## Architecture

Three cleanly separated, independently testable units:

### SwitchEngine (rewritten)

- **Input / coalescing:** `requestSwitch(profile)` publishes to a conflated `StateFlow`; a single
  long-lived consumer uses `collectLatest` so rapid switches cancel the superseded pass and settle on
  the last pick. A monotonic generation id defeats StateFlow conflation so re-selecting the active
  profile still re-asserts/repairs it.
- **Serial reconcile pass** (no concurrency anywhere):
  1. Resolve + dedup targets by canonical real path (`ProjectPaths`/`canonicalKey`). Empty-profile
     guard: a profile with no existing projects leaves windows unchanged and notifies — never closes
     everything to nothing.
  2. Compute `{toOpen, toClose, keep}` via the pure `ReconcilePlanner` (kept + reused).
  3. **Open primary first**, awaited to readiness — window count never hits zero (no Welcome flash). If
     the primary can't open, leave every window untouched this pass and retry.
  4. **Close extras one at a time**, each awaited (keyed extras + any project with no canonical
     identity; never the primary or a target).
  5. **Open remaining targets one at a time**, each awaited to readiness before starting the next.
  6. Focus the primary once.
- **Settle between ops:** after `openProjectAsync` returns, await frame/startup readiness — bridge
  `StartupManager.runAfterOpened` to a suspend point plus one EDT flush — **bounded by a timeout** so a
  slow project can't wedge the reconciler. This lets macOS native-tab reconciliation
  (`invokeLater(updateTabBars)`) finish before the next open. Exact hook (bounded `runAfterOpened` +
  EDT flush vs. a frame-present poll) is pinned during implementation against the real sandbox.
- **Level-triggered retry:** re-read ground truth each pass; repeat until converged or `MAX_PASSES`.
  Robust against the user manually closing a window mid-switch.
- **Deletions:** `OPEN_BATCH_SIZE`/`CLOSE_BATCH_SIZE`, all `async{…}.awaitAll()` bursts, and the
  `ActivityTracker` refresh nudge (replaced by the toolbar's live flow subscription).
- **Status:** `SwitchStatus` (Idle/Switching/Failed) published on a `StateFlow` the toolbar collects.

### ProjectGateway (reworked ProjectWindows)

The only file touching platform project APIs. The engine depends on it via an interface (existing test
seam preserved). Each internal call is `try`-wrapped with a public fallback + capability probe (direct
calls, not reflection):

| Operation | Primary (internal) | Fallback (public) | Verdict |
|---|---|---|---|
| Open in new frame | `ProjectManagerEx.openProjectAsync` + `OpenProjectTask(forceOpenInNewFrame)` | `ProjectUtil.openOrImportAsync` → public `ProjectManager` open | Keep (no public new-frame API) |
| Veto-free close | `forceCloseProjectAsync(save)` | public `ProjectManager.closeAndDispose` | Keep (no public veto-free close) |
| Focus window | — (removed) | `WindowManager.getFrame(project)` + toFront/requestFocus | Removed → public |
| Identity / liveness | public `ProjectManager.openProjects`, `project.basePath` | — | public |

Gateway responsibilities: `liveProjects()`, `keyOf()`, `openByKey()` (canonical-key dedup),
`openInNewFrame(path)` (dedup-then-open; already-open → focus + return; **awaits settle**),
`close(handle)` (veto-free, escalate save=true→false, then public fallback, awaits dispose),
`focus(handle)` (public WindowManager path).

Net internal surface after redesign: only the open + close paths, all in this one file, all documented,
all with graceful degradation. `verifyPlugin` reports these as accepted (Marketplace allows them).

### ProfileToolbarWidget (rewritten)

- Public `CustomComponentAction` rendering a toolbar button matching the Project/Branch widgets; opens
  the existing `ProfilePopups` dropdown on click. Replaces the impl-package `ExpandableComboAction`.
- **Live state:** the component collects `SwitchEngine.status` in a component-scoped coroutine, so the
  label updates correctly during and after a switch (fixes the stuck "Switching to X…").
- **Emoji/width safety:** icon rendered separately from text; user emoji sanitized + length-capped;
  fixed max preferred width so it can never blow out and crowd neighbors.
- Renders the `Failed` state (currently dead).
- The dropdown popup (`ProfilePopups`), presentation helpers (`ProfilePresentation`, `ProfileIcon`),
  and project-count rows are kept; the label/width/emoji-sanitize logic is factored into pure functions
  for unit testing.

### Unchanged

`ProfilesService`, `Profile`, `ProfilesJson`, `FolderScanner`, `ProjectPaths`, `ProfilesConfigurable`,
`ProfilesPanel`, and all `actions/*` (they already call `requestSwitch`). `ReconcilePlanner` and
`TargetResolver` are kept and reused.

## Error handling

- Open fails → retry next pass; primary can't open → leave windows untouched, retry.
- Close fails/veto → escalate save=false → public `closeAndDispose` → per-close timeout + continue;
  report unresolved extras in a notification.
- Settle times out → proceed to next op (bounded, never wedges).
- Empty profile / all-missing paths → leave windows unchanged, notify.
- Coalesced switch (superseded) → stop issuing new opens; in-flight open resolves to a clean handle or
  is safely discarded.
- Non-converged after `MAX_PASSES` → notify with counts; publish `Failed` then `Idle`.

## Testing

- **Engine (unit, injected fake gateway):** the fake **records call order and fails if any two opens or
  closes overlap** — locks in serialization. Cover: primary-opens-first, serial close-then-open,
  settle awaited before next op, coalescing to last pick, empty-profile guard, retry-to-convergence,
  non-convergence path. Reuse `ReconcilePlannerTest`, `TargetResolverTest`.
- **Toolbar (unit):** pure status→label mapping, emoji sanitize, width-cap logic, `Failed` rendering
  decision.
- **Manual verification (mandatory):** `./gradlew runIde`; create a 6+ project profile; switch; confirm
  no toolbar/tab garble; verify serial open feel and correct final window set. Repeat on **253 and 261**
  (both artifacts present). Run `./gradlew verifyPlugin` and record exactly which internal items remain.

## Version / build

- `sinceBuild = 253`, open-ended `untilBuild`. Compile against the 253 floor (as today) so newer-only
  APIs cannot be used accidentally. Every public API in this design exists across 253→261+.

## Notes

- Per user rules: no git commits are performed by the assistant; the user handles git. Changelog
  entries are appended to the `x.x.x` section.
