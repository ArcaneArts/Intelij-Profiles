package art.arcane.profiles

import art.arcane.profiles.model.Profile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level store of profiles. Profiles span projects, so this is an application service,
 * not a project service. State is serialized to `<config>/options/arcane-profiles.xml`.
 *
 * The CRUD operations here are pure state mutations with no platform-project dependencies, so they
 * are unit-tested against a directly-constructed instance (see ProfilesServiceTest).
 */
@Service(Service.Level.APP)
@State(
    name = "ArcaneProfiles",
    storages = [Storage("arcane-profiles.xml", roamingType = RoamingType.DISABLED)],
)
class ProfilesService : SimplePersistentStateComponent<ProfilesService.ProfilesState>(ProfilesState()) {

    class ProfilesState : BaseState() {
        var profiles by list<ProfileEntry>()
        var activeProfileName by string()

        // BaseState.incrementModificationCount is protected; expose it so the service can flag
        // nested-entry edits (renames, path changes) as dirty for persistence.
        fun bump() = incrementModificationCount()
    }

    class ProfileEntry : BaseState() {
        var name by string()
        var color by string()
        var projectPaths by list<String>()

        fun toModel(): Profile = Profile(
            name = name.orEmpty(),
            color = color,
            projectPaths = projectPaths.toList(),
        )
    }

    val profiles: List<Profile> get() = state.profiles.map { it.toModel() }

    var activeProfileName: String?
        get() = state.activeProfileName
        set(value) {
            state.activeProfileName = value
            state.bump()
        }

    fun findEntry(name: String): ProfileEntry? = state.profiles.firstOrNull { it.name == name }

    fun hasProfile(name: String): Boolean = findEntry(name) != null

    fun addProfile(name: String, paths: List<String> = emptyList(), color: String? = null): Profile {
        require(name.isNotBlank()) { "Profile name must not be blank" }
        require(!hasProfile(name)) { "A profile named '$name' already exists" }
        val entry = ProfileEntry().apply {
            this.name = name
            this.color = color
            this.projectPaths = ProjectPaths.normalizeAll(paths).toMutableList()
        }
        state.profiles.add(entry)
        state.bump()
        return entry.toModel()
    }

    fun deleteProfile(name: String) {
        val removed = state.profiles.removeIf { it.name == name }
        if (removed) {
            if (state.activeProfileName == name) state.activeProfileName = null
            state.bump()
        }
    }

    fun renameProfile(oldName: String, newName: String) {
        require(newName.isNotBlank()) { "Profile name must not be blank" }
        val entry = findEntry(oldName) ?: error("No profile named '$oldName'")
        if (oldName == newName) return
        require(!hasProfile(newName)) { "A profile named '$newName' already exists" }
        entry.name = newName
        if (state.activeProfileName == oldName) state.activeProfileName = newName
        state.bump()
    }

    fun setProjectPaths(name: String, paths: List<String>) {
        val entry = findEntry(name) ?: error("No profile named '$name'")
        entry.projectPaths = ProjectPaths.normalizeAll(paths).toMutableList()
        state.bump()
    }

    fun addProjectToProfile(name: String, path: String) {
        val entry = findEntry(name) ?: error("No profile named '$name'")
        val normalized = ProjectPaths.normalize(path)
        if (entry.projectPaths.none { ProjectPaths.sameProject(it, normalized) }) {
            entry.projectPaths.add(normalized)
            state.bump()
        }
    }

    fun removeProjectFromProfile(name: String, path: String) {
        val entry = findEntry(name) ?: error("No profile named '$name'")
        if (entry.projectPaths.removeIf { ProjectPaths.sameProject(it, path) }) {
            state.bump()
        }
    }

    /** First "Profile N" name not already taken — used to seed the new/save dialogs. */
    fun suggestUniqueName(prefix: String = "Profile"): String {
        var i = 1
        while (hasProfile("$prefix $i")) i++
        return "$prefix $i"
    }

    /** Replace the entire profile set (used by the settings page on Apply). */
    fun replaceAll(newProfiles: List<Profile>) {
        state.profiles.clear()
        for (p in newProfiles) {
            state.profiles.add(
                ProfileEntry().apply {
                    name = p.name
                    color = p.color
                    projectPaths = ProjectPaths.normalizeAll(p.projectPaths).toMutableList()
                },
            )
        }
        if (state.activeProfileName != null && newProfiles.none { it.name == state.activeProfileName }) {
            state.activeProfileName = null
        }
        state.bump()
    }

    companion object {
        @JvmStatic
        fun getInstance(): ProfilesService =
            ApplicationManager.getApplication().getService(ProfilesService::class.java)
    }
}
