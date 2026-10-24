package dev.omalauncher.studio.ui

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.net.toUri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.studio.ChatItem
import dev.omalauncher.studio.CodexException
import dev.omalauncher.studio.Conversation
import dev.omalauncher.studio.DevServer
import dev.omalauncher.studio.Project
import dev.omalauncher.studio.ServerState
import dev.omalauncher.studio.Status
import dev.omalauncher.studio.Studio
import kotlinx.coroutines.launch

/**
 * A project: the chat with Codex and the running app. Side by side when there's room (a tablet, a
 * foldable, a phone on its side), otherwise one at a time with a switch in the top bar.
 */
@Composable
fun ProjectScreen(
    studio: Studio,
    project: Project,
    onBack: () -> Unit,
) {
    var chat by remember(project.slug) { mutableStateOf<Conversation?>(null) }
    var error by remember(project.slug) { mutableStateOf<String?>(null) }
    LaunchedEffect(project.slug) {
        studio.focus(project)
        try {
            chat = studio.open(project)
        } catch (e: CodexException) {
            error = e.message
        }
    }
    val server = studio.server(project)
    var tab by rememberSaveable(project.slug) { mutableIntStateOf(0) }
    val c = colors
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(StudioIcons.Back, onBack)
                Label(project.name, style = Type.heading, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
                if (!wide) Segmented(listOf("Chat", "Preview"), tab, { tab = it }, Modifier.padding(end = 8.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            if (wide) {
                Row(Modifier.weight(1f)) {
                    ChatPane(studio, project, chat, error, Modifier.weight(0.42f).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
                    PreviewPane(studio, project, server, Modifier.weight(0.58f).fillMaxHeight())
                }
            } else if (tab == 0) {
                ChatPane(studio, project, chat, error, Modifier.weight(1f))
            } else {
                PreviewPane(studio, project, server, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChatPane(
    studio: Studio,
    project: Project,
    chat: Conversation?,
    error: String?,
    modifier: Modifier,
) {
    val c = colors
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val items = chat?.items.orEmpty()
    // Follows the reply as it streams in.
    val lastLength = (items.lastOrNull() as? ChatItem.Agent)?.text?.length ?: 0
    LaunchedEffect(items.size, lastLength) { if (items.isNotEmpty()) list.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> Hint("Couldn't open the chat", error)
                chat == null -> Hint("Opening", null)
                items.isEmpty() -> Hint("What should Codex build?", "Describe the app, or what to change. Codex replies here and the preview follows.")
                else ->
                    LazyColumn(Modifier.fillMaxSize(), list, PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(items, key = { it.id }) { ChatRow(it) }
                        if (chat.running) item(key = "working") { Label("Working…", style = Type.small, color = c.faint) }
                    }
            }
        }
        Composer(
            placeholder = "Ask for a change",
            running = chat?.running == true,
            enabled = chat != null,
            onSend = { text -> scope.launch { studio.send(project, text) } },
            onStop = { studio.interrupt(project) },
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    val c = colors
    when (item) {
        is ChatItem.User ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Label(item.text, style = Type.body)
                }
            }
        is ChatItem.Agent ->
            BasicText(
                markdown(item.text, c.surface),
                style = (if (item.final) Type.body else Type.small).copy(color = if (item.final) c.text else c.secondary),
            )
        is ChatItem.Edit -> {
            val names = item.files.map { it.path.substringAfterLast('/') }.distinct()
            val what = if (names.size <= 3) names.joinToString(", ") else "${names.size} files"
            Activity(StudioIcons.Edit, if (item.status == Status.Running) "Editing $what" else "Edited $what", item.status == Status.Failed)
        }
        is ChatItem.Command -> CommandRow(item)
        is ChatItem.Notice ->
            BasicText(item.text, Modifier.fillMaxWidth(), Type.caption.copy(color = if (item.error) c.bad else c.faint, textAlign = TextAlign.Center))
    }
}

@Composable
private fun CommandRow(item: ChatItem.Command) {
    val c = colors
    var expanded by remember(item.id) { mutableStateOf(false) }
    val failed = item.status == Status.Failed || (item.exitCode ?: 0) != 0
    val verb =
        when {
            item.status == Status.Running -> "Running"
            failed -> "Failed"
            else -> "Ran"
        }
    Column(Modifier.fillMaxWidth()) {
        Activity(StudioIcons.Terminal, "$verb ${shownCommand(item.command)}", failed, Modifier.clickable { expanded = !expanded })
        if (expanded && item.output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                item.output.trimEnd(),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(10.dp),
                Type.code.copy(color = c.secondary),
            )
        }
    }
}

@Composable
private fun Activity(
    icon: ImageVector,
    text: String,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val tint = if (failed) c.bad else c.faint
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(rememberVectorPainter(icon), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(tint))
        Spacer(Modifier.width(8.dp))
        Label(text, style = Type.caption, color = tint, maxLines = 1)
    }
}

@Composable
private fun PreviewPane(
    studio: Studio,
    project: Project,
    server: DevServer,
    modifier: Modifier,
) {
    val c = colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloads by remember { mutableIntStateOf(0) }
    Column(modifier.background(c.background)) {
        when (val state = server.state) {
            is ServerState.Running -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(c.good)
                    Spacer(Modifier.width(8.dp))
                    Label(state.url.removePrefix("http://").trimEnd('/'), style = Type.caption, color = c.secondary, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(StudioIcons.Reload, { reloads++ }, tint = c.secondary, size = 36.dp)
                    IconButton(StudioIcons.OpenOutside, { context.startActivity(Intent(Intent.ACTION_VIEW, state.url.toUri())) }, tint = c.secondary, size = 36.dp)
                }
                WebPreview(state.url, server.generation + reloads, Modifier.weight(1f).fillMaxWidth())
            }
            is ServerState.Starting ->
                Hint("Starting the app", null, Modifier.weight(1f)) { Log(state.log) }
            ServerState.NoScript ->
                Hint("Your app shows up here", "Once Codex has built it, Phonex starts it and keeps it running.", Modifier.weight(1f))
            ServerState.Stopped ->
                Hint("The app isn't running", null, Modifier.weight(1f)) { PillButton("Start", server::start) }
            is ServerState.Crashed ->
                Hint("The app stopped", "Its dev server exited with code ${state.code}.", Modifier.weight(1f)) {
                    Log(state.log)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Restart", server::restart, primary = false)
                        PillButton("Ask Codex to fix it", {
                            scope.launch { studio.send(project, fixPrompt(state)) }
                        })
                    }
                }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPreview(
    url: String,
    generation: Int,
    modifier: Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Links stay in the preview rather than opening a browser.
                webViewClient = WebViewClient()
            }
        },
        modifier = modifier,
        update = { view ->
            val key = "$url#$generation"
            if (view.tag != key) {
                val sameUrl = (view.tag as? String)?.substringBefore('#') == url
                view.tag = key
                if (sameUrl) view.reload() else view.loadUrl(url)
            }
        },
    )
}

@Composable
private fun Log(lines: List<String>) {
    val c = colors
    if (lines.isEmpty()) return
    BasicText(
        lines.takeLast(12).joinToString("\n"),
        Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(10.dp),
        Type.code.copy(color = c.secondary),
    )
}

@Composable
private fun Hint(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val c = colors
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(title, style = Type.heading)
        body?.let { BasicText(it, Modifier.widthIn(max = 360.dp), Type.small.copy(color = c.secondary, textAlign = TextAlign.Center)) }
        content()
    }
}

private fun fixPrompt(state: ServerState.Crashed) =
    "The app's dev server (.phonex/dev) exited with code ${state.code}. The end of its output:\n\n" +
        state.log.joinToString("\n") + "\n\nPlease fix it."

/** `/bin/bash -lc 'npm install'` -> `npm install`: Codex wraps every command in a login shell. */
internal fun shownCommand(command: String): String {
    val wrapped = Regex("""^(?:\S*/)?(?:ba|z)?sh -l?c (['"])(.*)\1$""", RegexOption.DOT_MATCHES_ALL).find(command.trim())
    return (wrapped?.groupValues?.get(2) ?: command).trim().lines().first()
}

/** Codex's replies are Markdown; on a phone, inline code and bold carry most of it. */
internal fun markdown(
    text: String,
    codeBackground: Color,
): AnnotatedString =
    buildAnnotatedString {
        val pattern = Regex("""`([^`]+)`|\*\*([^*]+)\*\*""")
        for ((n, raw) in text.lines().withIndex()) {
            if (n > 0) append('\n')
            val line = raw.replace(Regex("""^(\s*)[-*] """), "$1• ").replace(Regex("""^#{1,6} """), "")
            var at = 0
            for (match in pattern.findAll(line)) {
                append(line.substring(at, match.range.first))
                val (code, bold) = match.destructured
                if (code.isNotEmpty()) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(code) }
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
                }
                at = match.range.last + 1
            }
            append(line.substring(at))
        }
    }
