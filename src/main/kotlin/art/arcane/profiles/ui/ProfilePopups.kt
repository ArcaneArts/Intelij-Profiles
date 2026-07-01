package art.arcane.profiles.ui

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.actions.ImportProfilesFromFolderAction
import art.arcane.profiles.actions.NewProfileAction
import art.arcane.profiles.actions.SaveCurrentAsProfileAction
import art.arcane.profiles.actions.UpdateActiveProfileFromWindowsAction
import art.arcane.profiles.engine.ProfileSwitchEngine
import art.arcane.profiles.engine.SwitchStatus
import art.arcane.profiles.model.Profile
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
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

    /** The full main-toolbar dropdown: switch rows (or a hint) + create/capture/import/manage + version. */
    fun createMainDropdown(dataContext: DataContext): JBPopup {
        val group = DefaultActionGroup()
        val switchActions = profileSwitchActions()
        if (switchActions.isEmpty()) {
            group.add(DisabledHint("No profiles yet — save your open windows below"))
        } else {
            switchActions.forEach(group::add)
        }
        group.addSeparator()
        group.add(SaveCurrentAsProfileAction())
        group.add(UpdateActiveProfileFromWindowsAction())
        group.add(NewProfileAction())
        group.add(ImportProfilesFromFolderAction())
        group.add(ManageProfilesAction())
        group.addSeparator()
        group.add(DisabledHint("Profiles v${pluginVersion()}"))
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Profiles",
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    // Read our own version from a build-filtered resource rather than a plugin-descriptor lookup
    // (those APIs are @Internal on newer platforms); see processResources in build.gradle.kts.
    private fun pluginVersion(): String =
        runCatching {
            javaClass.getResourceAsStream("/profiles-build.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"

    private class ManageProfilesAction : AnAction(
        "Manage Profiles…",
        "Open profile settings",
        AllIcons.General.Settings,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ProfilesConfigurable::class.java)
        }
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
