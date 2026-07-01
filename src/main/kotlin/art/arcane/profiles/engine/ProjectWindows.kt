package art.arcane.profiles.engine

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Every platform window/project primitive the engine needs, in one place. Identity is canonical
 * real-path based (see [canonicalKey]); opening is dedup-then-open so an already-open project is
 * focused rather than duplicated; closing is veto-free force-close. This is the only file that
 * touches non-public project APIs.
 */
internal interface ProjectWindowHandle {
    val name: String
    val isDisposed: Boolean
}

internal interface ProjectWindowGateway {
    fun liveProjects(): List<ProjectWindowHandle>
    fun keyOf(project: ProjectWindowHandle): String?
    fun openByKey(): Map<String, ProjectWindowHandle>
    suspend fun openInOwnFrameAsync(path: Path): ProjectWindowHandle?
    suspend fun closeProject(project: ProjectWindowHandle): Boolean
    suspend fun focus(project: ProjectWindowHandle)
    suspend fun withSwitchProgress(project: ProjectWindowHandle, title: String, action: suspend () -> Unit)
}

internal object ProjectWindows : ProjectWindowGateway {

    private val LOG = Logger.getInstance(ProjectWindows::class.java)

    private data class IdeProjectHandle(val project: Project) : ProjectWindowHandle {
        override val name: String get() = project.name
        override val isDisposed: Boolean get() = project.isDisposed
    }

    private fun rawLiveProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }

    /** Currently open, real (non-default, non-disposed) projects — the reconciler's ground truth. */
    override fun liveProjects(): List<ProjectWindowHandle> = rawLiveProjects().map(::IdeProjectHandle)

    /** True when [project]'s folder is the same real directory as [target] (symlink/case/`/private`-safe). */
    private fun matches(target: Path, project: Project): Boolean {
        val base = project.basePath ?: return false
        return runCatching { canonicalKey(Path.of(base)) == canonicalKey(target) }.getOrDefault(false)
    }

    /** Canonical key of an open [project] (its folder real-path), or null if it has no base dir. */
    private fun keyOfProject(project: Project): String? =
        project.basePath?.let { runCatching { canonicalKey(Path.of(it)) }.getOrNull() }

    override fun keyOf(project: ProjectWindowHandle): String? =
        (project as? IdeProjectHandle)?.project?.let(::keyOfProject)

    /** Live projects indexed by canonical key (first wins). Projects with no base dir are excluded. */
    override fun openByKey(): Map<String, ProjectWindowHandle> {
        val map = LinkedHashMap<String, ProjectWindowHandle>()
        for (project in rawLiveProjects()) {
            val key = keyOfProject(project) ?: continue
            map.putIfAbsent(key, IdeProjectHandle(project))
        }
        return map
    }

    /**
     * Open [path] in its OWN window. If a project for that folder is already open, focus it and
     * return it instead of opening a duplicate. Returns null if the open failed.
     */
    override suspend fun openInOwnFrameAsync(path: Path): ProjectWindowHandle? {
        rawLiveProjects().firstOrNull { matches(path, it) }?.let {
            val handle = IdeProjectHandle(it)
            focus(handle)
            return handle
        }
        return try {
            val task = OpenProjectTask.build().withForceOpenInNewFrame(true)
            val direct = try {
                ProjectManagerEx.getInstanceEx().openProjectAsync(path, task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.debug("Profiles: direct project open failed for $path; falling back to open/import", e)
                null
            }
            if (direct != null) return IdeProjectHandle(direct)
            ProjectUtil.openOrImportAsync(path, task)?.let(::IdeProjectHandle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Profiles: open failed for $path", e)
            null
        }
    }

    /** Force-close [project]: no veto, no save/confirm dialog; awaits dispose. Returns success. */
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
        // Escalate once: close without saving rather than leave a leftover window.
        return attempt(false) || ideProject.isDisposed
    }

    override suspend fun focus(project: ProjectWindowHandle) {
        val ideProject = (project as? IdeProjectHandle)?.project ?: return
        if (ideProject.isDisposed) return
        withContext(Dispatchers.EDT) {
            try {
                ProjectUtil.focusProjectWindow(ideProject, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("Profiles: focus failed for ${ideProject.name}", e)
            }
        }
    }

    override suspend fun withSwitchProgress(
        project: ProjectWindowHandle,
        title: String,
        action: suspend () -> Unit,
    ) {
        val ideProject = (project as? IdeProjectHandle)?.project
        if (ideProject == null || ideProject.isDisposed) {
            action()
        } else {
            withBackgroundProgress(ideProject, title, false) {
                action()
            }
        }
    }
}
