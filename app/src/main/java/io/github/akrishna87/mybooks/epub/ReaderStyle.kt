package io.github.akrishna87.mybooks.epub

/** Page colours. New ones go at the end: settings are saved by position. */
enum class ReaderTheme(val label: String, val background: String, val text: String, val link: String, val faint: String, val dark: Boolean = false) {
    LIGHT("Light", "#FBFAF7", "#1B1B1F", "#1F7A6E", "#D5D2CB"),
    SEPIA("Sepia", "#F4ECD8", "#3B2F21", "#8A5A14", "#D9CBA8"),
    DARK("Night", "#121214", "#D9D6CF", "#F2B35E", "#3A3A40", dark = true),
    PAPER("Paper", "#F2EFE9", "#2A2826", "#3D6E9E", "#D3CEC4"),
    CALM("Calm", "#EADFC8", "#3A3226", "#7A5A1E", "#CDBE9F"),
    GRAY("Gray", "#4A4A4F", "#E4E2DE", "#9FD3C7", "#6A6A70", dark = true),
    FOCUS("Focus", "#FFF8E6", "#2B2418", "#9A6A12", "#E8DCC0"),
}

/** Typefaces. [ORIGINAL] keeps the book's own fonts. New ones go at the end. */
enum class ReaderFont(val label: String, val css: String?) {
    SERIF("Serif", "Georgia, 'Noto Serif', serif"),
    SANS("Sans", "Roboto, 'Noto Sans', sans-serif"),
    ORIGINAL("Original", null),
    CONDENSED("Condensed", "'Roboto Condensed', sans-serif-condensed, sans-serif"),
    MONO("Mono", "'Roboto Mono', 'Droid Sans Mono', monospace"),
}

/** How pages change when you turn them. */
enum class PageTurn(val label: String) { SLIDE("Slide"), FADE("Fade"), NONE("None") }

/** How the reader lays out a chapter. Sizes are CSS pixels (the same as dp on Android). */
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val font: ReaderFont = ReaderFont.SERIF,
    /** Text size in percent; applied with WebView's text zoom so it works whatever the book's CSS says. */
    val textZoom: Int = 110,
    /** Line height, as a multiple of the text size. */
    val lineHeight: Float = 1.55f,
    val top: Int = 32,
    val bottom: Int = 44,
    val side: Int = 24,
    val justify: Boolean = false,
    val bold: Boolean = false,
    val pageTurn: PageTurn = PageTurn.SLIDE,
    /** Switch to the Night theme when the phone is in dark mode. */
    val autoNight: Boolean = false,
) {
    companion object {
        val LINE_HEIGHTS = listOf(1.3f, 1.55f, 1.85f)
        val MARGINS = listOf(14, 24, 38)
    }
}

/**
 * Page sizes come from --page-w and --page-h, which reader.js sets in pixels from the measured
 * screen: a WebView can lay a page out before it knows its height, and 100vh is then far too
 * small (one line per page).
 *
 * Overrides the book's own styles just enough to lay it out as screen-sized pages in the chosen
 * colours and font, keeping its headings, italics, indents and so on.
 */
fun readerCss(s: ReaderSettings): String {
    val t = s.theme
    val textHeight = s.top + s.bottom
    return """
        html { margin: 0 !important; padding: 0 !important; height: 100% !important; overflow: hidden !important;
          background: ${t.background} !important; -webkit-text-size-adjust: none; }
        body { margin: 0 !important; padding: ${s.top}px ${s.side}px ${s.bottom}px ${s.side}px !important;
          box-sizing: border-box !important; width: var(--page-w, 100vw) !important; max-width: none !important;
          height: var(--page-h, 100vh) !important; min-height: 0 !important; overflow: visible !important;
          column-width: calc(var(--page-w, 100vw) - ${2 * s.side}px) !important; column-gap: ${2 * s.side}px !important;
          column-fill: auto !important; background: ${t.background} !important; color: ${t.text} !important;
          ${s.font.css?.let { "font-family: $it !important;" } ?: ""} line-height: ${s.lineHeight} !important;
          ${if (s.justify) "text-align: justify !important; hyphens: auto; -webkit-hyphens: auto;" else ""}
          ${if (s.bold) "font-weight: 600 !important;" else ""}
          overflow-wrap: break-word; -webkit-tap-highlight-color: transparent; }
        body * { color: inherit !important; background-color: transparent !important; border-color: ${t.faint} !important;
          ${if (s.font.css != null) "font-family: inherit !important;" else ""} max-width: 100% !important; box-sizing: border-box; }
        ${if (s.justify) "body p, body div { text-align: justify !important; }" else ""}
        ${if (s.bold) "body p, body span, body div, body li { font-weight: 600 !important; }" else ""}
        body p { line-height: inherit !important; }
        body a, body a * { color: ${t.link} !important; text-decoration: none !important; }
        img, svg, video { max-width: 100% !important; max-height: calc(var(--page-h, 100vh) - ${textHeight}px) !important;
          width: auto !important; height: auto !important; object-fit: contain; break-inside: avoid; }
        body mark.__hl { background-color: ${HighlightColor.YELLOW.css} !important; color: inherit !important; border-radius: 2px; }
        ${HighlightColor.entries.filter { it != HighlightColor.UNDERLINE }.joinToString(" ") { "body mark.__hl.c-${it.name.lowercase()} { background-color: ${it.css} !important; }" }}
        body mark.__hl.c-underline { background-color: transparent !important; text-decoration: underline !important;
          text-decoration-color: ${t.link} !important; text-decoration-thickness: 2px !important; text-underline-offset: 3px; }
        body mark.__hl.__note { border-bottom: 2px dotted ${t.link} !important; }
        body mark.__find { background-color: ${t.link} !important; color: ${t.background} !important; border-radius: 2px; }
        pre { white-space: pre-wrap !important; }
        table { max-width: 100% !important; }
        hr { border-style: solid !important; border-width: 1px 0 0 0 !important; }
        html:not(.__ready) body { visibility: hidden !important; }
    """.trimIndent().replace(Regex("\\s*\n\\s*"), " ")
}

private val HEAD_END = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
private val BODY_START = Regex("<body[^>]*>", RegexOption.IGNORE_CASE)

/** Adds the reader's style and page-turning script to a chapter's XHTML. */
fun injectReader(html: String, css: String, scriptUrl: String): String {
    val tags = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\"/>" +
        "<style id=\"__reader_css\">$css</style><script src=\"$scriptUrl\"></script>"
    HEAD_END.find(html)?.let { return html.substring(0, it.range.first) + tags + html.substring(it.range.first) }
    BODY_START.find(html)?.let { return html.substring(0, it.range.last + 1) + tags + html.substring(it.range.last + 1) }
    return tags + html
}
