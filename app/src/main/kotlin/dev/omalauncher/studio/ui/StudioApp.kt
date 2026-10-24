package dev.omalauncher.studio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.studio.CodexState
import dev.omalauncher.studio.Project
import dev.omalauncher.studio.Studio
import java.text.DateFormat
import java.util.Date

/** Phonex's screens, from setting up Linux to a project's chat and preview. */
@Composable
fun StudioApp(
    runtime: Runtime,
    studio: Studio,
    onSetUp: () -> Unit,
) {
    val linux by runtime.bootstrap.state.collectAsState()
    LaunchedEffect(runtime.linux) { if (runtime.linux) studio.start() }
    val c = colors
    Box(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        when {
            linux != LinuxState.Installed -> SetupScreen(linux, onSetUp, runtime::retryLinux)
            else ->
                when (val state = studio.state) {
                    CodexState.Ready -> Projects(studio)
                    is CodexState.SignIn -> SignInScreen(state, studio::signIn)
                    is CodexState.Failed -> Centered("Codex isn't running", state.message) { PillButton("Try again", studio::connect) }
                    CodexState.Installing -> Centered("Installing Codex", "This takes a minute or two.")
                    CodexState.Starting, CodexState.Off -> Centered("Starting Codex", null)
                }
        }
    }
}

@Composable
private fun Projects(studio: Studio) {
    var openSlug by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openSlug?.let { slug -> studio.projects.projects.firstOrNull { it.slug == slug } }
    if (open != null) {
        BackHandler { openSlug = null }
        ProjectScreen(studio, open, onBack = { openSlug = null })
    } else {
        ProjectList(studio, onOpen = { openSlug = it.slug }, onCreate = { prompt -> openSlug = studio.create(prompt).slug })
    }
}

@Composable
private fun ProjectList(
    studio: Studio,
    onOpen: (Project) -> Unit,
    onCreate: (String) -> Unit,
) {
    val c = colors
    var confirmDelete by remember { mutableStateOf<Project?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
            item {
                Label("Phonex", style = Type.title)
                Spacer(Modifier.height(4.dp))
                Label("Describe a web app. Codex builds it on this phone, and you watch it run.", style = Type.small, color = c.secondary)
                Spacer(Modifier.height(24.dp))
            }
            if (studio.projects.projects.isNotEmpty()) {
                item {
                    Label("Your apps", style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.faint)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(studio.projects.projects, key = { it.slug }) { project ->
                val deleting = confirmDelete?.slug == project.slug
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { if (deleting) confirmDelete = null else onOpen(project) }, onLongClick = { confirmDelete = project })
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(project.name, style = Type.body, maxLines = 1)
                        Label(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.created)), style = Type.caption, color = c.faint)
                    }
                    if (deleting) {
                        PillButton("Delete", { studio.delete(project).also { confirmDelete = null } }, primary = false)
                    } else {
                        IconButton(StudioIcons.Chevron, { onOpen(project) }, tint = c.faint, size = 32.dp)
                    }
                }
            }
        }
        Composer(
            placeholder = "What do you want to build?",
            running = false,
            onSend = onCreate,
            onStop = {},
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** The message box: a rounded field with send, or stop while a turn runs. */
@Composable
fun Composer(
    placeholder: String,
    running: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = colors
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Label(placeholder, style = Type.body, color = c.faint)
            BasicTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth().heightIn(max = 160.dp),
                enabled = enabled,
                textStyle = Type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
            )
        }
        Spacer(Modifier.size(6.dp))
        if (running) {
            IconButton(StudioIcons.Stop, onStop, tint = c.onAccent, background = c.accent)
        } else {
            IconButton(
                StudioIcons.Send,
                {
                    val message = text.trim()
                    if (message.isNotEmpty()) {
                        text = ""
                        onSend(message)
                    }
                },
                tint = c.onAccent,
                background = c.accent,
                enabled = enabled && text.isNotBlank(),
            )
        }
    }
}

@Composable
private fun SetupScreen(
    linux: LinuxState,
    onSetUp: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = colors
    when (linux) {
        LinuxState.NotInstalled ->
            Centered(
                "Phonex",
                "Build web apps on your phone with Codex. Phonex sets up a small Arch Linux system for Codex, Node.js and " +
                    "Python to run in. The system alone is an 830 MB download, so use Wi-Fi.",
            ) { PillButton("Set up", onSetUp) }
        is LinuxState.Installing ->
            Centered("Setting up", "${linux.stage.label.replaceFirstChar { it.uppercase() }}: ${linux.step}") {
                val progress = linux.progress
                if (progress != null) {
                    Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surface)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(c.accent))
                    }
                }
                linux.log.lastOrNull()?.let { Label(it, style = Type.code, color = c.faint, maxLines = 2) }
            }
        is LinuxState.Failed ->
            Centered("Setup stopped", linux.message) {
                linux.log.takeLast(4).forEach { Label(it, style = Type.code, color = c.faint, maxLines = 2) }
                Spacer(Modifier.height(8.dp))
                PillButton("Try again", onRetry)
            }
        LinuxState.Checking, LinuxState.Installed -> Centered("Starting", null)
        LinuxState.Removing -> Centered("Removing Linux", null)
    }
}

@Composable
private fun SignInScreen(
    state: CodexState.SignIn,
    onSignIn: () -> Unit,
) {
    val c = colors
    val context = LocalContext.current
    val code = state.code
    if (code == null) {
        Centered("Sign in to Codex", state.error ?: "Use your ChatGPT account. Codex runs on this phone, with your plan's usage.") {
            PillButton("Sign in with ChatGPT", onSignIn)
        }
    } else {
        Centered("Enter this code", "Open the page below, sign in, and enter the code. This screen moves on by itself.") {
            Label(code, style = Type.title.copy(fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 2.sp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Copy code", { copy(context, code) }, primary = false)
                state.url?.let { url -> PillButton("Open page", { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) }
            }
            state.url?.let { Label(it, style = Type.caption, color = c.faint) }
        }
    }
}

@Composable
private fun Centered(
    title: String,
    body: String?,
    content: @Composable () -> Unit = {},
) {
    val c = colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(title, style = Type.title, modifier = Modifier.widthIn(max = 420.dp))
        body?.let { BodyText(it, Modifier.widthIn(max = 420.dp)) }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
) = androidx.compose.foundation.text.BasicText(text, modifier, Type.small.copy(color = colors.secondary, textAlign = TextAlign.Center))

private fun copy(
    context: Context,
    text: String,
) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", text))
}
