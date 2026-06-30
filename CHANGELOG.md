# Changelog

## x.x.x

### Added
- "Create Profiles from Folder" — scan a root folder (e.g. `~/Developer/RemoteGit`) and turn its
  org/repo tree into profiles in one pass: each subfolder becomes a profile, its subfolders become
  the projects. A checkbox tree lets you prune anything you don't want; real-looking repos (have
  `.git`/`.idea`/a build file) start checked, tooling/build folders start unchecked, and a folder
  with no real projects starts unchecked. Same-named profiles merge (new paths added) instead of
  duplicating. Available from the dropdown and the settings page. Backed by a pure, unit-tested
  `engine/FolderScanner`.
- Quick-switch and cycling actions (no default shortcuts — bind them in Settings → Keymap):
  "Switch Profile…" (speed-search popup), "Switch to Next Profile", "Switch to Previous Profile".
- "Update Active Profile from Open Windows" — re-capture your currently open windows into the active
  profile without visiting settings.
- Project counts in the dropdown rows ("Name — N projects").
- Settings page: reorder profiles (move up/down), duplicate a profile, and "Remove missing" to drop
  project paths whose folder no longer exists (missing paths are flagged in the list).
- Export / Import profiles as JSON (settings page) for backup or moving between machines; round-trip
  covered by `io/ProfilesJson` unit tests.

### Changed
- Shared the profile-switch popup rows between the toolbar dropdown and the new quick-switch action
  (`ui/ProfilePopups`); unified user-facing balloons behind a single `Notifications` helper.
- Reduced internal-API usage flagged by the Marketplace verifier to a single, unavoidable call.
  Dropped the redundant `updateCustomComponent` override (the base `ExpandableComboAction` already
  renders text/icon from the presentation) and now read the plugin's own version from a build-filtered
  resource instead of a `@Internal` plugin-descriptor lookup. Only `forceCloseProjectAsync` (the
  veto-free project close, which has no public equivalent) remains, on all supported builds.

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

- Per-profile color and icon (a built-in icon or a typed emoji), shown in the toolbar and the
  dropdown.
- Empty-profile guard: switching to a profile with no (existing) projects leaves your open windows
  unchanged and explains, instead of closing everything; creating an empty profile asks first.

### Notes
- Platform-level plugin (depends only on `com.intellij.modules.platform`); loads in IntelliJ IDEA,
  WebStorm, PyCharm, GoLand, etc. `sinceBuild = 253` (2025.3.2+).
