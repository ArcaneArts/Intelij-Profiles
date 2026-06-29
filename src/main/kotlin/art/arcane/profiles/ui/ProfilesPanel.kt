package art.arcane.profiles.ui

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.ProjectPaths
import art.arcane.profiles.model.Profile
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Master/detail editor for profiles. Edits a working copy and writes it back to [ProfilesService]
 * only on [apply], so Cancel discards cleanly and the standard Settings dirty-state works.
 */
class ProfilesPanel {

    private class Editable(var name: String, var color: String?, val paths: MutableList<String>)

    private val service = ProfilesService.getInstance()

    private val profilesModel = CollectionListModel<Editable>()
    private val profilesList = JBList(profilesModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SimpleListCellRenderer.create("(unnamed)") { it.name.ifBlank { "(unnamed)" } }
    }

    private val pathsModel = CollectionListModel<String>()
    private val pathsList = JBList(pathsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val nameField = JBTextField()

    private var current: Editable? = null
    private var loading = false

    val component: JComponent = build()

    init {
        reset()
    }

    private fun build(): JComponent {
        profilesList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onProfileSelected()
        }
        val profilesPanel = ToolbarDecorator.createDecorator(profilesList)
            .setAddAction { addProfile() }
            .setRemoveAction { removeProfile() }
            .createPanel()

        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onNameEdited()
            override fun removeUpdate(e: DocumentEvent) = onNameEdited()
            override fun changedUpdate(e: DocumentEvent) = onNameEdited()
        })

        val pathsPanel = ToolbarDecorator.createDecorator(pathsList)
            .setAddAction { addPath() }
            .setRemoveAction { removePath() }
            .createPanel()

        val projectsBox = JPanel(BorderLayout(0, 4)).apply {
            add(JBLabel("Projects in this profile:"), BorderLayout.NORTH)
            add(pathsPanel, BorderLayout.CENTER)
        }

        val detail = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addComponentFillVertically(projectsBox, 8)
            .panel

        val splitter = JBSplitter(false, 0.32f).apply {
            firstComponent = profilesPanel
            secondComponent = detail
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(splitter, BorderLayout.CENTER)
        }
    }

    // ---- selection / editing ----

    private fun onProfileSelected() {
        loading = true
        try {
            current = profilesList.selectedValue
            val selected = current
            if (selected == null) {
                nameField.text = ""
                pathsModel.removeAll()
                setDetailEnabled(false)
            } else {
                nameField.text = selected.name
                pathsModel.replaceAll(selected.paths.toList())
                setDetailEnabled(true)
            }
        } finally {
            loading = false
        }
    }

    private fun onNameEdited() {
        if (loading) return
        val selected = current ?: return
        selected.name = nameField.text.trim()
        profilesList.repaint()
    }

    // ---- profile add/remove ----

    private fun addProfile() {
        val entry = Editable(uniqueWorkingName(), null, mutableListOf())
        profilesModel.add(entry)
        profilesList.selectedIndex = profilesModel.size - 1
    }

    private fun removeProfile() {
        val index = profilesList.selectedIndex
        if (index < 0) return
        profilesModel.remove(index)
        val next = index.coerceAtMost(profilesModel.size - 1)
        if (next >= 0) profilesList.selectedIndex = next else onProfileSelected()
    }

    // ---- project path add/remove ----

    private fun addPath() {
        val selected = current ?: return
        val descriptor = FileChooserDescriptorFactory.multiDirs()
            .withTitle("Select Project Folders")
            .withDescription("Choose one or more project root folders")
        val chosen = FileChooser.chooseFiles(descriptor, null, null)
        for (file in chosen) {
            val path = ProjectPaths.normalize(file.path)
            if (selected.paths.none { ProjectPaths.sameProject(it, path) }) {
                selected.paths.add(path)
                pathsModel.add(path)
            }
        }
    }

    private fun removePath() {
        val selected = current ?: return
        val index = pathsList.selectedIndex
        if (index < 0) return
        val path = pathsModel.getElementAt(index)
        selected.paths.removeIf { ProjectPaths.sameProject(it, path) }
        pathsModel.remove(index)
    }

    // ---- Configurable contract ----

    fun isModified(): Boolean = toProfiles() != service.profiles

    fun apply() {
        val profiles = toProfiles()
        val names = profiles.map { it.name }
        if (names.any { it.isBlank() }) {
            throw ConfigurationException("Profile names cannot be blank")
        }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ConfigurationException("Duplicate profile names: ${duplicates.joinToString()}")
        }
        service.replaceAll(profiles)
        ActivityTracker.getInstance().inc()
    }

    fun reset() {
        loading = true
        try {
            profilesModel.replaceAll(
                service.profiles.map { Editable(it.name, it.color, it.projectPaths.toMutableList()) },
            )
            current = null
            nameField.text = ""
            pathsModel.removeAll()
            setDetailEnabled(false)
        } finally {
            loading = false
        }
        if (profilesModel.size > 0) profilesList.selectedIndex = 0
    }

    // ---- helpers ----

    private fun toProfiles(): List<Profile> =
        (0 until profilesModel.size).map { index ->
            val entry = profilesModel.getElementAt(index)
            Profile(entry.name.trim(), entry.color, ProjectPaths.normalizeAll(entry.paths))
        }

    private fun setDetailEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        pathsList.isEnabled = enabled
    }

    private fun uniqueWorkingName(): String {
        val existing = (0 until profilesModel.size).map { profilesModel.getElementAt(it).name }.toSet()
        var i = 1
        while ("Profile $i" in existing) i++
        return "Profile $i"
    }
}
