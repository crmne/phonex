package dev.omalauncher.studio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.omalauncher.App
import dev.omalauncher.studio.ui.StudioApp
import dev.omalauncher.studio.ui.StudioTheme

/** Phonex's own screen: projects, and each project's chat and preview. Linux windows still open as their own tasks. */
class StudioActivity : ComponentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restored for a backup or device transfer, the process has a plain Application; see MainActivity.
        if (application !is App) {
            finish()
            return
        }
        enableEdgeToEdge()
        app.runtime.start()
        setContent { StudioTheme { StudioApp(app.runtime, app.studio, onSetUp = ::setUp) } }
    }

    override fun onStart() {
        super.onStart()
        if (application is App) app.runtime.screenStarted()
    }

    override fun onStop() {
        if (application is App) app.runtime.screenStopped()
        super.onStop()
    }

    /** Installs Linux with Codex. The foreground service's notification shows the progress, so ask to post it. */
    private fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        app.runtime.finishWelcome()
        app.runtime.installLinux(listOf(Studio.CODEX))
    }
}
