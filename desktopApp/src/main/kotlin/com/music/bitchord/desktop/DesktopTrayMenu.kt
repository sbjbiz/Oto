package com.music.bitchord.desktop

import java.awt.EventQueue

/** The tray menu: what the Windows tray icon's right-click popup shows. */
internal object DesktopTrayMenu {

    private const val ID_NOW_PLAYING = 1
    private const val ID_SEPARATOR_TOP = 2
    private const val ID_PLAY_PAUSE = 3
    private const val ID_NEXT = 4
    private const val ID_PREVIOUS = 5
    private const val ID_SEPARATOR_MIDDLE = 6
    private const val ID_OPEN_PLAYER = 7
    private const val ID_SETTINGS = 8
    private const val ID_SEPARATOR_BOTTOM = 9
    private const val ID_QUIT = 10

    private val lock = Any()

    private var nowPlaying: String? = null
    private var isPlaying = false
    private var revision = 1

    private var playPause: () -> Unit = {}
    private var next: () -> Unit = {}
    private var previous: () -> Unit = {}
    private var openPlayer: () -> Unit = {}
    private var openSettings: () -> Unit = {}
    private var quit: () -> Unit = {}

    /** Told to the tray so it rebuilds its popup. */
    private var onLayoutChanged: (Int) -> Unit = {}

    fun bind(
        onPlayPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        onOpenPlayer: () -> Unit,
        onOpenSettings: () -> Unit,
        onQuit: () -> Unit,
    ) = synchronized(lock) {
        playPause = onPlayPause
        next = onNext
        previous = onPrevious
        openPlayer = onOpenPlayer
        openSettings = onOpenSettings
        quit = onQuit
    }

    fun onLayoutChanged(listener: (Int) -> Unit) = synchronized(lock) { onLayoutChanged = listener }

    /** Keeps the menu's text in step with playback; silent when nothing moved. */
    fun publish(title: String?, playing: Boolean) {
        val next = synchronized(lock) {
            if (title == nowPlaying && playing == isPlaying) return
            nowPlaying = title
            isPlaying = playing
            ++revision
        }
        onLayoutChanged(next)
    }

    fun revision(): Int = synchronized(lock) { revision }

    /** The menu as it stands. */
    private fun entries(): List<Entry> = synchronized(lock) {
        buildList {
            nowPlaying?.let { playing ->
                add(Entry(ID_NOW_PLAYING, label = playing, enabled = false))
                add(Entry(ID_SEPARATOR_TOP, separator = true))
            }
            add(
                Entry(
                    ID_PLAY_PAUSE,
                    label = if (isPlaying) "Pause" else "Play",
                    icon = if (isPlaying) "media-playback-pause" else "media-playback-start",
                    action = playPause,
                ),
            )
            add(Entry(ID_NEXT, label = DesktopStrings["widget_next", "Next"], icon = "media-skip-forward", action = next))
            add(Entry(ID_PREVIOUS, label = DesktopStrings["widget_previous", "Previous"], icon = "media-skip-backward", action = previous))
            add(Entry(ID_SEPARATOR_MIDDLE, separator = true))
            add(Entry(ID_OPEN_PLAYER, label = DesktopStrings["widget_open_oto", "Open Oto"], icon = "media-playback-start", action = openPlayer))
            add(Entry(ID_SETTINGS, label = DesktopStrings["settings", "Settings"], icon = "preferences-system", action = openSettings))
            add(Entry(ID_SEPARATOR_BOTTOM, separator = true))
            add(Entry(ID_QUIT, label = DesktopStrings["d_quit_oto", "Quit Oto"], icon = "application-exit", action = quit))
        }
    }

    /** The same menu, as AWT's own widgets. */
    fun awtPopup(): java.awt.PopupMenu = java.awt.PopupMenu().apply {
        entries().forEach { entry ->
            if (entry.separator) {
                addSeparator()
            } else {
                add(
                    java.awt.MenuItem(entry.label).apply {
                        isEnabled = entry.enabled
                        val action = entry.action
                        if (entry.enabled) addActionListener { EventQueue.invokeLater(action) }
                    },
                )
            }
        }
    }

    private data class Entry(
        val id: Int,
        val label: String = "",
        val separator: Boolean = false,
        val enabled: Boolean = true,
        val icon: String? = null,
        val action: (() -> Unit)? = null,
    )
}
