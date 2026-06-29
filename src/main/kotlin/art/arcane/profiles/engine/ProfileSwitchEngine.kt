package art.arcane.profiles.engine

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.model.Profile
import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Declarative, level-triggered profile-switch engine.
 *
 * A switch publishes a single desired profile; one long-lived consumer drives the live set of open
 * projects toward that profile's project set: it re-reads ground truth each pass, opens missing
 * targets (dedup-then-open, no duplicates), force-closes extras (no veto), focuses the primary, and
 * loops until converged. Rapid switching coalesces to the last pick (StateFlow + collectLatest) and
 * cancels the superseded pass. The primary is opened before anything is closed, so the open-window
 * count never hits zero (no IDE quit / Welcome flash); if the primary can't open, nothing is closed.
 */
@Service(Service.Level.APP)
class ProfileSwitchEngine(private val scope: CoroutineScope) {

    private val log = Logger.getInstance("art.arcane.profiles.engine.ProfileSwitchEngine")

    private data class SwitchRequest(val profile: Profile, val gen: Long)

    private val desired = MutableStateFlow<SwitchRequest?>(null)
    // Monotonic id so re-selecting the active profile still emits (defeats StateFlow conflation),
    // letting the user re-assert/repair a profile whose window they manually closed.
    private val gen = AtomicLong(0)

    init {
        scope.launch {
            desired.filterNotNull().collectLatest { request ->
                try {
                    reconcile(request.profile)
                } catch (e: CancellationException) {
                    throw e // a newer switch superseded this one — let it cancel cleanly
                } catch (e: Throwable) {
                    log.warn("Profiles: reconcile failed for '${request.profile.name}'", e)
                }
            }
        }
    }

    /** Entry point (called on the EDT from the toolbar / actions). Non-blocking. */
    fun requestSwitch(profile: Profile) {
        desired.value = SwitchRequest(profile, gen.getAndIncrement()) // last-wins; supersedes in-flight
    }

    private suspend fun reconcile(profile: Profile) {
        if (ApplicationManager.getApplication().isDisposed) return

        val resolved = withContext(Dispatchers.IO) {
            TargetResolver.resolve(profile.projectPaths) { Files.isDirectory(it) }
        }
        if (resolved.missing.isNotEmpty()) notifyMissing(profile.name, resolved.missing)

        val targets = resolved.targets
        if (targets.isEmpty()) {
            if (profile.projectPaths.isEmpty()) {
                // Genuinely empty profile → close everything (intentional Welcome screen).
                withContext(NonCancellable) { closeAll() }
                setActive(profile.name)
            } else {
                notify(
                    "Profile \"${profile.name}\": none of its project paths exist on disk; windows left unchanged.",
                    NotificationType.WARNING,
                )
            }
            return
        }

        // Commit the active-profile label only now that we have a real target set to apply.
        setActive(profile.name)
        reconcilePasses(profile.name, targets)
    }

    private suspend fun reconcilePasses(profileName: String, targets: List<Path>) {
        val primaryPath = targets.first()
        repeat(MAX_PASSES) {
            val open = ProjectWindows.liveProjects()
            val toClose = open.filter { p -> targets.none { ProjectWindows.matches(it, p) } }
            val secondary = targets.drop(1).filter { t -> open.none { ProjectWindows.matches(t, it) } }
            val primaryOpen = open.firstOrNull { ProjectWindows.matches(primaryPath, it) }

            if (primaryOpen != null && toClose.isEmpty() && secondary.isEmpty()) {
                ProjectWindows.focus(primaryOpen)
                return // converged
            }

            // PHASE A — open/focus the primary FIRST. If it can't open, leave every window untouched
            // this pass (never close anything without the new primary live) and retry next pass.
            val primary = primaryOpen ?: ProjectWindows.openInOwnFrame(primaryPath)
            if (primary == null || primary.isDisposed) {
                log.warn("Profiles: could not open primary $primaryPath; leaving windows unchanged this pass")
                return@repeat
            }
            ProjectWindows.focus(primary)

            // PHASES B-D under a progress indicator hosted on the PRIMARY (a surviving target), so the
            // progress can never abort its own switch by hosting on a project we're about to close.
            withBackgroundProgress(primary, "Switching to $profileName", false) {
                closeExtrasAndOpenRest(targets, primary)
            }
        }

        // Surface a non-converged end state instead of failing silently.
        val open = ProjectWindows.liveProjects()
        val stillMissing = targets.filter { t -> open.none { ProjectWindows.matches(t, it) } }
        val stillExtra = open.filter { p -> targets.none { ProjectWindows.matches(it, p) } }
        if (stillMissing.isNotEmpty() || stillExtra.isNotEmpty()) {
            log.warn("Profiles: '$profileName' not converged in $MAX_PASSES passes; missing=$stillMissing extras=${stillExtra.map { it.name }}")
            notify(
                "Profile \"$profileName\" did not fully apply: ${stillMissing.size} not opened, ${stillExtra.size} not closed.",
                NotificationType.WARNING,
            )
        }
    }

    private suspend fun closeExtrasAndOpenRest(targets: List<Path>, primary: Project) {
        // Re-derive from fresh ground truth (Phase A may have changed the open set).
        val open = ProjectWindows.liveProjects()
        val toClose = open.filter { p -> targets.none { ProjectWindows.matches(it, p) } }
        val secondary = targets.drop(1).filter { t -> open.none { ProjectWindows.matches(t, it) } }

        // PHASE B — close extras (RAM-light). Interruptible + per-close timeout so a hung close can't
        // wedge the reconciler; the next pass re-derives ground truth and retries any unfinished close.
        for (project in toClose) {
            if (project.isDisposed) continue
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { ProjectWindows.closeProject(project) }
                ?: log.warn("Profiles: close timed out for ${project.name}; continuing")
        }
        // PHASE C — open remaining targets, re-checking ground truth (a prior await may have opened one).
        for (target in secondary) {
            if (ProjectWindows.liveProjects().any { ProjectWindows.matches(target, it) }) continue
            ProjectWindows.openInOwnFrame(target)
        }
        // PHASE D — settle focus on the primary once.
        if (!primary.isDisposed) ProjectWindows.focus(primary)
    }

    private suspend fun closeAll() {
        for (project in ProjectWindows.liveProjects()) {
            ProjectWindows.closeProject(project)
        }
    }

    private fun setActive(profileName: String) {
        val service = ProfilesService.getInstance()
        if (service.hasProfile(profileName)) {
            service.activeProfileName = profileName
            ActivityTracker.getInstance().inc()
        }
    }

    private fun notifyMissing(profileName: String, missing: List<String>) {
        notify(
            "Profile \"$profileName\": ${missing.size} project(s) skipped (path missing):\n" + missing.joinToString("\n"),
            NotificationType.WARNING,
        )
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Profiles")
            .createNotification(content, type)
            .notify(null)
    }

    companion object {
        private const val MAX_PASSES = 3
        private const val CLOSE_TIMEOUT_MS = 30_000L

        @JvmStatic
        fun getInstance(): ProfileSwitchEngine = service()
    }
}
