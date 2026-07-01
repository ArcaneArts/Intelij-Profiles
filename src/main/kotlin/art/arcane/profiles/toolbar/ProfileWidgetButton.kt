package art.arcane.profiles.toolbar

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.engine.ProfileSwitchEngine
import art.arcane.profiles.engine.SwitchStatus
import art.arcane.profiles.ui.ProfilePopups
import art.arcane.profiles.ui.ProfilePresentation
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.application.EDT
import com.intellij.ui.AnimatedIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Dimension
import javax.swing.JButton

/**
 * The Profiles toolbar widget's live component. Renders the active profile (or switch progress) from
 * [ProfileSwitchEngine.status], to which it subscribes for its lifetime in the toolbar so the label
 * never sticks on a stale "Switching..." state. Width is capped so a long name / emoji can't crowd the
 * neighbouring Project and Branch widgets. Clicking opens the shared main dropdown.
 */
internal class ProfileWidgetButton : JButton() {

    private var scope: CoroutineScope? = null

    init {
        isFocusable = false
        putClientProperty("styleTag", "toolbar")
        horizontalAlignment = LEFT
        addActionListener { showDropdown() }
        renderCurrent()
    }

    override fun addNotify() {
        super.addNotify()
        // Guard against a second addNotify (e.g. reparenting) leaking the prior subscription.
        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        scope = newScope
        newScope.launch {
            ProfileSwitchEngine.getInstance().status.collect { renderCurrent() }
        }
    }

    override fun removeNotify() {
        scope?.cancel()
        scope = null
        super.removeNotify()
    }

    override fun getPreferredSize(): Dimension = cap(super.getPreferredSize())

    override fun getMaximumSize(): Dimension = cap(super.getMaximumSize())

    private fun cap(d: Dimension): Dimension = Dimension(minOf(d.width, MAX_WIDTH_PX), d.height)

    fun renderCurrent() {
        val service = ProfilesService.getInstance()
        when (val status = ProfileSwitchEngine.getInstance().status.value) {
            is SwitchStatus.Switching -> {
                val target = service.findEntry(status.targetProfileName)?.toModel()
                text = target?.let { ProfilePresentation.toolbarLabel(it) }
                    ?: status.targetProfileName.take(ProfilePresentation.TOOLBAR_MAX_LABEL_CHARS)
                icon = AnimatedIcon.Default.INSTANCE
                toolTipText = "Switching to ${status.targetProfileName}: " +
                    "${status.openedCount}/${status.targetCount} open, ${status.closingCount} closing"
            }
            is SwitchStatus.Failed -> {
                val active = service.activeProfileName?.let { service.findEntry(it)?.toModel() }
                text = active?.let { ProfilePresentation.toolbarLabel(it) } ?: "Profiles"
                icon = active?.let { ProfilePresentation.toolbarIcon(it) } ?: AllIcons.General.User
                toolTipText = "Last switch failed: ${status.missingCount} not opened, " +
                    "${status.extraCount} not closed"
            }
            is SwitchStatus.Idle -> {
                val active = status.activeProfileName?.let { service.findEntry(it)?.toModel() }
                text = active?.let { ProfilePresentation.toolbarLabel(it) } ?: "Profiles"
                icon = active?.let { ProfilePresentation.toolbarIcon(it) } ?: AllIcons.General.User
                toolTipText = if (active != null) "Active profile: ${active.name}"
                else "Switch project profiles"
            }
        }
        // AbstractButton.setText/setIcon already revalidate + repaint when the value changes, so an
        // unconditional relayout on every toolbar update tick would be wasted work.
    }

    private fun showDropdown() {
        val dataContext = DataManager.getInstance().getDataContext(this)
        ProfilePopups.createMainDropdown(dataContext).showUnderneathOf(this)
    }

    companion object {
        private const val MAX_WIDTH_PX = 220
    }
}
