package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The few lines a resolve has to say about itself. */
internal object DesktopTrackLog {

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** `%LOCALAPPDATA%\\Oto\\oto.log`. */
    private val file: Path? by lazy {
        runCatching {
            val directory = Path.of(otoDataDir())
            Files.createDirectories(directory)
            directory.resolve("oto.log")
        }.getOrNull()
    }

    fun log(line: String) {
        val stamped = "${LocalTime.now().format(clock)}  $line"
        println("Oto: $stamped")
        val target = file ?: return
        runCatching {
            Files.writeString(
                target,
                stamped + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
}
