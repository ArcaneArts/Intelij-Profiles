# Changelog

## x.x.x

## 1.0.1 - 2026-06-29

### Added
- Profiles dropdown in the new-UI main toolbar (next to the Project and Branch widgets), built on
  `ExpandableComboAction` and registered in `MainToolbarLeft`.
- `ProfilesService` — application-level persistent store of profiles (name, color, project paths)
  plus the active-profile pointer, serialized to `arcane-profiles.xml`.
- Profile switching engine (`engine/ProfileSwitchEngine`): a declarative, level-triggered reconciler
  that drives the open project set to exactly the active profile's projects — opens the missing ones
  (canonical real-path identity + dedup-before-open, so never a duplicate window), force-closes the
  extras (veto-free, so none are left behind), and focuses the primary. Only the active profile stays
  loaded (low RAM). Rapid switching is serialized and coalesces to the last pick (cancelling the
  superseded switch); the primary opens before anything closes, so the IDE never quits or flashes the
  Welcome screen mid-switch.
- Creating a profile (toolbar or Welcome screen) immediately switches to it (opens its projects).
- Welcome-screen "New Profile Workspace" button to build and open a profile when no project is open.
- "Save Current Windows as Profile" action that captures all open projects into a new profile.
- "New Profile" action that names a profile and selects any number of project folders at once via a
  multi-select folder picker.
- Settings page (Settings → Tools → Profiles) to rename profiles, add/remove projects via a
  multi-select folder chooser, and delete profiles.
- Warning notification when a profile references project paths that no longer exist on disk.
- Pure path-normalization helper (`ProjectPaths`) with unit tests; `ProfilesService` CRUD unit tests.

### Notes
- Platform-level plugin (depends only on `com.intellij.modules.platform`); loads in IntelliJ IDEA,
  WebStorm, PyCharm, GoLand, etc. `sinceBuild = 242` (2024.2+).
- Hide strategy is pluggable (`WindowVisibilityStrategy`): default fully hides windows; an iconify
  strategy is available as a macOS-ergonomics fallback.
