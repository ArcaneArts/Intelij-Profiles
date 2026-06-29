package art.arcane.profiles.toolbar

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.engine.ProfileSwitchEngine
import art.arcane.profiles.actions.NewProfileAction
import art.arcane.profiles.actions.SaveCurrentAsProfileAction
import art.arcane.profiles.model.Profile
import art.arcane.profiles.ui.ProfilesConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.impl.ExpandableComboAction
import com.intellij.openapi.wm.impl.ToolbarComboButton
import javax.swing.Icon
import javax.swing.JComponent

/**
 * The Profiles dropdown in the new-UI main toolbar, beside the Project and Branch widgets. The
 * label reflects the active profile; the popup lists profiles to switch to plus the create/manage
 * actions.
 */
class ProfileToolbarWidgetAction : ExpandableComboAction(), DumbAware {

    // Toolbar widget update() runs on a background thread and may only touch the presentation.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        val active = ProfilesService.getInstance().activeProfileName
        e.presentation.text = active ?: "Profiles"
        e.presentation.icon = AllIcons.General.User
        e.presentation.description =
            if (active != null) "Active profile: $active" else "Switch project profiles"
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        super.updateCustomComponent(component, presentation)
        (component as? ToolbarComboButton)?.apply {
            text = presentation.text
            toolTipText = presentation.description
            leftIcons = listOfNotNull<Icon>(presentation.icon)
        }
    }

    override fun createPopup(event: AnActionEvent): JBPopup {
        val service = ProfilesService.getInstance()
        val active = service.activeProfileName
        val group = DefaultActionGroup()

        val profiles = service.profiles
        if (profiles.isEmpty()) {
            group.add(DisabledHint("No profiles yet — save your open windows below"))
        } else {
            for (profile in profiles) {
                group.add(SwitchToProfileAction(profile, profile.name == active))
            }
        }

        group.addSeparator()
        group.add(SaveCurrentAsProfileAction())
        group.add(NewProfileAction())
        group.add(ManageProfilesAction())

        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Profiles",
            group,
            event.dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    private class SwitchToProfileAction(private val profile: Profile, active: Boolean) : AnAction(
        profile.name,
        "Switch to \"${profile.name}\"",
        if (active) AllIcons.Actions.Checked else null,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = ProfileSwitchEngine.getInstance().requestSwitch(profile)
    }

    private class ManageProfilesAction : AnAction(
        "Manage Profiles…",
        "Open profile settings",
        AllIcons.General.Settings,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            // editConfigurable shows the panel directly, bypassing the settings-tree lookup that
            // silently failed for our declaratively-registered configurable.
            ShowSettingsUtil.getInstance().editConfigurable(e.project, ProfilesConfigurable())
        }
    }

    private class DisabledHint(text: String) : AnAction(text), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }
        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}
