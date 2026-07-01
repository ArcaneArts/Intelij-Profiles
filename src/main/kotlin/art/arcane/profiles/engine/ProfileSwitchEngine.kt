package art.arcane.profiles.engine

import art.arcane.profiles.Notifications
import art.arcane.profiles.ProfilesService
import art.arcane.profiles.model.Profile
import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

internal interface ProfileSwitchStore {
    var activeProfileName: String?
    fun hasProfile(name: String): Boolean
}

internal data class ProfileSwitchDependencies(
    val windows: ProjectWindowGateway,
    val store: ProfileSwitchStore,
    val notify: (String, NotificationType) -> Unit,
    val isApplicationDisposed: () -> Boolean,
    val pathExists: (Path) -> Boolean,
    val markActivity: () -> Unit,
) {
    companion object {
        fun ide(): ProfileSwitchDependencies =
            ProfileSwitchDependencies(
                windows = ProjectWindows,
                store = object : ProfileSwitchStore {
                    override var activeProfileName: String?
                        get() = ProfilesService.getInstance().activeProfileName
                        set(value) {
                            ProfilesService.getInstance().activeProfileName = value
                        }

                    override fun hasProfile(name: String): Boolean = ProfilesService.getInstance().hasProfile(name)
                },
                notify = Notifications::show,
                isApplicationDisposed = { ApplicationManager.getApplication().isDisposed },
                pathExists = { Files.isDirectory(it) },
                markActivity = { ActivityTracker.getInstance().inc() },
            )
    }
}

/**
 * Declarative, level-triggered profile-switch engine.
 *
 * A switch publishes a single desired profile; one long-lived consumer drives the live set of open
 * projects toward that profile's project set: it re-reads ground truth each pass, opens/focuses the
 * primary first, closes extras in batches, then opens remaining targets in batches. Rapid
 * switching coalesces to the last pick (StateFlow + collectLatest) and cancels the superseded pass.
 * The primary is opened before anything is closed, so the open-window count never hits zero (no IDE
 * quit / Welcome flash); if the primary can't open, nothing is closed.
 *
 * Switching to an EMPTY profile (no projects, or none that still exist) leaves the open windows
 * unchanged and notifies — it never closes everything to nothing. The active profile label is
 * committed only after the final reconcile confirms convergence.
 */
@Service(Service.Level.APP)
class ProfileSwitchEngine {

    private val log = Logger.getInstance(ProfileSwitchEngine::class.java)
    private val dependencies: ProfileSwitchDependencies

    private data class SwitchRequest(val profile: Profile, val gen: Long)

    private val desired = MutableStateFlow<SwitchRequest?>(null)
    private val _status: MutableStateFlow<SwitchStatus>
    val status: StateFlow<SwitchStatus>

    // Monotonic id so re-selecting the active profile still emits (defeats StateFlow conflation),
    // letting the user re-assert/repair a profile whose window they manually closed.
    private val gen = AtomicLong(0)

    constructor(scope: CoroutineScope) : this(scope, ProfileSwitchDependencies.ide())

    internal constructor(scope: CoroutineScope, dependencies: ProfileSwitchDependencies) {
        this.dependencies = dependencies
        _status = MutableStateFlow(SwitchStatus.Idle(dependencies.store.activeProfileName))
        status = _status.asStateFlow()
        scope.launch {
            desired.filterNotNull().collectLatest { request ->
                try {
                    reconcile(request)
                } catch (e: CancellationException) {
                    throw e // a newer switch superseded this one — let it cancel cleanly
                } catch (e: Throwable) {
                    log.warn("Profiles: reconcile failed for '${request.profile.name}'", e)
                    if (isCurrent(request)) publishStatus(SwitchStatus.Idle(dependencies.store.activeProfileName))
                }
            }
        }
    }

    /** Entry point (called on the EDT from the toolbar / actions). Non-blocking. */
    fun requestSwitch(profile: Profile) {
        val request = SwitchRequest(profile, gen.incrementAndGet())
        desired.value = request // last-wins; supersedes in-flight
        publishStatus(
            SwitchStatus.Switching(
                targetProfileName = profile.name,
                openedCount = 0,
                targetCount = profile.projectPaths.size,
                closingCount = 0,
            ),
        )
    }

    private suspend fun reconcile(request: SwitchRequest) {
        if (dependencies.isApplicationDisposed()) return
        val profile = request.profile

        val resolved = withContext(Dispatchers.IO) {
            TargetResolver.resolve(profile.projectPaths, dependencies.pathExists)
        }
        if (!isCurrent(request)) return

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
            if (isCurrent(request)) publishStatus(SwitchStatus.Idle(dependencies.store.activeProfileName))
            return
        }

        if (resolved.missing.isNotEmpty()) notifyMissing(profile.name, resolved.missing)

        val result = reconcilePasses(request, profile.name, targets)
        if (!isCurrent(request)) return
        if (result.converged) {
            commitActive(profile.name)
        } else {
            log.warn(
                "Profiles: '${profile.name}' not converged in $MAX_PASSES passes; " +
                    "missing=${result.missingKeys} extras=${result.extraNames}",
            )
            notify(
                "Profile \"${profile.name}\" did not fully apply: " +
                    "${result.missingKeys.size} not opened, ${result.extraNames.size} not closed.",
                NotificationType.WARNING,
            )
            publishFailureThenIdle(request, profile.name, result.missingKeys.size, result.extraNames.size)
        }
    }

    private suspend fun reconcilePasses(
        request: SwitchRequest,
        profileName: String,
        targets: List<Path>,
    ): ConvergenceResult {
        // Map each target to its canonical key once (first spelling wins), preserving order.
        val targetByKey = LinkedHashMap<String, Path>()
        for (target in targets) targetByKey.putIfAbsent(canonicalKey(target), target)
        val targetKeys = targetByKey.keys.toList()
        val primaryKey = targetKeys.first()
        val primaryPath = targetByKey.getValue(primaryKey)
        publishSwitchingSnapshot(request, profileName, targetKeys)

        repeat(MAX_PASSES) {
            val openByKey = dependencies.windows.openByKey()
            val plan = ReconcilePlanner.plan(targetKeys, openByKey.keys)
            val nullKeyExtras = dependencies.windows.liveProjects().filter { dependencies.windows.keyOf(it) == null }

            if (plan.converged && nullKeyExtras.isEmpty()) {
                openByKey[primaryKey]?.let { dependencies.windows.focus(it) }
                return ConvergenceResult(emptyList(), emptyList())
            }

            // PHASE A — open/focus the primary FIRST. If it can't open, leave every window untouched
            // this pass (never close anything without the new primary live) and retry next pass.
            val primary = openByKey[primaryKey] ?: dependencies.windows.openInOwnFrameAsync(primaryPath)
            if (primary == null || primary.isDisposed) {
                log.warn("Profiles: could not open primary $primaryPath; leaving windows unchanged this pass")
                return@repeat
            }
            dependencies.windows.focus(primary)
            publishSwitchingSnapshot(request, profileName, targetKeys)

            // PHASES B-D under a progress indicator hosted on the PRIMARY (a surviving target), so the
            // progress can never abort its own switch by hosting on a project we're about to close.
            dependencies.windows.withSwitchProgress(primary, "Switching to $profileName") {
                applyPass(request, profileName, targetByKey, primaryKey, primary)
            }
        }

        val result = convergenceResult(targetKeys, targetByKey)
        if (result.converged) {
            dependencies.windows.openByKey()[primaryKey]?.let { dependencies.windows.focus(it) }
        }
        return result
    }

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

        // PHASE B — close extras: keyed extras plus any project with no identity, but NEVER the
        // primary or any target. Interruptible + per-close timeout so a hung close can't wedge the
        // reconciler; the next pass retries.
        val extras = (plan.toCloseKeys.mapNotNull { openByKey[it] } +
            dependencies.windows.liveProjects().filter { dependencies.windows.keyOf(it) == null }).distinct()
        val extrasToClose = extras.filter { project ->
            if (project == primary || project.isDisposed) return@filter false
            val key = dependencies.windows.keyOf(project)
            key != primaryKey && (key == null || key !in targetByKey)
        }

        for (chunk in extrasToClose.chunked(CLOSE_BATCH_SIZE)) {
            if (!isCurrent(request)) return
            coroutineScope {
                chunk.map { project ->
                    async {
                        withTimeoutOrNull(CLOSE_TIMEOUT_MS) { dependencies.windows.closeProject(project) }
                            ?: log.warn("Profiles: close timed out for ${project.name}; continuing")
                    }
                }.awaitAll()
            }
            publishSwitchingSnapshot(request, profileName, targetKeys)
        }

        // PHASE C — open remaining targets after old extras have been asked to close. This makes
        // the visible transition feel like the new primary replaces the old profile instead of all
        // target windows piling in before the old profile disappears.
        val targetKeysToOpen = plan.toOpenKeys.filter { it != primaryKey }
        for (chunk in targetKeysToOpen.chunked(OPEN_BATCH_SIZE)) {
            if (!isCurrent(request)) return
            val freshOpen = dependencies.windows.openByKey()
            val paths = chunk.mapNotNull { key ->
                if (key in freshOpen) null else targetByKey[key]
            }
            if (paths.isEmpty()) continue
            coroutineScope {
                paths.map { path ->
                    async { dependencies.windows.openInOwnFrameAsync(path) }
                }.awaitAll()
            }
            publishSwitchingSnapshot(request, profileName, targetKeys)
        }

        // PHASE D — settle focus on the primary once.
        if (!primary.isDisposed) dependencies.windows.focus(primary)
        publishSwitchingSnapshot(request, profileName, targetKeys)
    }

    private fun convergenceResult(targetKeys: List<String>, targetByKey: Map<String, Path>): ConvergenceResult {
        val openByKey = dependencies.windows.openByKey()
        val stillMissing = targetKeys.filter { it !in openByKey }
        val stillExtra = openByKey.keys.filter { it !in targetByKey } +
            dependencies.windows.liveProjects().filter { dependencies.windows.keyOf(it) == null }.map { it.name }
        return ConvergenceResult(stillMissing, stillExtra)
    }

    private fun publishSwitchingSnapshot(request: SwitchRequest, profileName: String, targetKeys: List<String>) {
        if (!isCurrent(request)) return
        val openByKey = dependencies.windows.openByKey()
        val targetSet = targetKeys.toSet()
        val openedCount = targetKeys.count { it in openByKey }
        val closingCount = openByKey.keys.count { it !in targetSet } +
            dependencies.windows.liveProjects().count { dependencies.windows.keyOf(it) == null }
        publishStatus(SwitchStatus.Switching(profileName, openedCount, targetKeys.size, closingCount))
    }

    private fun commitActive(profileName: String) {
        if (dependencies.store.hasProfile(profileName)) {
            dependencies.store.activeProfileName = profileName
        }
        publishStatus(SwitchStatus.Idle(dependencies.store.activeProfileName))
    }

    private suspend fun publishFailureThenIdle(
        request: SwitchRequest,
        profileName: String,
        missingCount: Int,
        extraCount: Int,
    ) {
        if (!isCurrent(request)) return
        publishStatus(SwitchStatus.Failed(profileName, missingCount, extraCount))
        yield()
        if (isCurrent(request)) publishStatus(SwitchStatus.Idle(dependencies.store.activeProfileName))
    }

    private fun publishStatus(status: SwitchStatus) {
        _status.value = status
        dependencies.markActivity()
    }

    private fun isCurrent(request: SwitchRequest): Boolean {
        return desired.value?.gen == request.gen
    }

    private fun notifyMissing(profileName: String, missing: List<String>) {
        notify(
            "Profile \"$profileName\": ${missing.size} project(s) skipped (path missing):\n" + missing.joinToString("\n"),
            NotificationType.WARNING,
        )
    }

    private fun notify(content: String, type: NotificationType) = dependencies.notify(content, type)

    private data class ConvergenceResult(val missingKeys: List<String>, val extraNames: List<String>) {
        val converged: Boolean get() = missingKeys.isEmpty() && extraNames.isEmpty()
    }

    companion object {
        private const val MAX_PASSES = 3
        private const val CLOSE_TIMEOUT_MS = 30_000L
        private const val CLOSE_BATCH_SIZE = 8
        private const val OPEN_BATCH_SIZE = 4

        @JvmStatic
        fun getInstance(): ProfileSwitchEngine = service()
    }
}
