package dev.omalauncher.studio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A notification from the app server, like `item/agentMessage/delta`. */
data class CodexEvent(
    val method: String,
    val params: JSONObject,
)

/** A JSON-RPC error the app server answered a request with, or the server going away mid-request. */
class CodexException(
    val code: Int,
    message: String,
) : Exception(message)

/**
 * Speaks Codex's app server protocol (`codex app-server`): JSON-RPC 2.0, one message per line, over the
 * process's stdin and stdout. Requests suspend until their response; notifications go to [events] in the
 * order they arrived. The server's own requests (approvals, user input) go to [onServerRequest], whose
 * result is sent back; null declines with "method not found".
 *
 * Lines that aren't JSON are skipped: proot prints its warnings on the same terminal.
 */
class CodexClient(
    input: InputStream,
    output: OutputStream,
    scope: CoroutineScope,
    private val onServerRequest: suspend (method: String, params: JSONObject) -> JSONObject? = { _, _ -> null },
) {
    private val writer = output.bufferedWriter()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    // Never drops: a slow collector holds up reading instead, and with it the server, rather than losing a delta.
    private val _events = MutableSharedFlow<CodexEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<CodexEvent> = _events

    /** Completes when the server's stdout ends: it exited or was killed. */
    val closed = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                input.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val message = parse(line) ?: continue
                        dispatch(message, scope)
                    }
                }
            } catch (_: IOException) {
                // The pipe closed under us: same as the end of the stream.
            } finally {
                val gone = CodexException(-1, "codex app-server exited")
                pending.values.forEach { it.completeExceptionally(gone) }
                pending.clear()
                closed.complete(Unit)
            }
        }
    }

    /** Sends [method] and waits for its result. Throws [CodexException] for an error response or a closed server. */
    suspend fun request(
        method: String,
        params: JSONObject = JSONObject(),
    ): JSONObject {
        if (closed.isCompleted) throw CodexException(-1, "codex app-server exited")
        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        try {
            send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        } catch (e: IOException) {
            pending.remove(id)
            throw CodexException(-1, "codex app-server exited: ${e.message}")
        }
        return response.await()
    }

    /** A notification from the client, like `initialized`: no response comes. */
    suspend fun notify(
        method: String,
        params: JSONObject = JSONObject(),
    ) = send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    private suspend fun send(message: JSONObject) =
        withContext(Dispatchers.IO) {
            synchronized(writer) {
                writer.write(message.toString())
                writer.write("\n")
                writer.flush()
            }
        }

    private suspend fun dispatch(
        message: JSONObject,
        scope: CoroutineScope,
    ) {
        val method = message.optString("method").takeIf { message.has("method") }
        val id = if (message.has("id") && !message.isNull("id")) message.get("id") else null
        when {
            method != null && id != null -> scope.launch { answer(id, method, message.optJSONObject("params") ?: JSONObject()) }
            method != null -> _events.emit(CodexEvent(method, message.optJSONObject("params") ?: JSONObject()))
            id != null -> {
                val waiting = pending.remove((id as? Number)?.toLong() ?: id.toString().toLongOrNull() ?: return) ?: return
                val error = message.optJSONObject("error")
                if (error != null) {
                    waiting.completeExceptionally(CodexException(error.optInt("code"), error.optString("message")))
                } else {
                    waiting.complete(message.optJSONObject("result") ?: JSONObject())
                }
            }
        }
    }

    private suspend fun answer(
        id: Any,
        method: String,
        params: JSONObject,
    ) {
        val result = runCatching { onServerRequest(method, params) }.getOrNull()
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", id)
        if (result != null) {
            reply.put("result", result)
        } else {
            reply.put("error", JSONObject().put("code", -32601).put("message", "$method is not supported by Phonex"))
        }
        runCatching { send(reply) }
    }

    private fun parse(line: String): JSONObject? =
        line.trim().takeIf { it.startsWith("{") }?.let {
            try {
                JSONObject(it)
            } catch (_: JSONException) {
                null
            }
        }

    /** Closes stdin, which ends the server; for tests and for a session being let go. */
    fun close() {
        runCatching { synchronized(writer) { writer.close() } }
    }
}
