package art.arcane.profiles.actions

import art.arcane.profiles.Notifications
import art.arcane.profiles.ProfilesService
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager

/**
 * Re-capture the currently open windows into the ACTIVE profile, so a profile you've opened/closed
 * projects from stays in sync without visiting the settings page. Disabled when no profile is active.
 */
class UpdateActiveProfileFromWindowsAction : AnAction(
    "Update Active Profile from Open Windows",
    "Replace the active profile's projects with the windows you currently have open",
    AllIcons.Actions.Refresh,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = ProfilesService.getInstance().activeProfileName != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = ProfilesService.getInstance()
        val active = service.activeProfileName ?: return
        val openPaths = ProjectManager.getInstance().openProjects
            .filterNot { it.isDefault }
            .mapNotNull { it.basePath }
        if (service.updateProfilePaths(active, openPaths)) {
            ActivityTracker.getInstance().inc()
            val n = openPaths.size
            Notifications.info("Updated \"$active\" to $n " + if (n == 1) "project." else "projects.")
        }
    }
}
