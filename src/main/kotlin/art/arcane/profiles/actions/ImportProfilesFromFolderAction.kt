package art.arcane.profiles.actions

import art.arcane.profiles.Notifications
import art.arcane.profiles.ProfilesService
import art.arcane.profiles.ui.FolderImportDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Scan a root folder (e.g. ~/Developer/RemoteGit) and create one profile per subfolder, with that
 * subfolder's subfolders as the projects. Same-named profiles are merged (new paths added) rather
 * than duplicated. Does NOT switch to anything — it just creates the profiles.
 */
class ImportProfilesFromFolderAction : AnAction(
    "Create Profiles from Folder…",
    "Turn a folder of org/repo subfolders into profiles",
    AllIcons.Nodes.Folder,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val selected = FolderImportDialog.chooseAndSelect(e.project) ?: return
        if (selected.isEmpty()) return
        val result = ProfilesService.getInstance().importMerge(selected)
        ActivityTracker.getInstance().inc()
        Notifications.info("Created ${result.added} profile(s)" + if (result.merged > 0) ", merged ${result.merged}." else ".")
    }
}
