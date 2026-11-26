package app.oto.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's own copy, in whichever language is in force.
 *
 * The catalogues are Android's own, copied in at build time from its `res` values folders,
 * so a translation written once is read by both targets and desktop never drifts into its own
 * wording for a string Android already has.
 *
 * A key with no translation in the chosen language falls back to English rather than showing the
 * key: a half-translated locale should read as mostly-English, not as a debug build.
 */
object DesktopStrings {

    /** Oto is English only. */
    val available: List<DesktopLanguage> = listOf(DesktopLanguage("en", "English"))

    private val english: Map<String, String> by lazy { catalogue("en") }

    private val current: Map<String, String> get() = english

    private val _language = MutableStateFlow("en")

    val language: StateFlow<String> = _language

    fun resolvedTag(): String = "en"

    /**
     * One language, both layers.
     *
     * `<tag>.xml` is Android's own catalogue, copied in at build time. `desktop-<tag>.xml` holds
     * the copy only this target has — its window chrome, the source editor, output precision — and
     * is laid over the top, so a desktop key never collides with an Android one.
     *
     * zh-Hant/zh-Hans fall back to legacy "zh" files so old installs keep
     * working while the script-tagged catalogues roll out.
     */
    private fun catalogue(tag: String): Map<String, String> = load(tag) + load("desktop-$tag")

    @Suppress("UNUSED_PARAMETER")
    fun setLanguage(tag: String) = Unit

    /** The label shown for the stored choice. */
    fun languageLabel(): String =
        "English"

    /**
     * The string for [key], or [fallback] when no catalogue has one.
     *
     * The fallback is the English text the call site would otherwise have held literally, so an
     * untranslated key still reads as the sentence it was written as.
     */
    operator fun get(key: String, fallback: String = key): String =
        current[key] ?: english[key] ?: fallback

    /** As [get], with Android's `%1$s`-style positional arguments filled in. */
    fun format(key: String, vararg arguments: Any?, fallback: String = key): String =
        runCatching { String.format(get(key, fallback), *arguments) }.getOrDefault(get(key, fallback))

    private fun load(tag: String): Map<String, String> = runCatching {
        val stream = DesktopStrings::class.java.getResourceAsStream("/strings/$tag.xml")
            ?: return@runCatching emptyMap()
        val document = stream.use {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(it)
        }
        val nodes = document.getElementsByTagName("string")
        buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                put(name, unescape(node.textContent.orEmpty()))
            }
        }
    }.getOrDefault(emptyMap())

    /**
     * A catalogue value as the UI should read it.
     *
     * Android escapes an apostrophe for its own resource parser, and a `\uXXXX` can survive a
     * round trip out of source into a catalogue — either of which would otherwise be drawn
     * literally, which is how "Don't" once reached the screen as "Don\u2019t".
     */
    internal fun unescape(raw: String): String {
        val decoded = UNICODE_ESCAPE.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        return decoded.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
    }

    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

    internal const val KEY_LANGUAGE = "app_language"
}

/** One entry in the language picker. */
data class DesktopLanguage(val tag: String, val label: String)
