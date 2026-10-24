package dev.omalauncher.studio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private val recorded =
        javaClass.getResource("/codex/turn.jsonl")!!.readText().lines().filter { it.isNotBlank() }.map {
            val json = JSONObject(it)
            CodexEvent(json.getString("method"), json.getJSONObject("params"))
        }
    private val threadId = recorded.first { it.method == "turn/started" }.params.getString("threadId")

    @Test fun aRecordedTurnBecomesTheChat() {
        var completed = 0
        val chat = Conversation(threadId, "/root/projects/hello") { completed++ }
        chat.sent("Create index.html with a one-line hello page, run ls to check it exists, then reply 'done'.")
        assertTrue(chat.running)
        recorded.forEach(chat::apply)

        assertFalse(chat.running)
        assertEquals(1, completed)
        val items = chat.items.toList()
        // The message shown on send was replaced by Codex's, not shown twice.
        assertEquals(1, items.count { it is ChatItem.User })
        val agent = items.filterIsInstance<ChatItem.Agent>()
        assertEquals("I’ll create `index.html` and check that it exists.", agent.first().text)
        assertFalse(agent.first().final)
        assertEquals("done", agent.last().text)
        assertTrue(agent.last().final)
        val commands = items.filterIsInstance<ChatItem.Command>()
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.status == Status.Done && it.exitCode == 0 })
    }

    @Test fun anotherThreadsEventsAreIgnored() {
        val chat = Conversation("someone-else", "/root/projects/hello")
        recorded.forEach(chat::apply)
        assertTrue(chat.items.isEmpty())
    }

    @Test fun editsShowPathsInTheProject() {
        val chat = Conversation("t", "/root/projects/hello")
        chat.apply(event("turn/started", """{"threadId":"t","turn":{"id":"u"}}"""))
        chat.apply(
            event(
                "item/completed",
                """{"threadId":"t","item":{"type":"fileChange","id":"e1","status":"completed","changes":[
                    {"path":"/root/projects/hello/.phonex/dev","kind":{"type":"add"},"diff":"exec vite"},
                    {"path":"/root/projects/hello/src/App.jsx","kind":{"type":"update","move_path":null},"diff":"-a\n+b"}]}}""",
            ),
        )
        chat.apply(event("turn/completed", """{"threadId":"t","turn":{"id":"u","status":"completed","error":null}}"""))
        val edit = chat.items.single() as ChatItem.Edit
        assertEquals(listOf(FileEdit(".phonex/dev", "add"), FileEdit("src/App.jsx", "update")), edit.files)
    }

    @Test fun aFailedTurnSaysWhy() {
        val chat = Conversation("t", "/root/projects/hello")
        chat.apply(event("error", """{"threadId":"t","willRetry":true,"error":{"message":"reconnecting"}}"""))
        chat.apply(event("error", """{"threadId":"t","willRetry":false,"error":{"message":"usage limit reached"}}"""))
        chat.apply(event("turn/completed", """{"threadId":"t","turn":{"id":"u","status":"interrupted","error":null}}"""))
        assertEquals(
            listOf("usage limit reached" to true, "Stopped" to false),
            chat.items.map { (it as ChatItem.Notice).text to it.error },
        )
    }

    @Test fun historyLoadsLikeTheLiveTurn() {
        val chat = Conversation("t", "/root/projects/hello")
        chat.load(
            JSONArray(
                """[{"id":"u1","items":[
                    {"type":"userMessage","id":"m1","clientId":null,"content":[{"type":"text","text":"make a todo app","text_elements":[]}]},
                    {"type":"reasoning","id":"r1","summary":[],"content":[]},
                    {"type":"agentMessage","id":"m2","text":"Done: a todo app.","phase":"final_answer"}]}]""",
            ),
        )
        assertEquals(
            listOf(ChatItem.User("m1", "make a todo app"), ChatItem.Agent("m2", "Done: a todo app.", final = true)),
            chat.items.toList(),
        )
    }

    private fun event(
        method: String,
        params: String,
    ) = CodexEvent(method, JSONObject(params))
}
