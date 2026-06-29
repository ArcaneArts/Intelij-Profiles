package art.arcane.profiles.actions

import art.arcane.profiles.ProfilesService
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages

/** Captures every currently open project into a brand-new profile and makes it active. */
class SaveCurrentAsProfileAction : AnAction(
    "Save Current Windows as Profile…",
    "Capture all currently open projects into a new profile",
    AllIcons.Actions.MenuSaveall,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val service = ProfilesService.getInstance()
        val openPaths = ProjectManager.getInstance().openProjects
            .filterNot { it.isDefault }
            .mapNotNull { it.basePath }

        val name = Messages.showInputDialog(
            e.project,
            "Name for the new profile (captures ${openPaths.size} open project(s)):",
            "Save Profile",
            null,
            service.suggestUniqueName(),
            ProfileNameValidator(service),
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        service.addProfile(name, openPaths)
        service.activeProfileName = name
        ActivityTracker.getInstance().inc()
    }
}
