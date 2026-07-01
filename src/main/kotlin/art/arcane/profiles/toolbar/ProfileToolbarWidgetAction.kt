package art.arcane.profiles.toolbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import javax.swing.JComponent

/**
 * The Profiles dropdown in the new-UI main toolbar, beside the Project and Branch widgets. Built on the
 * public [CustomComponentAction] (not the impl-package ExpandableComboAction) so the plugin stays off
 * unstable toolbar internals. All rendering and the live switch-state subscription live in
 * [ProfileWidgetButton]; this action only supplies that component and toggles visibility with the
 * project context.
 */
class ProfileToolbarWidgetAction : AnAction(), CustomComponentAction, DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    // The component handles clicks; nothing to do when triggered via Find Action.
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        ProfileWidgetButton()

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as? ProfileWidgetButton)?.let {
            it.isVisible = presentation.isVisible
            it.renderCurrent()
        }
    }
}
