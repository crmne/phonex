package dev.omalauncher.studio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.BufferedReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.Writer

class CodexClientTest {
    // A pipe that never gets its line blocks for good: fail instead.
    @get:Rule val timeout: Timeout = Timeout.seconds(20)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The fake server's ends: what it writes the client reads, and the other way round.
    private val toClient = PipedOutputStream()
    private val fromClient = PipedInputStream()
    private val server: Writer = toClient.bufferedWriter()
    private val serverReads: BufferedReader = fromClient.bufferedReader()

    private fun client(onServerRequest: suspend (String, JSONObject) -> JSONObject? = { _, _ -> null }) =
        CodexClient(PipedInputStream(toClient), PipedOutputStream(fromClient), scope, onServerRequest)

    private fun serverSays(line: String) {
        server.write(line + "\n")
        server.flush()
    }

    @After fun tearDown() = scope.cancel()

    @Test fun aRequestGetsTheResultWithItsId() =
        runBlocking {
            val client = client()
            val response = async(Dispatchers.Default) { client.request("thread/start", JSONObject().put("cwd", "/root/projects/a")) }
            val sent = JSONObject(serverReads.readLine())
            assertEquals("thread/start", sent.getString("method"))
            assertEquals("/root/projects/a", sent.getJSONObject("params").getString("cwd"))
            // A response to some other request first: it mustn't answer this one.
            serverSays("""{"id":999,"result":{"thread":{"id":"wrong"}}}""")
            serverSays("""{"id":${sent.getLong("id")},"result":{"thread":{"id":"t1"}}}""")
            assertEquals("t1", withTimeout(5000) { response.await() }.getJSONObject("thread").getString("id"))
        }

    @Test fun anErrorResponseThrowsWithItsMessage() =
        runBlocking {
            val client = client()
            val response = async(Dispatchers.Default) { runCatching { client.request("turn/start") } }
            val id = JSONObject(serverReads.readLine()).getLong("id")
            serverSays("""{"id":$id,"error":{"code":-32600,"message":"thread not found"}}""")
            val error = withTimeout(5000) { response.await() }.exceptionOrNull() as CodexException
            assertEquals(-32600, error.code)
            assertEquals("thread not found", error.message)
        }

    @Test fun notificationsArriveInOrderAndProotNoiseIsSkipped() =
        runBlocking {
            val client = client()
            val events = async(Dispatchers.Default) { client.events.take(2).toList() }
            // Lets the collector subscribe before the server talks.
            Thread.sleep(100)
            serverSays("proot warning: can't sanitize binding \"/proc/self/fd/1\"")
            serverSays("""{"method":"item/agentMessage/delta","params":{"delta":"Hel"}}""")
            serverSays("""{"method":"item/agentMessage/delta","params":{"delta":"lo"}}""")
            val got = withTimeout(5000) { events.await() }
            assertEquals(listOf("Hel", "lo"), got.map { it.params.getString("delta") })
        }

    @Test fun aServerRequestIsAnsweredWithTheHandlersResult() =
        runBlocking {
            client { method, params ->
                if (method == "item/commandExecution/requestApproval") JSONObject().put("decision", "accept").put("for", params.getString("itemId")) else null
            }
            serverSays("""{"id":"a1","method":"item/commandExecution/requestApproval","params":{"itemId":"i1"}}""")
            val reply = JSONObject(withTimeout(5000) { serverReads.readLine() })
            assertEquals("a1", reply.getString("id"))
            assertEquals("accept", reply.getJSONObject("result").getString("decision"))
            assertEquals("i1", reply.getJSONObject("result").getString("for"))
        }

    @Test fun anUnknownServerRequestIsDeclinedSoTheTurnDoesNotHang() =
        runBlocking {
            client()
            serverSays("""{"id":7,"method":"item/tool/requestUserInput","params":{}}""")
            val reply = JSONObject(withTimeout(5000) { serverReads.readLine() })
            assertEquals(7, reply.getInt("id"))
            assertEquals(-32601, reply.getJSONObject("error").getInt("code"))
        }

    @Test fun aServerThatExitsFailsTheRequestsWaitingOnIt() =
        runBlocking {
            val client = client()
            val response = async(Dispatchers.Default) { runCatching { client.request("turn/start") } }
            serverReads.readLine()
            server.close()
            val error = withTimeout(5000) { response.await() }.exceptionOrNull()
            assertTrue("expected CodexException, got $error", error is CodexException)
            withTimeout(5000) { client.closed.await() }
            try {
                client.request("thread/start")
                fail("a closed client accepted a request")
            } catch (_: CodexException) {
            }
        }

    @Test fun theRecordedTurnParsesAsNotifications() =
        runBlocking {
            val client = client()
            val lines = javaClass.getResource("/codex/turn.jsonl")!!.readText().lines().filter { it.isNotBlank() }
            val last = async(Dispatchers.Default) { client.events.first { it.method == "turn/completed" } }
            Thread.sleep(100)
            lines.forEach(::serverSays)
            assertEquals("completed", withTimeout(5000) { last.await() }.params.getJSONObject("turn").getString("status"))
        }
}
