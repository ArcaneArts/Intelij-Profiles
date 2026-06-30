package art.arcane.profiles.toolbar

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.actions.ImportProfilesFromFolderAction
import art.arcane.profiles.actions.NewProfileAction
import art.arcane.profiles.actions.SaveCurrentAsProfileAction
import art.arcane.profiles.actions.UpdateActiveProfileFromWindowsAction
import art.arcane.profiles.ui.ProfilePopups
import art.arcane.profiles.ui.ProfilePresentation
import art.arcane.profiles.ui.ProfilesConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.impl.ExpandableComboAction
import java.util.Properties

/**
 * The Profiles dropdown in the new-UI main toolbar, beside the Project and Branch widgets. The
 * label/icon reflect the active profile (set in [update] on the presentation; the base
 * [ExpandableComboAction] renders them onto the combo, so no custom-component override is needed).
 * The popup lists profiles to switch to (with project counts) plus the create/capture/import/manage
 * actions.
 */
class ProfileToolbarWidgetAction : ExpandableComboAction(), DumbAware {

    // Toolbar widget update() runs on a background thread and may only touch the presentation.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        val service = ProfilesService.getInstance()
        val activeName = service.activeProfileName
        val active = activeName?.let { service.findEntry(it)?.toModel() }
        e.presentation.text = active?.let { ProfilePresentation.label(it) } ?: "Profiles"
        e.presentation.icon = active?.let { ProfilePresentation.icon(it) } ?: AllIcons.General.User
        e.presentation.description =
            if (activeName != null) "Active profile: $activeName" else "Switch project profiles"
    }

    override fun createPopup(event: AnActionEvent): JBPopup {
        val group = DefaultActionGroup()

        val switchActions = ProfilePopups.profileSwitchActions()
        if (switchActions.isEmpty()) {
            group.add(ProfilePopups.DisabledHint("No profiles yet — save your open windows below"))
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
        group.add(ProfilePopups.DisabledHint("Profiles v${pluginVersion()}"))

        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Profiles",
            group,
            event.dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    // Read our own version from a build-filtered resource rather than a plugin-descriptor lookup
    // (those APIs are @Internal on newer platforms); see processResources in build.gradle.kts.
    private fun pluginVersion(): String =
        runCatching {
            javaClass.getResourceAsStream("/profiles-build.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
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
}
