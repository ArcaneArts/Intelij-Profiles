package art.arcane.profiles.ui

import art.arcane.profiles.ProfilesService
import art.arcane.profiles.ProjectPaths
import art.arcane.profiles.io.ProfilesJson
import art.arcane.profiles.model.Profile
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColorPanel
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Master/detail editor for profiles: name, color, icon (a built-in icon or a typed emoji), and the
 * project list, plus reorder/duplicate, missing-folder cleanup, and JSON/folder import-export. Edits
 * a working copy and writes back to [ProfilesService] only on [apply].
 */
class ProfilesPanel {

    private class Editable(
        var name: String,
        var color: String?,
        var icon: String?,
        val paths: MutableList<String>,
    ) {
        fun renderModel(): Profile = Profile(name, color, icon, emptyList())
    }

    private val service = ProfilesService.getInstance()

    private val profilesModel = CollectionListModel<Editable>()
    private val profilesList = JBList(profilesModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<Editable>() {
            override fun customizeCellRenderer(
                list: JList<out Editable>,
                value: Editable?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                val model = value.renderModel()
                icon = ProfilePresentation.icon(model)
                val name = value.name.ifBlank { "(unnamed)" }
                val emoji = ProfilePresentation.emoji(model)
                append(if (emoji != null) "$emoji $name" else name)
                append("  ${value.paths.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private val pathsModel = CollectionListModel<String>()
    private val pathsList = JBList(pathsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(
                list: JList<out String>,
                value: String?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                if (pathExists(value)) {
                    append(value)
                } else {
                    append(value, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  (missing)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
        }
    }

    private val nameField = JBTextField()
    private val colorPanel = ColorPanel()
    private val iconCombo = ComboBox<ProfileIcon?>(arrayOf<ProfileIcon?>(null, *ProfileIcon.entries.toTypedArray())).apply {
        renderer = object : SimpleListCellRenderer<ProfileIcon?>() {
            override fun customize(list: JList<out ProfileIcon?>, value: ProfileIcon?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.display ?: "None"
                icon = value?.icon
            }
        }
    }
    private val emojiField = JBTextField().apply { columns = 4 }
    private val removeMissingButton = JButton("Remove missing").apply { addActionListener { removeMissingPaths() } }

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
            .setMoveUpAction { moveProfile(-1) }
            .setMoveDownAction { moveProfile(1) }
            .setMoveUpActionUpdater { profilesList.selectedIndex > 0 }
            .setMoveDownActionUpdater {
                val i = profilesList.selectedIndex
                i in 0 until (profilesModel.size - 1)
            }
            .createPanel()

        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onNameEdited()
            override fun removeUpdate(e: DocumentEvent) = onNameEdited()
            override fun changedUpdate(e: DocumentEvent) = onNameEdited()
        })
        colorPanel.addActionListener { syncColor() }
        iconCombo.addActionListener { onIconComboChanged() }
        emojiField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onEmojiEdited()
            override fun removeUpdate(e: DocumentEvent) = onEmojiEdited()
            override fun changedUpdate(e: DocumentEvent) = onEmojiEdited()
        })

        val pathsPanel = ToolbarDecorator.createDecorator(pathsList)
            .setAddAction { addPath() }
            .setRemoveAction { removePath() }
            .setAddActionUpdater { _ -> current != null }
            .setRemoveActionUpdater { _ -> current != null && pathsList.selectedIndex >= 0 }
            .createPanel()

        val pathsToolRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(removeMissingButton) }
        val projectsBox = JPanel(BorderLayout(0, 4)).apply {
            add(JBLabel("Projects in this profile:"), BorderLayout.NORTH)
            add(pathsPanel, BorderLayout.CENTER)
            add(pathsToolRow, BorderLayout.SOUTH)
        }

        val detail = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Color:", colorPanel)
            .addLabeledComponent("Icon:", iconCombo)
            .addLabeledComponent("Emoji:", emojiField)
            .addComponentFillVertically(projectsBox, 8)
            .panel

        val splitter = JBSplitter(false, 0.32f).apply {
            firstComponent = profilesPanel
            secondComponent = detail
        }

        val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JButton("Duplicate").apply { addActionListener { duplicateProfile() } })
            add(JButton("Create from Folder…").apply { addActionListener { importFromFolder() } })
            add(JButton("Export…").apply { addActionListener { exportJson() } })
            add(JButton("Import…").apply { addActionListener { importJson() } })
        }

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(splitter, BorderLayout.CENTER)
            add(actionBar, BorderLayout.SOUTH)
        }
    }

    // ---- selection / editing ----

    private fun onProfileSelected() {
        val wasLoading = loading
        loading = true
        try {
            current = profilesList.selectedValue
            val selected = current
            if (selected == null) {
                nameField.text = ""
                colorPanel.selectedColor = null
                iconCombo.selectedItem = null
                emojiField.text = ""
                pathsModel.removeAll()
                setDetailEnabled(false)
            } else {
                nameField.text = selected.name
                colorPanel.selectedColor = ProfilePresentation.colorFromHex(selected.color)
                val builtin = ProfileIcon.byKey(selected.icon)
                iconCombo.selectedItem = builtin
                emojiField.text = if (builtin == null && !selected.icon.isNullOrBlank()) selected.icon else ""
                pathsModel.replaceAll(selected.paths.toList())
                setDetailEnabled(true)
            }
        } finally {
            loading = wasLoading
        }
    }

    private fun onNameEdited() {
        if (loading) return
        val selected = current ?: return
        selected.name = nameField.text.trim()
        profilesList.repaint()
    }

    private fun syncColor() {
        if (loading) return
        val selected = current ?: return
        selected.color = colorPanel.selectedColor?.let { ColorUtil.toHex(it) }
        profilesList.repaint()
    }

    private fun onIconComboChanged() {
        if (loading) return
        val selected = current ?: return
        if (iconCombo.selectedItem is ProfileIcon) {
            loading = true
            try { emojiField.text = "" } finally { loading = false }
        }
        recomputeIcon(selected)
    }

    private fun onEmojiEdited() {
        if (loading) return
        val selected = current ?: return
        if (emojiField.text.trim().isNotEmpty()) {
            loading = true
            try { iconCombo.selectedItem = null } finally { loading = false }
        }
        recomputeIcon(selected)
    }

    /** Icon = the typed emoji if present, else the chosen built-in icon, else none. */
    private fun recomputeIcon(selected: Editable) {
        val emoji = emojiField.text.trim()
        val builtin = (iconCombo.selectedItem as? ProfileIcon)?.key
        selected.icon = when {
            emoji.isNotEmpty() -> emoji
            builtin != null -> builtin
            else -> null
        }
        profilesList.repaint()
    }

    // ---- profile add/remove/reorder/duplicate ----

    private fun addProfile() {
        val entry = Editable(nextNumberedName(), null, null, mutableListOf())
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

    private fun moveProfile(delta: Int) {
        val i = profilesList.selectedIndex
        val j = i + delta
        if (i < 0 || j < 0 || j >= profilesModel.size) return
        profilesModel.exchangeRows(i, j)
        profilesList.selectedIndex = j
    }

    private fun duplicateProfile() {
        val src = profilesList.selectedValue ?: return
        val copy = Editable(uniqueName(src.name.ifBlank { "Profile" }), src.color, src.icon, src.paths.toMutableList())
        profilesModel.add(copy)
        profilesList.selectedIndex = profilesModel.size - 1
    }

    // ---- project path add/remove/clean ----

    private fun addPath() {
        val selected = current ?: return
        val descriptor = FileChooserDescriptorFactory.multiDirs()
            .withTitle("Select Project Folders")
            .withDescription("Choose one or more project root folders")
        val chosen = FileChooser.chooseFiles(descriptor, component, null, null)
        for (file in chosen) {
            val path = ProjectPaths.normalize(file.path)
            if (selected.paths.none { ProjectPaths.sameProject(it, path) }) {
                selected.paths.add(path)
                pathsModel.add(path)
            }
        }
        profilesList.repaint()
    }

    private fun removePath() {
        val selected = current ?: return
        val index = pathsList.selectedIndex
        if (index < 0) return
        val path = pathsModel.getElementAt(index)
        selected.paths.removeIf { ProjectPaths.sameProject(it, path) }
        pathsModel.remove(index)
        profilesList.repaint()
    }

    private fun removeMissingPaths() {
        val selected = current ?: return
        val missing = selected.paths.filterNot { pathExists(it) }
        if (missing.isEmpty()) {
            Messages.showInfoMessage(component, "All project folders exist.", "Nothing to Remove")
            return
        }
        selected.paths.removeAll(missing.toSet())
        pathsModel.replaceAll(selected.paths.toList())
        profilesList.repaint()
    }

    // ---- import / export ----

    private fun importFromFolder() {
        val selected = FolderImportDialog.chooseAndSelect(null) ?: return
        addImportedProfiles(selected)
    }

    private fun exportJson() {
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export Profiles", "Save all profiles to a JSON file", "json"),
            component,
        )
        val wrapper = dialog.save(null as Path?, "profiles.json") ?: return
        try {
            wrapper.file.writeText(ProfilesJson.encode(toProfiles()))
            Messages.showInfoMessage(component, "Exported ${profilesModel.size} profile(s).", "Export Profiles")
        } catch (ex: IOException) {
            Messages.showErrorDialog(component, "Could not write file: ${ex.message}", "Export Failed")
        }
    }

    private fun importJson() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import Profiles")
        val vf = FileChooser.chooseFiles(descriptor, component, null, null).firstOrNull() ?: return
        val incoming = try {
            ProfilesJson.decode(String(vf.contentsToByteArray(), Charsets.UTF_8))
        } catch (ex: Exception) {
            Messages.showErrorDialog(component, "Not a valid profiles JSON file: ${ex.message}", "Import Failed")
            return
        }
        if (incoming.isEmpty()) {
            Messages.showInfoMessage(component, "No profiles found in that file.", "Nothing to Import")
            return
        }
        addImportedProfiles(incoming)
    }

    /** Add imported profiles to the working copy, suffixing names that collide with existing ones. */
    private fun addImportedProfiles(incoming: List<Profile>) {
        for (p in incoming) {
            val name = uniqueName(p.name.ifBlank { "Profile" })
            profilesModel.add(Editable(name, p.color, p.icon, p.projectPaths.toMutableList()))
        }
        if (profilesList.selectedIndex < 0 && profilesModel.size > 0) {
            profilesList.selectedIndex = profilesModel.size - 1
        }
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
                service.profiles.map { Editable(it.name, it.color, it.icon, it.projectPaths.toMutableList()) },
            )
            current = null
            nameField.text = ""
            colorPanel.selectedColor = null
            iconCombo.selectedItem = null
            emojiField.text = ""
            pathsModel.removeAll()
            setDetailEnabled(false)
        } finally {
            loading = false
        }
        if (profilesModel.size > 0) profilesList.selectedIndex = 0
    }

    // ---- helpers ----

    private fun pathExists(path: String): Boolean =
        try { Files.exists(Path.of(path)) } catch (e: Exception) { false }

    private fun toProfiles(): List<Profile> =
        (0 until profilesModel.size).map { index ->
            val entry = profilesModel.getElementAt(index)
            Profile(entry.name.trim(), entry.color, entry.icon, ProjectPaths.normalizeAll(entry.paths))
        }

    private fun setDetailEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        colorPanel.isEnabled = enabled
        iconCombo.isEnabled = enabled
        emojiField.isEnabled = enabled
        pathsList.isEnabled = enabled
        removeMissingButton.isEnabled = enabled
    }

    private fun currentNames(): Set<String> =
        (0 until profilesModel.size).map { profilesModel.getElementAt(it).name }.toSet()

    /** First "Profile N" name not already used by the working copy (the Add button). */
    private fun nextNumberedName(): String {
        val existing = currentNames()
        var i = 1
        while ("Profile $i" in existing) i++
        return "Profile $i"
    }

    /** [base] if free, else "base (2)", "base (3)", … (duplicate / import collisions). */
    private fun uniqueName(base: String): String {
        val existing = currentNames()
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }
}
