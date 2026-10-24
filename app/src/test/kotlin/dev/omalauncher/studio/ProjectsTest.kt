package dev.omalauncher.studio

import dev.omalauncher.studio.ui.shownCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class ProjectsTest {
    @Test fun aProjectIsNamedAfterItsPrompt() {
        assertEquals("Habit tracker with streaks", ProjectStore.nameFor("Build me a habit tracker with streaks and reminders"))
        assertEquals("Pomodoro timer", ProjectStore.nameFor("pomodoro timer"))
        assertEquals("Untitled app", ProjectStore.nameFor("make an app"))
    }

    @Test fun folderNamesAreAsciiAndNeverClash() {
        assertEquals("cafe-menu", ProjectStore.slugFor("Café menü!", emptySet()))
        assertEquals("todo-2", ProjectStore.slugFor("Todo", setOf("todo")))
        assertEquals("todo-3", ProjectStore.slugFor("Todo", setOf("todo", "todo-2")))
        assertEquals("app", ProjectStore.slugFor("日本", emptySet()))
    }

    @Test fun projectsSurviveAReloadAndOtherFoldersAreLeftAlone() {
        val root = createTempDirectory("projects").toFile()
        java.io.File(root, "my-clone").mkdirs()
        val store = ProjectStore(root)
        val created = store.create("A recipe box")
        store.update(created.copy(threadId = "t1"))

        val reloaded = ProjectStore(root).apply { load() }
        assertEquals(listOf(created.copy(threadId = "t1")), reloaded.projects)
        assertEquals("/root/projects/recipe-box", reloaded.projects.single().guestDir)

        reloaded.delete(reloaded.projects.single())
        assertTrue(reloaded.projects.isEmpty())
        assertFalse(java.io.File(root, "recipe-box").exists())
        assertTrue(java.io.File(root, "my-clone").exists())
        root.deleteRecursively()
    }

    @Test fun theServerRestartsForItsScriptAndDependenciesOnly() {
        val dir = createTempDirectory("app").toFile()
        java.io.File(dir, ".phonex").mkdirs()
        java.io.File(dir, ".phonex/dev").writeText("exec vite")
        java.io.File(dir, "package.json").writeText("{}")
        val before = DevServer.stamps(dir)
        assertEquals(setOf(".phonex/dev", "package.json"), before.keys)

        // The app's own code, or a dependency's manifest, doesn't need a new server.
        java.io.File(dir, "src").mkdirs()
        java.io.File(dir, "src/App.jsx").writeText("x")
        java.io.File(dir, "node_modules/react").mkdirs()
        java.io.File(dir, "node_modules/react/package.json").writeText("{}")
        assertEquals(before, DevServer.stamps(dir))

        // npm install writes the lock with a shell command, not an edit Codex reports.
        java.io.File(dir, "package-lock.json").writeText("{}")
        assertTrue(DevServer.stamps(dir) != before)
        dir.deleteRecursively()
    }

    @Test fun commandsShowWithoutTheirShellWrapper() {
        assertEquals("npm install", shownCommand("/bin/bash -lc 'npm install'"))
        assertEquals("ls -la", shownCommand("bash -lc \"ls -la\""))
        assertEquals("cat > index.html <<'EOF'", shownCommand("/usr/bin/bash -lc 'cat > index.html <<'EOF'\n<p>hi</p>\nEOF'"))
        assertEquals("git status", shownCommand("git status"))
    }

    @Test fun anUnreadableProjectIsSkipped() {
        val root = createTempDirectory("projects").toFile()
        java.io.File(root, "broken/.phonex").mkdirs()
        java.io.File(root, "broken/.phonex/project.json").writeText("{not json")
        assertNull(ProjectStore(root).apply { load() }.projects.firstOrNull())
        root.deleteRecursively()
    }
}
