# Profiles

Group your projects into named sets — Work, Home, a client, a side project — and switch between them
from one dropdown in the main toolbar. Pick a profile and it opens that profile's projects, each in
its own window, and closes the ones that aren't in it, so your open windows always match whatever
you're working on.

It's a profile switcher in the plain sense: many projects per profile, and you switch to them by
switching profiles. These are your real, separate projects — never merged into one window — so run
configurations, indexes, branches and open files are all kept exactly as you left them.

## Where you use it

- **In the editor** — the Profiles dropdown sits in the main toolbar, next to the project and branch
  widgets. Switch profiles without leaving your work.
- **On the Welcome screen** — a *New Profile Workspace* button builds a profile and opens all its
  projects before you've opened anything.

## Features

- Group any number of projects into a profile.
- One click switches the whole set: opens the profile's projects, closes the rest.
- Give each profile a color and an icon — a built-in icon or your own emoji — shown in the toolbar.
- Create a profile from the projects you already have open, or by picking folders.
- Manage profiles — rename, recolor, add/remove projects, delete — under Settings > Tools > Profiles.
- Light on memory: only the active profile stays open.
- Switch as fast as you like; it settles on your last pick.

Works on the New UI, IntelliJ 2025.3.2 and newer, across the IntelliJ-based IDEs — IntelliJ IDEA,
WebStorm, PyCharm, GoLand, and the rest.

## Getting started

1. Open the projects you want in a set, click the **Profiles** dropdown in the toolbar, and choose
   **Save Current Windows as Profile**. Name it (say, "Work").
2. Do the same for another set ("Home").
3. Now switch: pick a profile from the dropdown and your windows swap to that set.

No project open yet? Use **New Profile Workspace** on the Welcome screen to pick folders and open them
as a profile. Fine-tune colors, icons and membership any time under **Settings > Tools > Profiles**.

## Install

- **Marketplace:** Settings > Plugins > Marketplace, search for "Profiles".
- **From disk:** grab the zip from
  [Releases](https://github.com/ArcaneArts/Intelij-Profiles/releases), then Settings > Plugins > gear
  icon > Install Plugin from Disk.

## Building from source

A JDK 21 toolchain is used; the Gradle daemon JVM is pinned to 21, so `./gradlew` works whatever your
default JDK is.

| Command | What it does |
|---------|--------------|
| `./gradlew runIde` | Launch a sandbox IDE with the plugin |
| `./gradlew buildPlugin` | Build the installable zip (`build/distributions/`) |
| `./build.sh` | Build and drop the zip into `OUT/` |
| `./gradlew test` | Run the unit tests |

## How it works

Switching is a small state reconciler: your pick is the desired set of projects, and the engine drives
the open windows to match it — opening what's missing (de-duplicated by real path, so never a second
window for an already-open project), force-closing what's extra, and focusing the primary. It's
serialized and coalesces to your last pick, opens the first project before closing anything (so the
IDE never quits or flashes the Welcome screen mid-switch), and keeps memory low by closing inactive
projects. Switching to an empty profile leaves your windows untouched.

## License

Open source under the [GPL-3.0](LICENSE). Issues and pull requests welcome.
