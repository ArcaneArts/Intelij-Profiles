package art.arcane.profiles.actions

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.engine.ProfileSwitchEngine
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Shared "name it, pick one or more folders, create the profile, then open it" flow. Used by both
 * the toolbar dropdown's New Profile action and the Welcome-screen button. [project] is null when
 * invoked from the Welcome screen, which the dialogs and project-open path handle fine.
 */
object ProfileCreation {

    fun promptCreateAndOpen(project: Project?) {
        val service = ProfilesService.getInstance()
        val name = Messages.showInputDialog(
            project,
            "Name for the new profile:",
            "New Profile",
            null,
            service.suggestUniqueName(),
            ProfileNameValidator(service),
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        val descriptor = FileChooserDescriptorFactory.multiDirs()
            .withTitle("Select Project Folders")
            .withDescription("Choose one or more project root folders to include in \"$name\"")
        val chosen = FileChooser.chooseFiles(descriptor, project, null)
        val paths = chosen.map { it.path }

        val profile = service.addProfile(name, paths)
        // Open the new profile's projects immediately (requestSwitch also refreshes the toolbar label).
        ProfileSwitchEngine.getInstance().requestSwitch(profile)
    }
}
