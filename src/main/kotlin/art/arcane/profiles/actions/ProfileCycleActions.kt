package art.arcane.profiles.actions

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.engine.ProfileSwitchEngine
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import javax.swing.Icon

/**
 * Cycle to the next/previous profile in list order, wrapping around. No default shortcuts — these
 * exist so the user can bind keys in Settings > Keymap. No-op with fewer than two profiles.
 */
sealed class ProfileCycleAction(
    private val forward: Boolean,
    text: String,
    description: String,
    icon: Icon,
) : AnAction(text, description, icon), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = ProfilesService.getInstance().profiles.size > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = ProfilesService.getInstance()
        val profiles = service.profiles
        if (profiles.size < 2) return
        val activeIdx = profiles.indexOfFirst { it.name == service.activeProfileName }
        val nextIdx = when {
            activeIdx < 0 -> if (forward) 0 else profiles.size - 1
            forward -> (activeIdx + 1) % profiles.size
            else -> (activeIdx - 1 + profiles.size) % profiles.size
        }
        ProfileSwitchEngine.getInstance().requestSwitch(profiles[nextIdx])
    }
}

class NextProfileAction : ProfileCycleAction(
    forward = true,
    text = "Switch to Next Profile",
    description = "Switch to the next profile in the list",
    icon = AllIcons.Actions.Forward,
)

class PreviousProfileAction : ProfileCycleAction(
    forward = false,
    text = "Switch to Previous Profile",
    description = "Switch to the previous profile in the list",
    icon = AllIcons.Actions.Back,
)
