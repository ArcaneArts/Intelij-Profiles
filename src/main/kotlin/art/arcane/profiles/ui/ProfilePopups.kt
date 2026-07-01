package art.arcane.profiles.ui

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.engine.ProfileSwitchEngine
import art.arcane.profiles.engine.SwitchStatus
import art.arcane.profiles.model.Profile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory

/**
 * Shared building blocks for the two profile-switch popups: the full toolbar dropdown
 * ([art.arcane.profiles.toolbar.ProfileToolbarWidgetAction]) and the standalone, keybindable
 * quick-switch action ([art.arcane.profiles.actions.SwitchProfileAction]). Keeping the row actions
 * here means both surfaces render profiles identically (same label, count, active marker).
 */
object ProfilePopups {

    /** "Name (active)" / "Name (switching)" row label with project count. */
    fun rowLabel(profile: Profile, active: Boolean, switching: Boolean = false): String {
        val activeMark = when {
            switching -> " (switching)"
            active -> " (active)"
            else -> ""
        }
        val n = profile.projectPaths.size
        val count = " — $n " + if (n == 1) "project" else "projects"
        return ProfilePresentation.label(profile) + activeMark + count
    }

    /** One switch action per profile, with the active or switching one marked. */
    fun profileSwitchActions(): List<AnAction> {
        val service = ProfilesService.getInstance()
        val active = service.activeProfileName
        val switchingTarget = (ProfileSwitchEngine.getInstance().status.value as? SwitchStatus.Switching)?.targetProfileName
        return service.profiles.map { SwitchToProfileAction(it, it.name == active, it.name == switchingTarget) }
    }

    /** A standalone speed-search popup listing profiles to switch to (used by the bindable action). */
    fun createQuickSwitchPopup(dataContext: DataContext): JBPopup {
        val group = DefaultActionGroup()
        val actions = profileSwitchActions()
        if (actions.isEmpty()) {
            group.add(DisabledHint("No profiles yet — create one from the Profiles dropdown"))
        } else {
            actions.forEach(group::add)
        }
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Switch Profile",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    internal class SwitchToProfileAction(private val profile: Profile, active: Boolean, switching: Boolean) : AnAction(
        rowLabel(profile, active, switching),
        "Switch to \"${profile.name}\"",
        ProfilePresentation.icon(profile),
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = ProfileSwitchEngine.getInstance().requestSwitch(profile)
    }

    /** A non-actionable, greyed row used for hints and the version footer. */
    internal class DisabledHint(text: String) : AnAction(text), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }
        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}
