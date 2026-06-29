package art.arcane.profiles.engine

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
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
internal object ProjectWindows {

    private val LOG = Logger.getInstance("art.arcane.profiles.engine.ProjectWindows")

    /** Currently open, real (non-default, non-disposed) projects — the reconciler's ground truth. */
    fun liveProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }

    /** True when [project]'s folder is the same real directory as [target] (symlink/case/`/private`-safe). */
    fun matches(target: Path, project: Project): Boolean {
        val base = project.basePath ?: return false
        return runCatching { canonicalKey(Path.of(base)) == canonicalKey(target) }.getOrDefault(false)
    }

    /**
     * Open [path] in its OWN window. If a project for that folder is already open, focus it and
     * return it instead of opening a duplicate. Returns null if the open failed.
     */
    suspend fun openInOwnFrame(path: Path): Project? {
        liveProjects().firstOrNull { matches(path, it) }?.let {
            focus(it)
            return it
        }
        return try {
            // Suspend open: awaits full registration, is cancellable (no blocking bridge that would
            // wedge a superseded switch), and forceOpenInNewFrame=true gives its own window without
            // the platform picking a victim to close — safe because we already deduped above.
            ProjectUtil.openOrImportAsync(path, OpenProjectTask { forceOpenInNewFrame = true })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Profiles: open failed for $path", e)
            null
        }
    }

    /** Force-close [project]: no veto, no save/confirm dialog; awaits dispose. Returns success. */
    suspend fun closeProject(project: Project): Boolean {
        if (project.isDisposed) return true
        val ex = ProjectManagerEx.getInstanceEx()
        suspend fun attempt(save: Boolean): Boolean =
            try {
                ex.forceCloseProjectAsync(project, save)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("Profiles: forceClose(save=$save) failed for ${project.name}", e)
                false
            }
        if (attempt(true) || project.isDisposed) return true
        // Escalate once: close without saving rather than leave a leftover window.
        return attempt(false) || project.isDisposed
    }

    suspend fun focus(project: Project) {
        if (project.isDisposed) return
        withContext(Dispatchers.EDT) {
            runCatching { ProjectUtil.focusProjectWindow(project, false) }
        }
    }
}
