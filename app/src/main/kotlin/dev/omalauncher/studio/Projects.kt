package dev.omalauncher.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import org.json.JSONObject
import java.io.File

/** A web app being built: a folder in the guest's ~/projects, and the Codex thread that builds it. */
data class Project(
    val slug: String,
    val name: String,
    val threadId: String?,
    val created: Long,
) {
    val guestDir get() = "$GUEST_PROJECTS/$slug"
}

const val GUEST_PROJECTS = "/root/projects"

/**
 * The projects in [root] (the guest's ~/projects, seen from the host). Each keeps what Phonex knows about
 * it in `.phonex/project.json`, next to `.phonex/dev`, the script Codex writes to start its dev server; a
 * folder without the json isn't a project, so the user's own clones there are left alone.
 */
class ProjectStore(
    private val root: File,
) {
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set

    fun load() {
        projects =
            root.listFiles().orEmpty().mapNotNull { dir -> read(dir) }.sortedByDescending { it.created }
    }

    /** A new project for [prompt], named after it; nothing in the folder yet but Phonex's metadata. */
    fun create(prompt: String): Project {
        val name = nameFor(prompt)
        val slug = slugFor(name, root.list().orEmpty().toSet())
        val project = Project(slug, name, threadId = null, created = System.currentTimeMillis())
        write(project)
        projects = listOf(project) + projects
        return project
    }

    fun update(project: Project) {
        write(project)
        projects = projects.map { if (it.slug == project.slug) project else it }
    }

    fun delete(project: Project) {
        LauncherData.deleteTree(File(root, project.slug))
        projects = projects.filter { it.slug != project.slug }
    }

    fun dir(project: Project) = File(root, project.slug)

    private fun read(dir: File): Project? {
        val json = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull() ?: return null
        return Project(
            dir.name,
            json.optString("name", dir.name),
            json.optString("threadId").takeIf { it.isNotEmpty() },
            json.optLong("created"),
        )
    }

    private fun write(project: Project) {
        val file = File(root, "${project.slug}/$META")
        file.parentFile?.mkdirs()
        val json = JSONObject().put("name", project.name).put("created", project.created)
        project.threadId?.let { json.put("threadId", it) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(file)
    }

    companion object {
        const val META = ".phonex/project.json"

        private val FILLER = setOf("a", "an", "the", "me", "my", "please", "make", "build", "create", "write", "code", "simple", "i", "want", "need", "app", "for")

        /** "Build me a habit tracker with streaks" -> "Habit tracker with streaks": the prompt's first few real words. */
        fun nameFor(prompt: String): String {
            val words = prompt.split(Regex("""[^\p{L}\p{N}'-]+""")).filter { it.isNotEmpty() }
            val start = words.indexOfFirst { it.lowercase() !in FILLER }.takeIf { it >= 0 } ?: return "Untitled app"
            return words.drop(start).take(4).joinToString(" ").replaceFirstChar { it.uppercase() }
        }

        /** A folder name for [name] not among [taken]: lowercase ascii and dashes, numbered when it clashes. */
        fun slugFor(
            name: String,
            taken: Set<String>,
        ): String {
            val base =
                java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                    .replace(Regex("""\p{M}"""), "")
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .take(40)
                    .ifEmpty { "app" }
            if (base !in taken) return base
            return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
        }
    }
}
