package art.arcane.profiles.actions

import art.arcane.profiles.ProfilesService
import com.intellij.openapi.ui.InputValidator

/** Rejects blank names and names that collide with an existing profile. */
class ProfileNameValidator(private val service: ProfilesService) : InputValidator {

    override fun checkInput(inputString: String?): Boolean {
        val name = inputString?.trim().orEmpty()
        return name.isNotEmpty() && !service.hasProfile(name)
    }

    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
}
