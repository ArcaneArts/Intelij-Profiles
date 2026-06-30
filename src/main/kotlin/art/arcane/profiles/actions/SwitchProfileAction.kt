package art.arcane.profiles.actions

import art.arcane.profiles.ui.ProfilePopups
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Keybindable "Switch Profile…" action (no default shortcut — bind it in Settings > Keymap). Opens a
 * speed-search popup of profiles, the same rows as the toolbar dropdown, for keyboard-driven
 * switching from anywhere.
 */
class SwitchProfileAction : AnAction(
    "Switch Profile…",
    "Pick a profile to switch to",
    AllIcons.General.User,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val popup = ProfilePopups.createQuickSwitchPopup(e.dataContext)
        val project = e.project
        if (project != null) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
    }
}
