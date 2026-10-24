package dev.omalauncher.studio

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.linux.OutputTail
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.killTree
import dev.omalauncher.linux.shellQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Where a project's dev server stands, for the preview. */
sealed interface ServerState {
    /** Codex hasn't written `.phonex/dev` yet: nothing to run. */
    data object NoScript : ServerState

    data object Stopped : ServerState

    data class Starting(
        val log: List<String>,
    ) : ServerState

    data class Running(
        val url: String,
    ) : ServerState

    /** It exited on its own; [log] is the end of what it printed, which is where the error is. */
    data class Crashed(
        val code: Int,
        val log: List<String>,
    ) : ServerState
}

/**
 * Runs a project's `.phonex/dev` in the guest, on a port of its own, and says when it accepts
 * connections. Phonex starts it rather than Codex: a server Codex starts in a turn ends with the
 * command, and the preview needs it running between turns. The guest shares the phone's network,
 * so the preview reaches it on 127.0.0.1.
 */
class DevServer(
    private val proot: Proot,
    private val project: Project,
    private val hostDir: File,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(if (script.isFile) ServerState.Stopped else ServerState.NoScript)
        private set

    /** Bumped whenever the preview should load the page again: the server (re)started or Codex changed files. */
    var generation by mutableIntStateOf(0)
        private set

    private val script get() = File(hostDir, SCRIPT)
    private var process: Process? = null
    private var job: Job? = null
    private var port = 0

    /** When the start script and dependency files last changed, as of the server's start. */
    private var started: Map<String, Long> = emptyMap()

    fun start() {
        if (job?.isActive == true) return
        if (!script.isFile) {
            state = ServerState.NoScript
            return
        }
        val tail = OutputTail(40)
        started = stamps(hostDir)
        state = ServerState.Starting(emptyList())
        job =
            scope.launch {
                port = withContext(Dispatchers.IO) { freePort() }
                val command = "cd ${shellQuote(project.guestDir)} && PORT=$port HOST=127.0.0.1 exec sh $SCRIPT </dev/null"
                val started = withContext(Dispatchers.IO) { runCatching { proot.process(command).start() } }
                val p =
                    started.getOrElse {
                        state = ServerState.Crashed(-1, listOf(it.message ?: "cannot start"))
                        return@launch
                    }
                process = p
                launch(Dispatchers.IO) {
                    runCatching { p.inputStream.bufferedReader().forEachLine { line -> synchronized(tail) { tail.add(line) } } }
                }
                // Up once the port takes a connection; a server that exits first crashed.
                while (isActive && p.isAlive) {
                    if (withContext(Dispatchers.IO) { listening(port) }) {
                        state = ServerState.Running("http://127.0.0.1:$port/")
                        generation++
                        break
                    }
                    state = ServerState.Starting(synchronized(tail) { tail.toList() })
                    delay(400)
                }
                val code = withContext(Dispatchers.IO) { p.waitFor() }
                if (process === p) {
                    process = null
                    state = ServerState.Crashed(code, synchronized(tail) { tail.toList() })
                    Log.i(TAG, "${project.slug}: dev server exited with $code")
                }
            }
    }

    fun stop() {
        val p = process
        process = null
        job?.cancel()
        job = null
        if (p != null) scope.launch(Dispatchers.IO) { killTree(p) }
        state = if (script.isFile) ServerState.Stopped else ServerState.NoScript
    }

    fun restart() {
        stop()
        start()
    }

    /**
     * After a turn: a new or changed start script, or new dependencies, need a fresh server; anything else
     * the server's own reload picks up, and the preview reloads in case it has none. A crashed or missing
     * server gets another go, since the turn may have fixed it. Changes are read from the files' times,
     * not the turn's edits: Codex writes files with shell commands too, and `npm install` changes the lock.
     */
    fun afterTurn() {
        val running = state is ServerState.Running || state is ServerState.Starting
        when {
            !script.isFile -> stop()
            !running -> start()
            stamps(hostDir) != started -> restart()
            else -> generation++
        }
    }

    companion object {
        const val SCRIPT = ".phonex/dev"
        private const val TAG = "devserver"

        private val RESTART_FILES =
            setOf("package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "vite.config.js", "vite.config.ts", "Gemfile", "Gemfile.lock", "requirements.txt", "pyproject.toml")

        /** The start script's and the top-level dependency files' modification times; a nested package.json isn't the app's. */
        fun stamps(dir: File): Map<String, Long> =
            (RESTART_FILES + SCRIPT).associateWith { File(dir, it).lastModified() }.filterValues { it != 0L }

        private fun freePort(): Int {
            for (candidate in 5170..5299) {
                val free = runCatching { ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", candidate)) } }.isSuccess
                if (free) return candidate
            }
            return ServerSocket(0).use { it.localPort }
        }

        private fun listening(port: Int) =
            runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess
    }
}
