package art.arcane.profiles.ui

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/** Settings page under Settings/Preferences -> Tools -> Profiles. */
class ProfilesConfigurable : Configurable {

    private var panel: ProfilesPanel? = null

    override fun getDisplayName(): String = "Profiles"

    override fun createComponent(): JComponent = ProfilesPanel().also { panel = it }.component

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
