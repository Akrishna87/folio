package io.github.akrishna87.mybooks.epub

enum class ReaderTheme(val label: String, val background: String, val text: String, val link: String, val faint: String) {
    LIGHT("Light", "#FBFAF7", "#1B1B1F", "#1F7A6E", "#D5D2CB"),
    SEPIA("Sepia", "#F4ECD8", "#3B2F21", "#8A5A14", "#D9CBA8"),
    DARK("Dark", "#121214", "#D9D6CF", "#F2B35E", "#3A3A40"),
}

enum class ReaderFont(val label: String, val css: String) {
    SERIF("Serif", "Georgia, 'Noto Serif', serif"),
    SANS("Sans", "Roboto, 'Noto Sans', sans-serif"),
}

/** How the reader lays out a chapter. Sizes are CSS pixels (the same as dp on Android). */
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val font: ReaderFont = ReaderFont.SERIF,
    /** Text size in percent; applied with WebView's text zoom so it works whatever the book's CSS says. */
    val textZoom: Int = 110,
    val top: Int = 32,
    val bottom: Int = 44,
    val side: Int = 24,
)

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
          font-family: ${s.font.css} !important; line-height: 1.55 !important;
          overflow-wrap: break-word; -webkit-tap-highlight-color: transparent; }
        body * { color: inherit !important; background-color: transparent !important; border-color: ${t.faint} !important;
          font-family: inherit !important; max-width: 100% !important; box-sizing: border-box; }
        body a, body a * { color: ${t.link} !important; text-decoration: none !important; }
        img, svg, video { max-width: 100% !important; max-height: calc(var(--page-h, 100vh) - ${textHeight}px) !important;
          width: auto !important; height: auto !important; object-fit: contain; break-inside: avoid; }
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
