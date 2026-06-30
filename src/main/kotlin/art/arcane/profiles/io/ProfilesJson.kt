package art.arcane.profiles.io

import art.arcane.profiles.model.Profile
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * Stable JSON (de)serialization for profile export/import. A tiny fixed schema
 * (`[{name, color, icon, projectPaths}]`) so exported files stay human-readable and forward-stable.
 * Pure functions with no IntelliJ-platform dependency; unit-tested by round-trip. Uses the
 * platform-bundled Gson, which handles all string escaping/unicode.
 */
object ProfilesJson {

    // Nullable throughout: Gson populates fields reflectively and leaves anything absent in the JSON
    // null, so we must tolerate nulls and normalize on the way out.
    private data class Dto(
        val name: String? = null,
        val color: String? = null,
        val icon: String? = null,
        val projectPaths: List<String>? = null,
    )

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val listType = object : TypeToken<List<Dto>>() {}.type

    fun encode(profiles: List<Profile>): String =
        gson.toJson(profiles.map { Dto(it.name, it.color, it.icon, it.projectPaths) })

    fun decode(json: String): List<Profile> {
        val dtos: List<Dto> = gson.fromJson(json, listType) ?: emptyList()
        return dtos.map {
            Profile(
                name = it.name.orEmpty(),
                color = it.color,
                icon = it.icon,
                projectPaths = it.projectPaths ?: emptyList(),
            )
        }
    }
}
