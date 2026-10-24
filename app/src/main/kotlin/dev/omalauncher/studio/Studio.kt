package dev.omalauncher.studio

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.BuildConfig
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.killTree
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where Phonex's connection to Codex stands. */
sealed interface CodexState {
    /** Linux isn't running yet, or isn't installed. */
    data object Off : CodexState

    data object Installing : CodexState

    data object Starting : CodexState

    /** Not signed in. With a [code], the device-code sign-in is waiting for the user at [url]. */
    data class SignIn(
        val code: String? = null,
        val url: String? = null,
        val error: String? = null,
    ) : CodexState

    data object Ready : CodexState

    data class Failed(
        val message: String,
    ) : CodexState
}

/**
 * Phonex's side of Codex: one `codex app-server` in the guest for every project, each project a thread
 * working in its folder, and each project's dev server. Codex gets full access inside the guest, as
 * Omalauncher already gives it (see AgentStore's codex config): under proot its sandbox can't run, and
 * the guest is the phone's sandbox of its own.
 */
class Studio(
    private val runtime: Runtime,
    context: Context,
) {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "studio job failed", e) })
    private val proot get() = runtime.proot

    val projects = ProjectStore(File(proot.home, "projects"))

    var state by mutableStateOf<CodexState>(CodexState.Off)
        private set

    /** Phonex is exempt from battery optimization (see [Background]); re-read whenever its screen resumes. */
    var backgroundAllowed by mutableStateOf(true)

    private var client: CodexClient? = null
    private var process: Process? = null
    private var connecting: Job? = null
    // Watched by the wakelock: a project opened or closed changes whether a turn runs.
    private val conversations = mutableStateMapOf<String, Conversation>()

    /** Per project, the open() under way: creating a project sends its prompt while its screen opens it too. */
    private val opening = mutableMapOf<String, Deferred<Conversation>>()
    private val byThread = mutableMapOf<String, Conversation>()
    private val servers = mutableMapOf<String, DevServer>()

    /**
     * Keeps the CPU on while Phonex works: setting up Linux, installing Codex, or a Codex turn. Asleep,
     * the phone stops proot's traced processes mid-command, and a turn that should take minutes waits for
     * the screen to come on. An idle dev server doesn't need it, and the screen may turn off throughout.
     */
    private val wakeLock =
        context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonex:work").apply { setReferenceCounted(false) }

    init {
        scope.launch {
            val installing = runtime.bootstrap.state.map { it is LinuxState.Installing }
            val working = snapshotFlow { state == CodexState.Installing || conversations.values.any { it.running } }
            combine(installing, working) { a, b -> a || b }.distinctUntilChanged().collect { busy ->
                if (busy) wakeLock.acquire(WAKE_TIMEOUT_MS) else if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    /** Linux is up: list the projects and connect to Codex, installing it first if it isn't. */
    fun start() {
        scope.launch { withContext(Dispatchers.IO) { projects.load() } }
        connect()
    }

    fun connect() {
        if (connecting?.isActive == true || client != null) return
        connecting =
            scope.launch {
                try {
                    if (!runtime.agents.isInstalled(CODEX)) {
                        state = CodexState.Installing
                        if (!runtime.agents.install(CODEX)) {
                            state = CodexState.Failed("Codex couldn't be installed. Check the connection and try again.")
                            return@launch
                        }
                    }
                    state = CodexState.Starting
                    val c = launchServer()
                    c.request(
                        "initialize",
                        JSONObject().put(
                            "clientInfo",
                            JSONObject().put("name", "phonex").put("title", "Phonex").put("version", BuildConfig.VERSION_NAME),
                        ),
                    )
                    c.notify("initialized")
                    client = c
                    val account = c.request("account/read")
                    state = if (account.isNull("account") && account.optBoolean("requiresOpenaiAuth", true)) CodexState.SignIn() else CodexState.Ready
                } catch (e: CodexException) {
                    Log.w(TAG, "cannot connect to codex", e)
                    stopServer()
                    state = CodexState.Failed(e.message ?: "Codex didn't start")
                }
            }
    }

    private suspend fun launchServer(): CodexClient {
        // stderr apart: proot's warnings and Codex's logs go there, and the protocol is stdout.
        val p = withContext(Dispatchers.IO) { proot.process("exec codex app-server").redirectErrorStream(false).start() }
        process = p
        scope.launch(Dispatchers.IO) { runCatching { p.errorStream.bufferedReader().forEachLine { Log.d(TAG, it) } } }
        val c = CodexClient(p.inputStream, p.outputStream, scope)
        scope.launch { c.events.collect(::route) }
        scope.launch {
            c.closed.await()
            // A server that dies while connecting is connect()'s to report, with its error.
            if (client === c) {
                client = null
                // The threads are on disk: they resume on the next connect.
                conversations.clear()
                opening.clear()
                byThread.clear()
                if (state != CodexState.Off) state = CodexState.Failed("Codex stopped.")
            }
        }
        return c
    }

    private fun stopServer() {
        client = null
        process?.let { p -> scope.launch(Dispatchers.IO) { killTree(p) } }
        process = null
    }

    private fun route(event: CodexEvent) {
        when (event.method) {
            "account/login/completed" ->
                state =
                    if (event.params.optBoolean("success")) {
                        CodexState.Ready
                    } else {
                        CodexState.SignIn(error = event.params.optString("error").ifEmpty { "Sign-in didn't complete." })
                    }
            else -> event.params.optString("threadId").takeIf { it.isNotEmpty() }?.let { byThread[it]?.apply(event) }
        }
    }

    /** Starts the device-code sign-in: the user opens the URL on any device and enters the code. */
    fun signIn() {
        val c = client ?: return connect()
        scope.launch {
            try {
                val login = c.request("account/login/start", JSONObject().put("type", "chatgptDeviceCode"))
                state = CodexState.SignIn(login.optString("userCode"), login.optString("verificationUrl"))
            } catch (e: CodexException) {
                state = CodexState.SignIn(error = e.message)
            }
        }
    }

    /** A new project for [prompt], and its first turn: Codex picks the stack and writes the app. */
    fun create(prompt: String): Project {
        val project = projects.create(prompt)
        scope.launch { send(project, prompt) }
        return project
    }

    fun delete(project: Project) {
        servers.remove(project.slug)?.stop()
        conversations.remove(project.slug)?.let { byThread.remove(it.threadId) }
        opening.remove(project.slug)
        scope.launch(Dispatchers.IO) { projects.delete(project) }
    }

    /** The project's conversation, its thread resumed (with its history) or started. */
    suspend fun open(project: Project): Conversation {
        conversations[project.slug]?.let { return it }
        val pending = opening.getOrPut(project.slug) { scope.async { openNow(project) } }
        return try {
            pending.await()
        } finally {
            if (opening[project.slug] === pending && pending.isCompleted) opening.remove(project.slug)
        }
    }

    private suspend fun openNow(project: Project): Conversation {
        val c = client ?: throw CodexException(-1, "Codex isn't running")
        val saved = projects.projects.firstOrNull { it.slug == project.slug } ?: project
        val resumed =
            saved.threadId?.let { id ->
                runCatching { c.request("thread/resume", threadParams(saved).put("threadId", id)) }
                    .onFailure { Log.w(TAG, "cannot resume ${saved.slug}'s thread, starting a new one", it) }
                    .getOrNull()
                    ?.let { id }
            }
        val threadId =
            resumed ?: c.request("thread/start", threadParams(saved)).getJSONObject("thread").getString("id").also {
                withContext(Dispatchers.IO) { projects.update(saved.copy(threadId = it)) }
            }
        val chat = Conversation(threadId, saved.guestDir) { server(saved).afterTurn() }
        if (resumed != null) {
            runCatching {
                val page = c.request("thread/turns/list", JSONObject().put("threadId", threadId).put("itemsView", "full").put("sortDirection", "desc").put("limit", 30))
                val turns = page.optJSONArray("data").objects().reversed()
                chat.load(JSONArray(turns))
            }.onFailure { Log.w(TAG, "cannot load ${saved.slug}'s history", it) }
        }
        conversations[saved.slug] = chat
        byThread[threadId] = chat
        return chat
    }

    suspend fun send(
        project: Project,
        text: String,
    ) {
        val chat =
            try {
                open(project)
            } catch (e: CodexException) {
                Log.w(TAG, "cannot open ${project.slug}", e)
                return
            }
        chat.sent(text)
        try {
            client?.request(
                "turn/start",
                JSONObject()
                    .put("threadId", chat.threadId)
                    .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text).put("text_elements", JSONArray()))),
            ) ?: throw CodexException(-1, "Codex isn't running")
        } catch (e: CodexException) {
            chat.failed(e.message ?: "Codex didn't take the message")
        }
    }

    fun interrupt(project: Project) {
        val chat = conversations[project.slug] ?: return
        val turn = chat.turnId ?: return
        scope.launch { runCatching { client?.request("turn/interrupt", JSONObject().put("threadId", chat.threadId).put("turnId", turn)) } }
    }

    /** The project's dev server; opening a project stops the others', since phones are short on memory. */
    fun server(project: Project): DevServer = servers.getOrPut(project.slug) { DevServer(proot, project, projects.dir(project), scope) }

    fun focus(project: Project) {
        for ((slug, server) in servers) if (slug != project.slug) server.stop()
        server(project).start()
    }

    private fun threadParams(project: Project) =
        JSONObject()
            .put("cwd", project.guestDir)
            .put("sandbox", "danger-full-access")
            .put("approvalPolicy", "never")
            .put("developerInstructions", INSTRUCTIONS)

    companion object {
        private const val TAG = "studio"

        /** A turn or an install that runs longer than this lets the phone sleep anyway, rather than drain it. */
        private const val WAKE_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        val CODEX = AGENTS.first { it.bin == "codex" }

        /** What Codex needs to know to build for the preview; the user's own wishes come in the turns. */
        val INSTRUCTIONS =
            """
            You are building a web app in Phonex, an Android app. You run in Arch Linux ARM (aarch64) under proot,
            as root, in the project's folder. The user is on a phone: they see your replies in a chat and the app in
            a live preview next to it. Keep replies short and in plain language, without code unless they ask.

            Phonex runs the dev server, not you:
            - Keep `.phonex/dev` an executable shell script that runs the app's dev server in the foreground,
              listening on 127.0.0.1 port ${'$'}PORT, for example `exec npx vite --host 127.0.0.1 --port "${'$'}PORT" --strictPort`.
              Write it in your first turn. Phonex starts it after your turn, restarts it when `.phonex/dev` or a
              top-level dependency file changes, and otherwise reloads the preview.
            - Never start a dev server or any other long-running process yourself, and never open a browser.
            - Install dependencies yourself before you finish the turn (npm install, pacman -S --needed --noconfirm).
              Node.js, npm and Python are installed. Keep dependencies few: storage and memory are tight.
            - Check the app builds or starts without errors (for example with a production build) before you finish.

            Design mobile-first: the preview is a phone screen. Unless asked otherwise, pick a simple stack, like
            plain HTML, CSS and JavaScript or Vite.
            """.trimIndent()
    }
}
