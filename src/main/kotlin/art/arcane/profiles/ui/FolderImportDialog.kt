package art.arcane.profiles.ui

import art.arcane.profiles.engine.FolderScanner
import art.arcane.profiles.engine.FolderScanner.ScannedProfile
import art.arcane.profiles.engine.FolderScanner.ScannedProject
import art.arcane.profiles.model.Profile
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase.CheckPolicy
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree

/**
 * Checkbox-tree picker for the folder importer: root -> org (profile) -> repo (project). Real-looking
 * repos start checked, noise (build/tooling folders) starts unchecked, and an org with nothing
 * recommended starts unchecked. Unchecking an org (or all its repos) excludes that profile.
 *
 * Use [chooseAndSelect]: it picks a root folder, scans it, shows this dialog, and returns the chosen
 * profiles (or null if cancelled / no candidates).
 */
class FolderImportDialog private constructor(
    project: Project?,
    scanned: List<ScannedProfile>,
    rootName: String,
) : DialogWrapper(project) {

    private val root = CheckedTreeNode(null)
    private val tree: CheckboxTree
    private val summary = JBLabel()

    init {
        title = "Create Profiles from \"$rootName\""
        for (profile in scanned) {
            val orgNode = CheckedTreeNode(profile).apply { isChecked = profile.recommended }
            for (proj in profile.projects) {
                orgNode.add(CheckedTreeNode(proj).apply { isChecked = proj.recommended })
            }
            root.add(orgNode)
        }
        tree = CheckboxTree(Renderer(), root, CheckPolicy(true, true, false, false))
        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) = updateSummary()
        })
        setOKButtonText("Create Profiles")
        init()
        updateSummary()
    }

    override fun createCenterPanel(): JComponent {
        TreeUtil.expandAll(tree)
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(JBScrollPane(tree).apply { preferredSize = JBUI.size(480, 460) }, BorderLayout.CENTER)
        panel.add(summary, BorderLayout.SOUTH)
        return panel
    }

    /** Each org with at least one checked repo becomes a profile of those repos' paths. */
    fun selectedProfiles(): List<Profile> {
        val result = mutableListOf<Profile>()
        for (i in 0 until root.childCount) {
            val orgNode = root.getChildAt(i) as CheckedTreeNode
            val org = orgNode.userObject as ScannedProfile
            val paths = (0 until orgNode.childCount)
                .map { orgNode.getChildAt(it) as CheckedTreeNode }
                .filter { it.isChecked }
                .map { (it.userObject as ScannedProject).path }
            if (paths.isNotEmpty()) result.add(Profile(org.name, null, null, paths))
        }
        return result
    }

    private fun updateSummary() {
        val selected = selectedProfiles()
        val projects = selected.sumOf { it.projectPaths.size }
        summary.text = "${selected.size} profile(s), $projects project(s) selected"
        setOKActionEnabled(selected.isNotEmpty())
    }

    private class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? CheckedTreeNode ?: return
            when (val obj = node.userObject) {
                is ScannedProfile -> {
                    textRenderer.icon = AllIcons.Nodes.Folder
                    textRenderer.append(obj.name)
                    val n = obj.projects.size
                    textRenderer.append("  $n " + (if (n == 1) "folder" else "folders"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ScannedProject -> {
                    val attrs = if (obj.recommended) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
                    textRenderer.icon = if (obj.recommended) AllIcons.Nodes.Module else AllIcons.Nodes.Folder
                    textRenderer.append(obj.name, attrs)
                    if (!obj.recommended) {
                        textRenderer.append("  (no project markers)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                }
            }
        }
    }

    companion object {
        fun chooseAndSelect(project: Project?): List<Profile>? {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Root Folder")
                .withDescription("Each subfolder becomes a profile; its subfolders become the projects")
            val rootVf = FileChooser.chooseFile(descriptor, project, null) ?: return null
            val scanned = FolderScanner.scan(Path.of(rootVf.path))
            if (scanned.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    "No subfolders found in \"${rootVf.name}\" to turn into profiles.",
                    "Nothing to Import",
                )
                return null
            }
            val dialog = FolderImportDialog(project, scanned, rootVf.name)
            if (!dialog.showAndGet()) return null
            return dialog.selectedProfiles()
        }
    }
}
