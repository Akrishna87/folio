package io.github.akrishna87.mybooks.catalog

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Common named HTML entities. Anything else unknown becomes a space so XML parsing never fails. */
private val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "copy" to "©", "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á",
    "ouml" to "ö", "uuml" to "ü", "auml" to "ä", "ccedil" to "ç", "szlig" to "ß", "middot" to "·",
)

private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

/** Decodes HTML entities in plain text. */
fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
    val e = m.groupValues[1]
    when {
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        else -> ENTITIES[e] ?: m.value
    }
}

/** Turns the HTML descriptions the catalogs return into readable paragraphs. */
fun htmlToText(html: String): String {
    if (html.isBlank()) return ""
    val withBreaks = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n\n")
    val noTags = withBreaks.replace(Regex("<[^>]*>"), "")
    return decodeEntities(noTags)
        .replace(' ', ' ')
        .lines()
        .joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** "11 h 39 min", "24 min", "45 s". */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        m > 0 -> "$m min"
        else -> "$total s"
    }
}

/** "312 MB", "1.2 GB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** Lengths come as seconds ("1234.56") or clock time ("20:34", "1:02:03"). */
fun parseLength(s: String?): Double {
    if (s.isNullOrBlank()) return 0.0
    if (':' in s) {
        return s.split(':').fold(0.0) { acc, part -> acc * 60 + (part.trim().toDoubleOrNull() ?: 0.0) }
    }
    return s.trim().toDoubleOrNull() ?: 0.0
}

fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Encodes each part of a file path for a URL, keeping the slashes. */
fun encodePath(path: String): String = path.split('/').joinToString("/") { urlEncode(it).replace("+", "%20") }

/** "Austen, Jane" → "Jane Austen". */
fun flipName(name: String): String {
    val parts = name.split(", ", limit = 2)
    if (parts.size != 2) return name.trim()
    val (last, first) = parts
    // "Smith, John, 1800-1870" style leftovers and "Various" stay readable.
    return "${first.trim()} ${last.trim()}".trim()
}

/**
 * Parses XML (OPDS feeds, EPUB files) leniently: drops the DOCTYPE (so nothing is fetched)
 * and replaces HTML-only entities, which are the usual reasons strict XML parsers give up.
 */
fun parseXml(text: String): Document {
    val cleaned = text
        .replace(Regex("^﻿"), "")
        .replace(Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*\\])?\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(ENTITY) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#") || e in setOf("amp", "lt", "gt", "quot", "apos") -> m.value
                else -> ENTITIES[e] ?: " "
            }
        }
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isValidating = false
    try {
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    } catch (e: Exception) {
        // Not every parser knows this feature; with the DOCTYPE gone it doesn't matter.
    }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(cleaned)))
}

/** Child elements of [this] with the given local name, at any depth. */
fun Node.descendants(localName: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(n: Node) {
        val kids = n.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k is Element) {
                if (k.localName == localName || k.nodeName == localName || k.nodeName.endsWith(":$localName")) out += k
                walk(k)
            }
        }
    }
    walk(this)
    return out
}

/** Direct child elements with the given local name. */
fun Node.children(localName: String): List<Element> {
    val out = ArrayList<Element>()
    val kids = childNodes
    for (i in 0 until kids.length) {
        val k = kids.item(i)
        if (k is Element && (k.localName == localName || k.nodeName == localName || k.nodeName.endsWith(":$localName"))) out += k
    }
    return out
}

fun Element.child(localName: String): Element? = children(localName).firstOrNull()

/** The element's text with whitespace tidied. */
val Node.text: String get() = (textContent ?: "").replace(Regex("\\s+"), " ").trim()

/** An attribute by local name, whatever its namespace prefix ("epub:type", "opf:role" …). */
fun Element.attr(localName: String): String {
    if (hasAttribute(localName)) return getAttribute(localName)
    val attrs = attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if (a.localName == localName || a.nodeName.endsWith(":$localName")) return a.nodeValue ?: ""
    }
    return ""
}
