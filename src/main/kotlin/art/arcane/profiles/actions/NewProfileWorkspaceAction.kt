package art.arcane.profiles.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Welcome-screen button (registered into WelcomeScreen.QuickStart): when no project is open, create
 * a profile workspace from several folders and open them. [AnActionEvent.getProject] is null here,
 * which the shared flow handles.
 */
class NewProfileWorkspaceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ProfileCreation.promptCreateAndOpen(e.project)
    }
}
