package dev.omalauncher.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** What the chat shows: the subset of Codex's thread items a phone screen has room for. */
sealed interface ChatItem {
    val id: String

    data class User(
        override val id: String,
        val text: String,
    ) : ChatItem

    /** [final] is the turn's answer; the rest is commentary while it works, shown quieter. */
    data class Agent(
        override val id: String,
        val text: String,
        val final: Boolean,
    ) : ChatItem

    data class Edit(
        override val id: String,
        val files: List<FileEdit>,
        val status: Status,
    ) : ChatItem

    data class Command(
        override val id: String,
        val command: String,
        val output: String,
        val status: Status,
        val exitCode: Int?,
    ) : ChatItem

    data class Notice(
        override val id: String,
        val text: String,
        val error: Boolean,
    ) : ChatItem
}

/** One file of an edit, its path relative to the project. */
data class FileEdit(
    val path: String,
    val kind: String,
)

enum class Status { Running, Done, Failed }

/**
 * One project's thread, kept as [items] for the chat. Live events ([apply]) and saved history ([load])
 * go through the same item parsing, so a reopened project looks like it did.
 *
 * [onTurnCompleted] gets the paths edited during the turn, for the dev server to decide whether to restart.
 */
class Conversation(
    val threadId: String,
    private val projectDir: String,
    private val onTurnCompleted: (edited: Set<String>) -> Unit = {},
) {
    val items = mutableStateListOf<ChatItem>()

    /** A turn is running: the composer offers to stop it instead of sending. */
    var running by mutableStateOf(false)
        private set

    private val edited = mutableSetOf<String>()
    private var notices = 0

    /** Shows [text] at once, before Codex echoes it back as the turn's user message. */
    fun sent(text: String) {
        running = true
        put(ChatItem.User(PENDING, text))
    }

    fun apply(event: CodexEvent) {
        val params = event.params
        if (params.optString("threadId").let { it.isNotEmpty() && it != threadId }) return
        when (event.method) {
            "turn/started" -> {
                running = true
                edited.clear()
            }
            "item/started", "item/completed" -> params.optJSONObject("item")?.let(::item)?.let(::put)
            "item/agentMessage/delta" -> append(params.optString("itemId"), params.optString("delta"))
            "item/commandExecution/outputDelta" -> appendOutput(params.optString("itemId"), params.optString("delta"))
            "error" -> {
                val error = params.optJSONObject("error")
                if (!params.optBoolean("willRetry")) notice(error?.optString("message") ?: "Codex failed", error = true)
            }
            "turn/completed" -> {
                running = false
                val turn = params.optJSONObject("turn")
                turn?.optJSONObject("error")?.let { notice(it.optString("message"), error = true) }
                if (turn?.optString("status") == "interrupted") notice("Stopped", error = false)
                onTurnCompleted(edited.toSet())
            }
        }
    }

    /** The thread's saved turns (`thread/turns/list` with full items), oldest first. */
    fun load(turns: JSONArray) {
        items.clear()
        for (t in 0 until turns.length()) {
            val list = turns.optJSONObject(t)?.optJSONArray("items") ?: continue
            for (i in 0 until list.length()) list.optJSONObject(i)?.let(::item)?.let(::put)
        }
    }

    fun notice(
        text: String,
        error: Boolean,
    ) = put(ChatItem.Notice("notice-${notices++}", text, error))

    private fun put(item: ChatItem) {
        // Codex echoes the user's message with its own id: it takes the place of the one shown on send.
        if (item is ChatItem.User) {
            val pending = items.indexOfFirst { it.id == PENDING && (it as ChatItem.User).text == item.text }
            if (pending >= 0) {
                items[pending] = item
                return
            }
        }
        if (item is ChatItem.Edit) edited += item.files.map { it.path }
        val at = items.indexOfFirst { it.id == item.id }
        if (at >= 0) items[at] = item else items += item
    }

    private fun append(
        id: String,
        delta: String,
    ) {
        val at = items.indexOfFirst { it.id == id }
        val current = items.getOrNull(at) as? ChatItem.Agent ?: return
        items[at] = current.copy(text = current.text + delta)
    }

    private fun appendOutput(
        id: String,
        delta: String,
    ) {
        val at = items.indexOfFirst { it.id == id }
        val current = items.getOrNull(at) as? ChatItem.Command ?: return
        items[at] = current.copy(output = (current.output + delta).takeLast(OUTPUT_TAIL))
    }

    private fun item(json: JSONObject): ChatItem? {
        val id = json.optString("id")
        return when (json.optString("type")) {
            "userMessage" -> ChatItem.User(id, userText(json.optJSONArray("content")))
            "agentMessage" -> ChatItem.Agent(id, json.optString("text"), json.optString("phase") == "final_answer")
            "fileChange" ->
                ChatItem.Edit(
                    id,
                    json.optJSONArray("changes").objects().map {
                        FileEdit(relative(it.optString("path")), it.optJSONObject("kind")?.optString("type") ?: "update")
                    },
                    status(json.optString("status")),
                )
            "commandExecution" ->
                ChatItem.Command(
                    id,
                    json.optString("command"),
                    json.optString("aggregatedOutput").takeIf { !json.isNull("aggregatedOutput") }.orEmpty().takeLast(OUTPUT_TAIL),
                    status(json.optString("status")),
                    if (json.isNull("exitCode") || !json.has("exitCode")) null else json.optInt("exitCode"),
                )
            else -> null
        }
    }

    private fun userText(content: JSONArray?) = content.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }

    private fun relative(path: String) = path.removePrefix(projectDir.trimEnd('/') + "/")

    private fun status(value: String) =
        when (value) {
            "inProgress" -> Status.Running
            "completed" -> Status.Done
            else -> Status.Failed
        }

    private companion object {
        const val PENDING = "pending-user-message"

        /** A dev command can print for minutes; the chat keeps the end, which is where the error is. */
        const val OUTPUT_TAIL = 4000
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
