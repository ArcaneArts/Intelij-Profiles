package art.arcane.profiles.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Reproduction guard for the "can't open Manage Profiles without an error" report: builds the
 * settings panel the same way the Settings dialog does. If constructing the Configurable / panel
 * throws, this test fails with the real stack trace.
 */
class ProfilesConfigurableTest : BasePlatformTestCase() {

    fun testConfigurableComponentBuildsAndResets() {
        val configurable = ProfilesConfigurable()
        try {
            val component = configurable.createComponent()
            assertNotNull(component)
            configurable.reset()
            configurable.isModified()
        } finally {
            configurable.disposeUIResources()
        }
    }
}
