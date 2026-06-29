package art.arcane.profiles.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Toolbar-dropdown entry: create a profile from one or more folders, then open it. */
class NewProfileAction : AnAction(
    "New Profile…",
    "Create a profile from one or more project folders",
    AllIcons.General.Add,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ProfileCreation.promptCreateAndOpen(e.project)
    }
}
