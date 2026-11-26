package com.music.bitchord.desktop

/** Oto runs on Windows only; the other flags stay so shared code reads the same. */
internal object DesktopPlatform {

    val isWindows: Boolean = true

    val isLinux: Boolean = false

    val isMac: Boolean = false

    /** Whether the application draws the window's own frame instead of the system drawing it. */
    val drawsOwnWindowFrame: Boolean = true
}

/** Oto's own folder: `%LOCALAPPDATA%\\Oto`. */
internal fun otoDataDir(): String {
    val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}\\AppData\\Local"
    return java.nio.file.Path.of(base, "Oto").toString()
}
