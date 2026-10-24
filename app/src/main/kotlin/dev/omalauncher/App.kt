package dev.omalauncher

import android.app.Application
import dev.omalauncher.studio.Studio

class App : Application() {
    val runtime by lazy { Runtime(this) }
    val studio by lazy { Studio(runtime, this) }
}
