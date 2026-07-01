# Profiles Plugin Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate switch-time UI corruption by driving project opens/closes serially with a frame-settle, rewrite the toolbar widget on public API, and reduce internal-API usage to the irreducible minimum.

**Architecture:** A serial, level-triggered reconciler (`ProfileSwitchEngine`) drives the open project set to the active profile's set one project at a time, awaiting each platform op (and a bounded frame-settle) before the next — mirroring how JetBrains' own `RecentProjectsManagerBase` serializes opens. All platform project calls stay isolated in one gateway (`ProjectWindows`) with public fallbacks. The toolbar widget is rebuilt on the public `CustomComponentAction` with a live subscription to switch state.

**Tech Stack:** Kotlin, IntelliJ Platform Plugin SDK, kotlinx.coroutines, JUnit4.

## Global Constraints

- **Version floor:** `sinceBuild = "253"` (IntelliJ 2025.3.2), open-ended `untilBuild`. Compile against the 253 floor. Every API used must exist across 253 → 261+.
- **No backward-compatibility shims.** Make breaking changes directly; delete old/unused code (imports, functions, constants) that this work orphans. No deprecated aliases or re-exports.
- **No git commands by the assistant.** The user handles ALL git operations. Each task ends with a verification checkpoint, NOT a commit. Never run `git add`/`commit`/`push`.
- **Strong typing everywhere.** Explicit types; no `dynamic`/loose inference where a type can be declared.
- **No emojis in code/comments/output.**
- **Internal-API stance (pragmatic reduction):** Keep only the two internal calls with no public equivalent (forced-new-frame open; veto-free close), isolated in `ProjectWindows.kt`, each wrapped with a public fallback. Remove internal APIs that have public equivalents (window focus). `ActivityTracker` is public (not internal) and may remain at cosmetic one-shot call sites.
- **Changelog:** append to the `## x.x.x` section of `CHANGELOG.md` (do not bump the version number).

---

## File Structure

- `src/main/kotlin/art/arcane/profiles/engine/ProjectWindows.kt` — **modify.** Public `WindowManager` focus; bounded frame-settle after open; public close + open fallbacks.
- `src/main/kotlin/art/arcane/profiles/engine/ProfileSwitchEngine.kt` — **modify.** Serial open/close (delete concurrency); drop `markActivity`/`ActivityTracker`.
- `src/main/kotlin/art/arcane/profiles/ui/ProfilePresentation.kt` — **modify.** Add pure `toolbarLabel`, `toolbarIcon`, `firstGrapheme` (emoji sanitize + width cap).
- `src/main/kotlin/art/arcane/profiles/ui/ProfilePopups.kt` — **modify.** Add `createMainDropdown(dataContext)`; host `pluginVersion()` + `ManageProfilesAction`.
- `src/main/kotlin/art/arcane/profiles/toolbar/ProfileToolbarWidgetAction.kt` — **rewrite.** `CustomComponentAction` (public) instead of `ExpandableComboAction` (impl).
- `src/main/kotlin/art/arcane/profiles/toolbar/ProfileWidgetButton.kt` — **create.** The custom toolbar component with a live `status` subscription + width cap.
- `src/test/kotlin/art/arcane/profiles/engine/ProfileSwitchEngineTest.kt` — **modify.** Concurrency counters in `FakeWindows`; serialization-order test.
- `src/test/kotlin/art/arcane/profiles/ui/ProfilePresentationTest.kt` — **create.** Pure tests for `toolbarLabel`/`firstGrapheme`/`toolbarIcon`.
- `CHANGELOG.md` — **modify.** Append entries under `## x.x.x`.
- `src/main/resources/META-INF/plugin.xml` — **no change** (widget registered by FQN; base class is irrelevant to registration).

---

## Task 1: Gateway — public focus, frame-settle on open, public fallbacks

**Files:**
- Modify: `src/main/kotlin/art/arcane/profiles/engine/ProjectWindows.kt`

**Interfaces:**
- Consumes: existing `ProjectWindowGateway` interface (unchanged signatures).
- Produces: `ProjectWindows.openInOwnFrameAsync(path)` now returns only after a bounded frame-settle; `focus` uses public `WindowManager`; `closeProject`/open have public fallbacks. No signature changes — the engine and tests are unaffected structurally.

This file is a platform adapter (like I/O); its behavior is verified by the engine's fake-gateway tests (Task 2) plus manual `runIde` (Task 6), not by unit tests here. Verification for this task is a successful Kotlin compile.

- [ ] **Step 1: Replace the imports block**

Replace the top-of-file imports (lines 3-14) with (adds `WindowManager`, `StartupManager`, `suspendCancellableCoroutine`, `withTimeoutOrNull`; keeps `OpenProjectTask`, `ProjectUtil`, `ProjectManagerEx`, `ProjectManager` for the isolated internal/open paths; removes nothing else):

```kotlin
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
```

- [ ] **Step 2: Rewrite `openInOwnFrameAsync` and add the settle + open helpers**

Replace the whole `openInOwnFrameAsync` method (lines 79-103) with:

```kotlin
    /**
     * Open [path] in its OWN window and await it settling (frame created + native tab reconciliation
     * flushed), so the engine's serial loop constructs frames/tabs one at a time. If a project for
     * that folder is already open, focus it and return it. Returns null if the open failed.
     */
    override suspend fun openInOwnFrameAsync(path: Path): ProjectWindowHandle? {
        rawLiveProjects().firstOrNull { matches(path, it) }?.let {
            val handle = IdeProjectHandle(it)
            focus(handle)
            return handle
        }
        val task = OpenProjectTask.build().withForceOpenInNewFrame(true)
        val opened = try {
            openInternal(path, task) ?: openPublicFallback(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Profiles: open failed for $path", e)
            null
        } ?: return null
        awaitSettled(opened)
        return IdeProjectHandle(opened)
    }

    /** Internal forced-new-frame open (no public equivalent on 253+); direct then open/import. */
    private suspend fun openInternal(path: Path, task: OpenProjectTask): Project? {
        val direct = try {
            ProjectManagerEx.getInstanceEx().openProjectAsync(path, task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.debug("Profiles: direct project open failed for $path; falling back to open/import", e)
            null
        }
        return direct ?: ProjectUtil.openOrImportAsync(path, task)
    }

    /** Public last-resort open (may reuse a frame). Only reached if the internal paths are gone. */
    private suspend fun openPublicFallback(path: Path): Project? =
        try {
            @Suppress("DEPRECATION")
            ProjectManager.getInstance().loadAndOpenProject(path.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Profiles: public fallback open failed for $path", e)
            null
        }

    /**
     * Wait until [project]'s startup activities have run (frame built, content installed), bounded so a
     * slow project can't wedge the switch, then flush the EDT once so the platform's native window/tab
     * reconciliation (posted via invokeLater during open) completes before the next open starts.
     */
    private suspend fun awaitSettled(project: Project) {
        if (!project.isDisposed) {
            withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    try {
                        StartupManager.getInstance(project).runAfterOpened {
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
            }
        }
        withContext(Dispatchers.EDT) { /* EDT flush: let queued tab reconciliation run */ }
    }
```

- [ ] **Step 3: Rewrite `closeProject` to add a public fallback**

Replace the whole `closeProject` method (lines 106-122) with:

```kotlin
    /** Force-close [project]: veto-free, no save/confirm dialog; awaits dispose. Returns success. */
    override suspend fun closeProject(project: ProjectWindowHandle): Boolean {
        val ideProject = (project as? IdeProjectHandle)?.project ?: return false
        if (ideProject.isDisposed) return true
        val ex = ProjectManagerEx.getInstanceEx()
        suspend fun attempt(save: Boolean): Boolean =
            try {
                ex.forceCloseProjectAsync(ideProject, save)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("Profiles: forceClose(save=$save) failed for ${ideProject.name}", e)
                false
            }
        if (attempt(true) || ideProject.isDisposed) return true
        if (attempt(false) || ideProject.isDisposed) return true
        // Public last-resort (may prompt): only reached if the internal veto-free close is unavailable.
        return closePublicFallback(ideProject)
    }

    private suspend fun closePublicFallback(project: Project): Boolean =
        withContext(Dispatchers.EDT) {
            try {
                ProjectManager.getInstance().closeAndDispose(project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("Profiles: public close fallback failed for ${project.name}", e)
                false
            }
        }
```

- [ ] **Step 4: Rewrite `focus` to use the public `WindowManager`**

Replace the whole `focus` method (lines 124-136) with (removes the internal `ProjectUtil.focusProjectWindow`):

```kotlin
    override suspend fun focus(project: ProjectWindowHandle) {
        val ideProject = (project as? IdeProjectHandle)?.project ?: return
        if (ideProject.isDisposed) return
        withContext(Dispatchers.EDT) {
            try {
                val frame = WindowManager.getInstance().getFrame(ideProject) ?: return@withContext
                frame.toFront()
                frame.requestFocus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("Profiles: focus failed for ${ideProject.name}", e)
            }
        }
    }
```

- [ ] **Step 5: Add the settle-timeout constant**

Add a companion/object-level constant. `ProjectWindows` is an `object` with no companion; add this line just below `private val LOG = ...` (after line 39):

```kotlin
    private const val SETTLE_TIMEOUT_MS = 15_000L
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. If `loadAndOpenProject` or `getFrame` resolve errors appear, they are public on 253 — re-check the import paths in Step 1.

- [ ] **Step 7: Checkpoint (DO NOT COMMIT)**

Report: gateway now focuses via public `WindowManager`, awaits a bounded frame-settle after each open, and has public fallbacks for open and close. Await review. The user commits.

---

## Task 2: Engine — serialize opens/closes; drop `markActivity`/`ActivityTracker`

**Files:**
- Modify: `src/test/kotlin/art/arcane/profiles/engine/ProfileSwitchEngineTest.kt`
- Modify: `src/main/kotlin/art/arcane/profiles/engine/ProfileSwitchEngine.kt`

**Interfaces:**
- Consumes: `ProjectWindowGateway`, `ReconcilePlanner.plan(targetKeys: List<String>, openKeys: Set<String>): ReconcilePlan` (`.toOpenKeys`, `.toCloseKeys`, `.converged`), `canonicalKey(path: Path): String`.
- Produces: `ProfileSwitchDependencies` loses its `markActivity` field. `ProfileSwitchEngine.publishStatus` no longer nudges. Behavior: opens and closes are strictly serial (max concurrency 1), primary opened first.

- [ ] **Step 1: Add concurrency instrumentation to `FakeWindows`**

Open `ProfileSwitchEngineTest.kt`. In the `private class FakeWindows(...)` body, add these fields and initialize them, and add a `yield()` inside the concurrency-sensitive sections so any accidental parallelism is detected:

```kotlin
        // Concurrency instrumentation: the reconciler MUST open/close one project at a time.
        var openInFlight = 0
        var maxConcurrentOpens = 0
        var closeInFlight = 0
        var maxConcurrentCloses = 0
        var firstOpenedKey: String? = null
```

In `FakeWindows.openInOwnFrameAsync`, wrap the existing body so the counter brackets the whole suspend section, with a `yield()` after incrementing (so a parallel caller would be observed):

```kotlin
        override suspend fun openInOwnFrameAsync(path: Path): ProjectWindowHandle? {
            openInFlight++
            maxConcurrentOpens = maxOf(maxConcurrentOpens, openInFlight)
            try {
                kotlinx.coroutines.yield()
                val key = canonicalKey(path)
                if (key in openFailures) return null
                openByKey()[key]?.let { return it }
                val project = FakeProject(path.fileName?.toString() ?: key, key)
                projects.add(project)
                if (firstOpenedKey == null) firstOpenedKey = key
                return project
            } finally {
                openInFlight--
            }
        }
```

(If the existing `FakeWindows` stores open projects in a differently-named backing list than `projects`, keep that name — only add the `openInFlight`/`maxConcurrentOpens`/`firstOpenedKey` bookkeeping and the `yield()`.)

In `FakeWindows.closeProject`, bracket the whole body the same way:

```kotlin
        override suspend fun closeProject(project: ProjectWindowHandle): Boolean {
            closeInFlight++
            maxConcurrentCloses = maxOf(maxConcurrentCloses, closeInFlight)
            try {
                kotlinx.coroutines.yield()
                closeGates[keyOf(project)]?.await()
                (project as FakeProject).dispose()
                closedKeys.add(project.name)
                return true
            } finally {
                closeInFlight--
            }
        }
```

(Preserve whatever the existing `closeProject` did — gate await, dispose, record — and only add the `closeInFlight`/`maxConcurrentCloses` bracket and the `yield()`.)

- [ ] **Step 2: Write the failing serialization test**

Add this test to `ProfileSwitchEngineTest.kt`:

```kotlin
    @Test
    fun opensAndClosesOneAtATime() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val existing = setOf("/p/a", "/p/b", "/p/c", "/p/d", "/p/e", "/p/f", "/x/old1", "/x/old2")
            val initial = listOf(FakeProject("old1", "/x/old1"), FakeProject("old2", "/x/old2"))
            val windows = FakeWindows(existingPaths = existing, initialProjects = initial)
            val store = FakeStore(activeProfileName = null, profileNames = setOf("Work"))
            val engine = engine(scope, windows, store)

            engine.requestSwitch(
                Profile("Work", null, null, listOf("/p/a", "/p/b", "/p/c", "/p/d", "/p/e", "/p/f")),
            )
            awaitUntil { store.activeProfileName == "Work" }

            assertEquals(1, windows.maxConcurrentOpens)
            assertEquals(1, windows.maxConcurrentCloses)
            assertEquals(
                setOf("/p/a", "/p/b", "/p/c", "/p/d", "/p/e", "/p/f"),
                windows.openByKey().keys,
            )
            assertEquals("/p/a", windows.firstOpenedKey)
        } finally {
            scope.cancel()
        }
    }
```

- [ ] **Step 3: Run it — verify it FAILS**

Run: `./gradlew test --tests "art.arcane.profiles.engine.ProfileSwitchEngineTest.opensAndClosesOneAtATime"`
Expected: FAIL — `maxConcurrentOpens` is > 1 (the current `applyPass` opens up to 32 targets via `async{…}.awaitAll()`). This proves the test detects the bug.

- [ ] **Step 4: Rewrite `applyPass` to be serial**

In `ProfileSwitchEngine.kt`, replace the entire `applyPass` method (lines 224-282) with:

```kotlin
    private suspend fun applyPass(
        request: SwitchRequest,
        profileName: String,
        targetByKey: Map<String, Path>,
        primaryKey: String,
        primary: ProjectWindowHandle,
    ) {
        // Re-derive from fresh ground truth (Phase A may have changed the open set).
        val openByKey = dependencies.windows.openByKey()
        val plan = ReconcilePlanner.plan(targetByKey.keys.toList(), openByKey.keys)
        val targetKeys = targetByKey.keys.toList()

        // PHASE B — close extras ONE AT A TIME. The platform never tears down frames in parallel;
        // neither do we. Per-close timeout so a hung close can't wedge the reconciler.
        val extras = (plan.toCloseKeys.mapNotNull { openByKey[it] } +
            dependencies.windows.liveProjects().filter { dependencies.windows.keyOf(it) == null }).distinct()
        val extrasToClose = extras.filter { project ->
            if (project == primary || project.isDisposed) return@filter false
            val key = dependencies.windows.keyOf(project)
            key != primaryKey && (key == null || key !in targetByKey)
        }
        for (project in extrasToClose) {
            if (!isCurrent(request)) return
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { dependencies.windows.closeProject(project) }
                ?: log.warn("Profiles: close timed out for ${project.name}; continuing")
            publishSwitchingSnapshot(request, profileName, targetKeys)
        }

        // PHASE C — open remaining targets ONE AT A TIME. The gateway awaits each open's frame settle
        // before returning, so frames/tabs are constructed sequentially (fixes the meshed-toolbar bug).
        val targetKeysToOpen = plan.toOpenKeys.filter { it != primaryKey }
        for (key in targetKeysToOpen) {
            if (!isCurrent(request)) return
            val freshOpen = dependencies.windows.openByKey()
            if (key in freshOpen) continue
            val path = targetByKey[key] ?: continue
            dependencies.windows.openInOwnFrameAsync(path)
            publishSwitchingSnapshot(request, profileName, targetKeys)
        }

        // PHASE D — settle focus on the primary once.
        if (!primary.isDisposed) dependencies.windows.focus(primary)
        publishSwitchingSnapshot(request, profileName, targetKeys)
    }
```

- [ ] **Step 5: Remove the now-unused concurrency imports and batch constants**

In `ProfileSwitchEngine.kt`:
- Delete the imports `import kotlinx.coroutines.async`, `import kotlinx.coroutines.awaitAll`, and `import kotlinx.coroutines.coroutineScope` (lines 12, 13, 17) — they are no longer referenced.
- In the `companion object` (lines 343-351), delete `private const val CLOSE_BATCH_SIZE = 32` and `private const val OPEN_BATCH_SIZE = 32`. Keep `MAX_PASSES` and `CLOSE_TIMEOUT_MS`.

- [ ] **Step 6: Remove `markActivity` / `ActivityTracker` from the engine**

In `ProfileSwitchEngine.kt`:
- Delete `import com.intellij.ide.ActivityTracker` (line 6).
- In `ProfileSwitchDependencies`, delete the `val markActivity: () -> Unit,` field (line 42) and its initializer line in `ide()` (`markActivity = { ActivityTracker.getInstance().inc() },`, line 60).
- In `publishStatus` (lines 321-324), remove the `dependencies.markActivity()` call so it reads:

```kotlin
    private fun publishStatus(status: SwitchStatus) {
        _status.value = status
    }
```

- [ ] **Step 7: Update the test helper to drop `markActivity`**

In `ProfileSwitchEngineTest.kt`, the `engine(...)` helper builds `ProfileSwitchDependencies(...)`. Remove the `markActivity = {}` argument from that constructor call so it matches the new signature.

- [ ] **Step 8: Run the serialization test — verify it PASSES**

Run: `./gradlew test --tests "art.arcane.profiles.engine.ProfileSwitchEngineTest.opensAndClosesOneAtATime"`
Expected: PASS.

- [ ] **Step 9: Run the FULL engine test class — verify no regressions**

Run: `./gradlew test --tests "art.arcane.profiles.engine.*"`
Expected: PASS (0 failures). Existing coalescing / empty-profile / convergence tests must still pass with the serial engine.

- [ ] **Step 10: Checkpoint (DO NOT COMMIT)**

Report: engine opens/closes serially (verified by `maxConcurrentOpens/Closes == 1`), primary-first preserved, `markActivity` removed. Await review.

---

## Task 3: Pure toolbar-label helpers (emoji sanitize + width cap)

**Files:**
- Create: `src/test/kotlin/art/arcane/profiles/ui/ProfilePresentationTest.kt`
- Modify: `src/main/kotlin/art/arcane/profiles/ui/ProfilePresentation.kt`

**Interfaces:**
- Consumes: existing `ProfilePresentation.emoji(profile: Profile): String?`, `ProfilePresentation.icon(profile: Profile): Icon`.
- Produces: `ProfilePresentation.toolbarLabel(profile: Profile, maxChars: Int = TOOLBAR_MAX_LABEL_CHARS): String`, `ProfilePresentation.toolbarIcon(profile: Profile): Icon?`, and `const val ProfilePresentation.TOOLBAR_MAX_LABEL_CHARS`. Consumed by `ProfileWidgetButton` in Task 5.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/art/arcane/profiles/ui/ProfilePresentationTest.kt`:

```kotlin
package art.arcane.profiles.ui

import art.arcane.profiles.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePresentationTest {

    private fun profile(name: String, icon: String? = null) =
        Profile(name = name, color = null, icon = icon, projectPaths = emptyList())

    @Test
    fun shortNameIsReturnedUnchanged() {
        assertEquals("Work", ProfilePresentation.toolbarLabel(profile("Work")))
    }

    @Test
    fun longNameIsEllipsizedToMaxChars() {
        val label = ProfilePresentation.toolbarLabel(profile("A really really long profile name"), maxChars = 10)
        assertTrue("label='$label'", label.length <= 10)
        assertTrue("label='$label'", label.endsWith("…"))
    }

    @Test
    fun emojiProfileGetsSingleGraphemePrefix() {
        // A ZWJ family emoji is several code points but ONE grapheme; only that grapheme should prefix.
        val label = ProfilePresentation.toolbarLabel(profile("Home", icon = "👨‍👩‍👧 extra"))
        assertTrue("label='$label'", label.endsWith("Home"))
        assertTrue("label='$label'", label.startsWith("👨‍👩‍👧 "))
    }

    @Test
    fun builtInIconKeyAddsNoEmojiPrefix() {
        // "star" is a built-in ProfileIcon key, so emoji() returns null -> no prefix.
        assertEquals("Fav", ProfilePresentation.toolbarLabel(profile("Fav", icon = "star")))
    }

    @Test
    fun toolbarIconIsNullForEmojiProfile() {
        assertNull(ProfilePresentation.toolbarIcon(profile("Home", icon = "🏠")))
    }
}
```

- [ ] **Step 2: Run the tests — verify they FAIL**

Run: `./gradlew test --tests "art.arcane.profiles.ui.ProfilePresentationTest"`
Expected: FAIL — `toolbarLabel` / `toolbarIcon` / `TOOLBAR_MAX_LABEL_CHARS` are unresolved.

- [ ] **Step 3: Implement the helpers**

In `ProfilePresentation.kt`, add these to the `object ProfilePresentation` (put `TOOLBAR_MAX_LABEL_CHARS` near the top of the object, the functions below the existing members):

```kotlin
    /** Max characters shown in the main-toolbar combo label before ellipsis (keeps the widget narrow). */
    const val TOOLBAR_MAX_LABEL_CHARS: Int = 22

    /**
     * The main-toolbar text for [profile]: an optional single-grapheme emoji prefix (never the full,
     * possibly-long user string) plus the profile name, the whole thing ellipsized to [maxChars] so a
     * long name or a multi-glyph emoji can never blow out the toolbar width and crowd the neighbouring
     * Project/Branch widgets. Built-in icons contribute no prefix (they are shown via [toolbarIcon]).
     */
    fun toolbarLabel(profile: Profile, maxChars: Int = TOOLBAR_MAX_LABEL_CHARS): String {
        val prefix = firstGrapheme(emoji(profile))?.let { "$it " } ?: ""
        val name = profile.name.trim()
        val budget = (maxChars - prefix.length).coerceAtLeast(1)
        val shownName =
            if (name.length <= budget) name
            else name.take((budget - 1).coerceAtLeast(1)).trimEnd() + "…"
        return prefix + shownName
    }

    /** The toolbar icon: none when an emoji is shown in the label, otherwise the profile's [icon]. */
    fun toolbarIcon(profile: Profile): Icon? =
        if (emoji(profile) != null) null else icon(profile)

    /** The first user-perceived character (grapheme cluster) of [s], or null if blank. */
    fun firstGrapheme(s: String?): String? {
        val text = s?.trim().orEmpty()
        if (text.isEmpty()) return null
        val breaker = java.text.BreakIterator.getCharacterInstance()
        breaker.setText(text)
        val end = breaker.next()
        return if (end == java.text.BreakIterator.DONE) text else text.substring(0, end)
    }
```

If `Icon` is not already imported in this file, add `import javax.swing.Icon`.

- [ ] **Step 4: Run the tests — verify they PASS**

Run: `./gradlew test --tests "art.arcane.profiles.ui.ProfilePresentationTest"`
Expected: PASS.

- [ ] **Step 5: Checkpoint (DO NOT COMMIT)**

Report: pure width/emoji helpers added and unit-tested. Await review.

---

## Task 4: Centralize the dropdown in `ProfilePopups.createMainDropdown`

**Files:**
- Modify: `src/main/kotlin/art/arcane/profiles/ui/ProfilePopups.kt`

**Interfaces:**
- Consumes: existing `ProfilePopups.profileSwitchActions()`, `DisabledHint`, and the action classes `SaveCurrentAsProfileAction`, `UpdateActiveProfileFromWindowsAction`, `NewProfileAction`, `ImportProfilesFromFolderAction`.
- Produces: `ProfilePopups.createMainDropdown(dataContext: com.intellij.openapi.actionSystem.DataContext): com.intellij.openapi.ui.popup.JBPopup`. Consumed by `ProfileWidgetButton` in Task 5.

This moves the dropdown-building + version footer + "Manage Profiles" out of the old toolbar action so the new widget can reuse it. No behavior change to the popup contents.

- [ ] **Step 1: Add `createMainDropdown`, `pluginVersion`, and `ManageProfilesAction` to `ProfilePopups`**

Add these to `object ProfilePopups` (and the imports they need at the top of the file):

```kotlin
    /** The full main-toolbar dropdown: switch rows (or a hint) + create/capture/import/manage + version. */
    fun createMainDropdown(dataContext: DataContext): JBPopup {
        val group = DefaultActionGroup()
        val switchActions = profileSwitchActions()
        if (switchActions.isEmpty()) {
            group.add(DisabledHint("No profiles yet — save your open windows below"))
        } else {
            switchActions.forEach(group::add)
        }
        group.addSeparator()
        group.add(SaveCurrentAsProfileAction())
        group.add(UpdateActiveProfileFromWindowsAction())
        group.add(NewProfileAction())
        group.add(ImportProfilesFromFolderAction())
        group.add(ManageProfilesAction())
        group.addSeparator()
        group.add(DisabledHint("Profiles v${pluginVersion()}"))
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Profiles",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    // Read our own version from a build-filtered resource rather than a plugin-descriptor lookup
    // (those APIs are @Internal on newer platforms); see processResources in build.gradle.kts.
    private fun pluginVersion(): String =
        runCatching {
            javaClass.getResourceAsStream("/profiles-build.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"

    private class ManageProfilesAction : AnAction(
        "Manage Profiles…",
        "Open profile settings",
        AllIcons.General.Settings,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ProfilesConfigurable::class.java)
        }
    }
```

Add any imports not already present in `ProfilePopups.kt`:

```kotlin
import art.arcane.profiles.actions.ImportProfilesFromFolderAction
import art.arcane.profiles.actions.NewProfileAction
import art.arcane.profiles.actions.SaveCurrentAsProfileAction
import art.arcane.profiles.actions.UpdateActiveProfileFromWindowsAction
import art.arcane.profiles.ui.ProfilesConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
```

(`ProfilesConfigurable` is in the same `ui` package, so the import may be unnecessary — drop it if the compiler flags it as redundant.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Checkpoint (DO NOT COMMIT)**

Report: dropdown building centralized in `ProfilePopups.createMainDropdown`. Await review.

---

## Task 5: Rewrite the toolbar widget on public `CustomComponentAction`

**Files:**
- Create: `src/main/kotlin/art/arcane/profiles/toolbar/ProfileWidgetButton.kt`
- Modify (rewrite): `src/main/kotlin/art/arcane/profiles/toolbar/ProfileToolbarWidgetAction.kt`

**Interfaces:**
- Consumes: `ProfilePopups.createMainDropdown(dataContext)`, `ProfilePresentation.toolbarLabel/toolbarIcon`, `ProfileSwitchEngine.getInstance().status: StateFlow<SwitchStatus>`, `ProfilesService.getInstance()`, `SwitchStatus.{Idle,Switching,Failed}`.
- Produces: a toolbar widget rendered from a public base class, live-updating during a switch, width-capped.

This removes the impl-package `ExpandableComboAction` base. The widget stays registered by FQN in `plugin.xml` — no descriptor change.

- [ ] **Step 1: Create the custom component**

Create `src/main/kotlin/art/arcane/profiles/toolbar/ProfileWidgetButton.kt`:

```kotlin
package art.arcane.profiles.toolbar

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.engine.ProfileSwitchEngine
import art.arcane.profiles.engine.SwitchStatus
import art.arcane.profiles.ui.ProfilePopups
import art.arcane.profiles.ui.ProfilePresentation
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.application.EDT
import com.intellij.util.ui.AnimatedIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.Dimension
import javax.swing.JButton

/**
 * The Profiles toolbar widget's live component. Renders the active profile (or switch progress) from
 * [ProfileSwitchEngine.status], to which it subscribes for its lifetime in the toolbar so the label
 * never sticks on a stale "Switching..." state. Width is capped so a long name / emoji can't crowd the
 * neighbouring Project and Branch widgets. Clicking opens the shared main dropdown.
 */
internal class ProfileWidgetButton : JButton() {

    private var scope: CoroutineScope? = null

    init {
        isFocusable = false
        putClientProperty("styleTag", "toolbar")
        horizontalAlignment = LEFT
        addActionListener { showDropdown() }
        renderCurrent()
    }

    override fun addNotify() {
        super.addNotify()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        scope = newScope
        newScope.launch {
            ProfileSwitchEngine.getInstance().status.collect { renderCurrent() }
        }
    }

    override fun removeNotify() {
        scope?.cancel()
        scope = null
        super.removeNotify()
    }

    override fun getPreferredSize(): Dimension = cap(super.getPreferredSize())

    override fun getMaximumSize(): Dimension = cap(super.getMaximumSize())

    private fun cap(d: Dimension): Dimension = Dimension(minOf(d.width, MAX_WIDTH_PX), d.height)

    fun renderCurrent() {
        val service = ProfilesService.getInstance()
        when (val status = ProfileSwitchEngine.getInstance().status.value) {
            is SwitchStatus.Switching -> {
                val target = service.findEntry(status.targetProfileName)?.toModel()
                text = target?.let { ProfilePresentation.toolbarLabel(it) }
                    ?: ProfilePresentation.firstGrapheme(status.targetProfileName).orEmpty()
                        .let { status.targetProfileName.take(ProfilePresentation.TOOLBAR_MAX_LABEL_CHARS) }
                icon = AnimatedIcon.Default.INSTANCE
                toolTipText = "Switching to ${status.targetProfileName}: " +
                    "${status.openedCount}/${status.targetCount} open, ${status.closingCount} closing"
            }
            is SwitchStatus.Failed -> {
                val active = service.activeProfileName?.let { service.findEntry(it)?.toModel() }
                text = active?.let { ProfilePresentation.toolbarLabel(it) } ?: "Profiles"
                icon = active?.let { ProfilePresentation.toolbarIcon(it) } ?: AllIcons.General.User
                toolTipText = "Last switch failed: ${status.missingCount} not opened, " +
                    "${status.extraCount} not closed"
            }
            is SwitchStatus.Idle -> {
                val active = status.activeProfileName?.let { service.findEntry(it)?.toModel() }
                text = active?.let { ProfilePresentation.toolbarLabel(it) } ?: "Profiles"
                icon = active?.let { ProfilePresentation.toolbarIcon(it) } ?: AllIcons.General.User
                toolTipText = if (active != null) "Active profile: ${active.name}"
                else "Switch project profiles"
            }
        }
        revalidate()
        repaint()
    }

    private fun showDropdown() {
        val dataContext = DataManager.getInstance().getDataContext(this)
        ProfilePopups.createMainDropdown(dataContext).showUnderneathOf(this)
    }

    companion object {
        private const val MAX_WIDTH_PX = 220
    }
}
```

- [ ] **Step 2: Rewrite the action to a `CustomComponentAction`**

Replace the ENTIRE contents of `ProfileToolbarWidgetAction.kt` with:

```kotlin
package art.arcane.profiles.toolbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import javax.swing.JComponent

/**
 * The Profiles dropdown in the new-UI main toolbar, beside the Project and Branch widgets. Built on the
 * public [CustomComponentAction] (not the impl-package ExpandableComboAction) so the plugin stays off
 * unstable toolbar internals. All rendering and the live switch-state subscription live in
 * [ProfileWidgetButton]; this action only supplies that component and toggles visibility with the
 * project context.
 */
class ProfileToolbarWidgetAction : AnAction(), CustomComponentAction, DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    // The component handles clicks; nothing to do when triggered via Find Action.
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        ProfileWidgetButton()

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as? ProfileWidgetButton)?.let {
            it.isVisible = presentation.isVisible
            it.renderCurrent()
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. If `AnimatedIcon` fails to resolve, confirm the import `com.intellij.util.ui.AnimatedIcon`. If `flow.collect` is flagged, replace `import kotlinx.coroutines.flow.collect` with a `.collect { }` call on the `StateFlow` (it is a member of `Flow`; the explicit import may be unnecessary — remove it if redundant).

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew test`
Expected: PASS (0 failures) — the engine and presentation tests all green.

- [ ] **Step 5: Checkpoint (DO NOT COMMIT)**

Report: toolbar widget rebuilt on public `CustomComponentAction` with a live status subscription and width cap; `ExpandableComboAction` no longer referenced anywhere. Await review.

---

## Task 6: Cleanup, changelog, build, verifier, and manual verification

**Files:**
- Modify: `src/main/kotlin/art/arcane/profiles/ui/ProfilePresentation.kt` (only if `label()` is now unused)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Remove orphaned code this work created**

Run: `grep -rn "ProfilePresentation.label(" src/` and `grep -rn "\.label(" src/main/kotlin/art/arcane/profiles/`
- If `ProfilePresentation.label(` has no remaining callers (the old toolbar was its only user), delete the `label` function from `ProfilePresentation.kt` (no dead code per project rule).
- If it still has callers, leave it.

Also run: `grep -rn "ExpandableComboAction\|focusProjectWindow\|OPEN_BATCH_SIZE\|CLOSE_BATCH_SIZE\|markActivity" src/main/kotlin`
Expected: ZERO matches in `src/main/kotlin` except the two isolated internal calls in `ProjectWindows.kt` are NOT in this list. If any match remains, remove it.

- [ ] **Step 2: Append changelog entries**

In `CHANGELOG.md`, under the existing `## x.x.x` section, add:

```markdown
### Fixed
- Switching a profile no longer garbles the main toolbar / project tabs when the profile has many
  projects. The switch engine now opens and closes projects one at a time (awaiting each window's frame
  settle before the next), matching how the platform itself reopens multiple projects, instead of firing
  up to 32 concurrent opens/closes that raced frame and tab construction.
- The toolbar widget no longer sticks on "Switching..." after a switch completes, and a long profile
  name or emoji can no longer blow out the widget width and crowd the Project/Branch widgets.

### Changed
- Rewrote the profile-switch engine around a strictly serial reconcile pass.
- Rebuilt the main-toolbar widget on the public `CustomComponentAction` (was the impl-package
  `ExpandableComboAction`), with a live subscription to switch state and a capped width. Window focus now
  uses the public `WindowManager` instead of the internal `ProjectUtil.focusProjectWindow`. The only
  remaining internal-API calls are the veto-free close and the forced-new-frame open (no public
  equivalent on 253+), isolated in `engine/ProjectWindows.kt` and each guarded by a public fallback.
```

- [ ] **Step 3: Full build + tests**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 4: Plugin verifier — record remaining internal usage**

Run: `./gradlew verifyPlugin`
Expected: build succeeds under the configured `failureLevel` (COMPATIBILITY_PROBLEMS, INVALID_PLUGIN). Read the report and confirm the ONLY internal-API items reported are `forceCloseProjectAsync`, `openProjectAsync(OpenProjectTask)`, and `OpenProjectTask`/`ProjectUtil.openOrImportAsync` (all in `ProjectWindows.kt`). `focusProjectWindow` and `ExpandableComboAction` must NO LONGER appear.

- [ ] **Step 5: Manual verification — the actual bug (MANDATORY)**

Run: `./gradlew runIde`
In the sandbox IDE:
1. Create a profile with 6+ real project folders (Profiles dropdown - "Create Profiles from Folder..." or add folders).
2. Create a second profile with a different 6+ folders.
3. Switch between them via the toolbar dropdown several times.
4. Confirm: each project opens in its own window, the main toolbar renders correctly (NO overlapping/garbled toolbar or tabs), the widget label tracks the switch and settles on the active profile name, and the final open window set exactly matches the profile.
5. Set a profile's icon to an emoji and to a long name; confirm the toolbar widget stays narrow and does not crowd the Project/Branch widgets.

Report exactly what you observed (this is the acceptance criterion — "tests pass" is not sufficient for a UI fix).

- [ ] **Step 6: Cross-version smoke (MANDATORY)**

The repo has local platform artifacts for 253 and 261. Verify the plugin loads and a switch works on the upper bound too:
Run: `./gradlew runIde -PideVersion=2026.1.3` (or the project's configured mechanism for selecting the 261 sandbox; if only one `runIde` is configured, note that and run the default).
Confirm a 6+ project switch renders cleanly. Report results per version.

- [ ] **Step 7: Final checkpoint (DO NOT COMMIT)**

Report: build green, verifier shows only the two documented internal calls, manual switch is clean on 253 (and 261 if runnable), toolbar widget behaves. Summarize for the user; the user commits.

---

## Self-Review

**Spec coverage:**
- Serial open/close with settle → Tasks 1, 2. ✓
- Full engine rewrite (serial model, keep planner/resolver/level-triggered/DI seam) → Task 2. ✓
- Toolbar rewrite on public API + live state + emoji/width + Failed render → Tasks 3, 5. ✓
- Internal-API pragmatic reduction (focus→WindowManager, toolbar→CustomComponentAction, isolate open/close with fallbacks) → Tasks 1, 5; verified Task 6 Step 4. ✓
- Version range 253+ unchanged → Global Constraints; verified Tasks 1/6. ✓
- Testing: serialization-order test, pure label tests, manual runIde on 253/261 → Tasks 2, 3, 6. ✓
- No git commits; changelog to x.x.x → Global Constraints, Task 6. ✓

**Placeholder scan:** No "TBD"/"handle edge cases"/"similar to". Every code step shows full code. The one deferred detail from the spec (exact settle hook) is concretely implemented as bounded `runAfterOpened` + EDT flush in Task 1 Step 2. ✓

**Type consistency:** `toolbarLabel(profile, maxChars)`, `toolbarIcon(profile)`, `firstGrapheme(s)`, `TOOLBAR_MAX_LABEL_CHARS`, `createMainDropdown(dataContext)`, `renderCurrent()`, `ProfileWidgetButton` used consistently across Tasks 3, 4, 5. `ProfileSwitchDependencies` losing `markActivity` is applied in both the main source (Task 2 Step 6) and the test helper (Task 2 Step 7). ✓
