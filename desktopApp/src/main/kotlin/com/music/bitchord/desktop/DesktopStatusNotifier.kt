package com.music.bitchord.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The tray icon on Windows: AWT's system tray, with the menu from [DesktopTrayMenu]. */
internal class DesktopStatusNotifierController(
    private val onActivate: () -> Unit,
    @Suppress("unused") private val onPlayPause: () -> Unit,
) {
    /** Oto's tray icon, from a PNG. */
    fun setIconPng(png: ByteArray) {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return
        val sized = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
        sized.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(image, 0, 0, ICON_SIZE, ICON_SIZE, null)
            dispose()
        }
        DesktopAwtTray.setIcon(sized)
    }

    fun start() = DesktopAwtTray.start(onActivate)

    fun stop() = DesktopAwtTray.stop()

    fun update(title: String?, isPlaying: Boolean) = DesktopAwtTray.update(title, isPlaying)

    private companion object {
        const val ICON_SIZE = 32
    }
}
