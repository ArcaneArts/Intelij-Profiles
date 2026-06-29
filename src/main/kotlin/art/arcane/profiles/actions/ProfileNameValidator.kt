package art.arcane.profiles.actions

import art.arcane.profiles.ProfilesService
import com.intellij.openapi.ui.InputValidator

/**
 * Rejects blank names and names that collide with an existing profile. [allow] lets a rename keep
 * its own current name (which would otherwise read as a collision).
 */
class ProfileNameValidator(
    private val service: ProfilesService,
    private val allow: String? = null,
) : InputValidator {

    override fun checkInput(inputString: String?): Boolean {
        val name = inputString?.trim().orEmpty()
        return name.isNotEmpty() && (name == allow || !service.hasProfile(name))
    }

    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
}
