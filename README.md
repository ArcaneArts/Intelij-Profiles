# Profiles

A JetBrains IDE plugin that adds a **Profile** dropdown to the new-UI main toolbar, next to the
Project and Branch widgets. A **Profile is a named set of real projects** (e.g. "Work" = your 8
projects, "Home" = your 4). Selecting a profile performs a **warm swap**: the profile's project
windows are shown (opening any not yet loaded), and every other window is hidden but kept loaded —
so switching back is instant.

Because these are the *real* projects (never merged into a synthetic workspace), **run
configurations, indexes, open files, and branch state all come along for free** — which is exactly
what merge-based "multi-project" plugins lose.

## Features

- **Profile dropdown** in the new-UI main toolbar; its label shows the active profile.
- **Warm swap** on switch: show the target profile's windows, hide the rest, keep everything loaded.
- **Two ways to build a profile:**
  - *Save Current Windows as Profile* — one click captures every open project.
  - *Manage Profiles* (Settings → Tools → Profiles) — rename, add/remove projects, delete.
- **Platform-level** — loads in IntelliJ IDEA, WebStorm, PyCharm, GoLand, RubyMine, etc.
- Missing project paths (deleted on disk) are skipped with a warning notification.

## Using it

- Click the **Profiles** pill in the toolbar.
- *Save Current Windows as Profile…* captures the projects you have open right now.
- Pick a profile from the list to switch to it.
- *Manage Profiles…* opens **Settings → Tools → Profiles** to edit membership.

## Building

The project targets the IntelliJ Platform via the IntelliJ Platform Gradle Plugin 2.x and builds
with a JDK 21 toolchain. Gradle (9.5, via the wrapper) must run on a JDK it supports, so invoke it
with `JAVA_HOME` pointed at a JDK 21 install:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew build
```

Common tasks:

| Task | Purpose |
|------|---------|
| `./gradlew test` | Run the unit tests |
| `./gradlew buildPlugin` | Assemble the installable zip (`build/distributions/`) |
| `./gradlew runIde` | Launch a sandbox IDE with the plugin for manual testing |

Install the built zip via **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

## Architecture

| Unit | Responsibility |
|------|----------------|
| `ProfilesService` | App-level `@Service` persistence (profiles + active pointer → `arcane-profiles.xml`) |
| `engine/SwitchPlan` | Pure path logic: canonical real-path identity + target resolution (unit-tested) |
| `engine/ProjectWindows` | The only file touching platform project APIs: open-or-focus, force-close, focus |
| `engine/ProfileSwitchEngine` | The reconciler `@Service`: drives the open set to the active profile |
| `ProfileToolbarWidgetAction` | The toolbar dropdown (`ExpandableComboAction`) |
| `ProfilesConfigurable` / `ProfilesPanel` | Settings → Tools → Profiles management UI |
| `SaveCurrentAsProfileAction` / `NewProfileAction` / `NewProfileWorkspaceAction` | Profile creation shortcuts |

Switching is a **declarative, level-triggered reconciler**. A switch publishes a desired profile;
one coroutine consumer (`MutableStateFlow` + `collectLatest`) drives `ProjectManager.openProjects`
toward that profile's set, re-reading ground truth each pass: open the missing targets, force-close
the extras, focus the primary, loop until converged.

Three properties make it correct by construction:

- **One canonical real-path identity** (`toRealPath` — resolves symlinks, `/private`, macOS case) is
  used both to decide "is this target already open?" and "is this open project an extra?". Combined
  with dedup-before-open, this makes duplicate windows impossible.
- **Veto-free force-close** (`ProjectManagerEx.forceCloseProjectAsync`) can't be blocked by unsaved
  changes or a running Gradle sync, so the previous profile's windows always go.
- **Serialized + coalesced** switching: rapid clicks settle on the last pick and cancel the
  superseded switch. The primary opens before anything closes, so the open count never hits zero
  (no IDE quit / Welcome flash). Closing inactive projects keeps memory low (RAM-light).
