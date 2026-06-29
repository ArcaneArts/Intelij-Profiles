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
 *
 * Switching to an EMPTY profile (no projects, or none that still exist) leaves the open windows
 * unchanged and notifies — it never closes everything to nothing.
 */
@Service(Service.Level.APP)
class ProfileSwitchEngine(private val scope: CoroutineScope) {

    private val log = Logger.getInstance(ProfileSwitchEngine::class.java)

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

        val targets = resolved.targets
        if (targets.isEmpty()) {
            // An empty profile (or one whose paths are all gone) must NOT close your open windows —
            // switching to "nothing" and emptying the IDE is never useful. Leave the windows as they
            // are and explain.
            val why = if (profile.projectPaths.isEmpty()) {
                "has no projects"
            } else {
                "has no projects that still exist on disk"
            }
            notify(
                "Profile \"${profile.name}\" $why — nothing to switch to. Your open projects were left " +
                    "unchanged; add projects via the Profiles dropdown → Manage Profiles.",
                NotificationType.WARNING,
            )
            return
        }

        if (resolved.missing.isNotEmpty()) notifyMissing(profile.name, resolved.missing)

        // Commit the active-profile label only now that we have a real target set to apply.
        setActive(profile.name)
        reconcilePasses(profile.name, targets)
    }

    private suspend fun reconcilePasses(profileName: String, targets: List<Path>) {
        // Map each target to its canonical key once (first spelling wins), preserving order.
        val targetByKey = LinkedHashMap<String, Path>()
        for (target in targets) targetByKey.putIfAbsent(canonicalKey(target), target)
        val targetKeys = targetByKey.keys.toList()
        val primaryKey = targetKeys.first()
        val primaryPath = targetByKey.getValue(primaryKey)

        repeat(MAX_PASSES) {
            val openByKey = ProjectWindows.openByKey()
            val plan = ReconcilePlanner.plan(targetKeys, openByKey.keys)
            val nullKeyExtras = ProjectWindows.liveProjects().filter { ProjectWindows.keyOf(it) == null }

            if (plan.converged && nullKeyExtras.isEmpty()) {
                openByKey[primaryKey]?.let { ProjectWindows.focus(it) }
                return // converged
            }

            // PHASE A — open/focus the primary FIRST. If it can't open, leave every window untouched
            // this pass (never close anything without the new primary live) and retry next pass.
            val primary = openByKey[primaryKey] ?: ProjectWindows.openInOwnFrame(primaryPath)
            if (primary == null || primary.isDisposed) {
                log.warn("Profiles: could not open primary $primaryPath; leaving windows unchanged this pass")
                return@repeat
            }
            ProjectWindows.focus(primary)

            // PHASES B-D under a progress indicator hosted on the PRIMARY (a surviving target), so the
            // progress can never abort its own switch by hosting on a project we're about to close.
            withBackgroundProgress(primary, "Switching to $profileName", false) {
                applyPass(targetByKey, primaryKey, primary)
            }
        }

        // Surface a non-converged end state instead of failing silently.
        val openByKey = ProjectWindows.openByKey()
        val stillMissing = targetKeys.filter { it !in openByKey }
        val stillExtra = openByKey.keys.filter { it !in targetByKey } +
            ProjectWindows.liveProjects().filter { ProjectWindows.keyOf(it) == null }.map { it.name }
        if (stillMissing.isNotEmpty() || stillExtra.isNotEmpty()) {
            log.warn("Profiles: '$profileName' not converged in $MAX_PASSES passes; missing=$stillMissing extras=$stillExtra")
            notify(
                "Profile \"$profileName\" did not fully apply: ${stillMissing.size} not opened, ${stillExtra.size} not closed.",
                NotificationType.WARNING,
            )
        }
    }

    private suspend fun applyPass(targetByKey: Map<String, Path>, primaryKey: String, primary: Project) {
        // Re-derive from fresh ground truth (Phase A may have changed the open set).
        val openByKey = ProjectWindows.openByKey()
        val plan = ReconcilePlanner.plan(targetByKey.keys.toList(), openByKey.keys)

        // PHASE B — close extras: keyed extras plus any project with no identity, but NEVER the
        // primary (identity guard — the hard stop against "switch closes everything"). Interruptible
        // + per-close timeout so a hung close can't wedge the reconciler; the next pass retries.
        val extras = (plan.toCloseKeys.mapNotNull { openByKey[it] } +
            ProjectWindows.liveProjects().filter { ProjectWindows.keyOf(it) == null }).distinct()
        for (project in extras) {
            if (project === primary || project.isDisposed) continue
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { ProjectWindows.closeProject(project) }
                ?: log.warn("Profiles: close timed out for ${project.name}; continuing")
        }

        // PHASE C — open the remaining targets, re-checking ground truth (a prior await may have opened one).
        for (key in plan.toOpenKeys) {
            if (key == primaryKey) continue
            if (ProjectWindows.openByKey().containsKey(key)) continue
            targetByKey[key]?.let { ProjectWindows.openInOwnFrame(it) }
        }

        // PHASE D — settle focus on the primary once.
        if (!primary.isDisposed) ProjectWindows.focus(primary)
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
