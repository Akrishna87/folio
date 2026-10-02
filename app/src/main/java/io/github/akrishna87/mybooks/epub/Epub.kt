package io.github.akrishna87.mybooks.epub

import io.github.akrishna87.mybooks.catalog.attr
import io.github.akrishna87.mybooks.catalog.child
import io.github.akrishna87.mybooks.catalog.children
import io.github.akrishna87.mybooks.catalog.descendants
import io.github.akrishna87.mybooks.catalog.parseXml
import io.github.akrishna87.mybooks.catalog.text
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Reads files out of an EPUB by their path inside it. */
interface EpubFiles {
    fun read(path: String): ByteArray?
}

class ZipEpubFiles(file: File) : EpubFiles, AutoCloseable {
    private val zip = ZipFile(file)
    override fun read(path: String): ByteArray? =
        zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
    override fun close() = zip.close()
}

/** A part of the book in reading order (usually one chapter). */
data class SpineItem(val path: String, val mediaType: String)

/** A line of the table of contents. [path] is inside the EPUB; [fragment] is the spot in that file. */
data class TocEntry(val title: String, val path: String, val fragment: String, val depth: Int)

class Epub(
    val title: String,
    val author: String,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    /** Media type of every file listed in the book, by path. */
    val mediaTypes: Map<String, String>,
) {
    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path }

    /** The table of contents line for a spine item: the last entry at or before it. */
    fun chapterTitle(spineIndex: Int): String {
        var best: TocEntry? = null
        for (e in toc) {
            val i = spineIndexOf(e.path)
            if (i in 0..spineIndex && (best == null || i >= spineIndexOf(best.path))) best = e
        }
        return best?.title ?: ""
    }

    companion object {
        fun parse(files: EpubFiles): Epub {
            val container = files.read("META-INF/container.xml")?.let { parseXml(String(it, Charsets.UTF_8)) }
                ?: throw IOException("Not an EPUB: META-INF/container.xml is missing")
            val opfPath = container.documentElement.descendants("rootfile").firstOrNull()?.getAttribute("full-path")
                ?.takeIf { it.isNotEmpty() } ?: throw IOException("The EPUB doesn't say where its contents are")
            val opfBytes = files.read(opfPath) ?: throw IOException("The EPUB is missing $opfPath")
            val opf = parseXml(String(opfBytes, Charsets.UTF_8)).documentElement
            val base = opfPath.substringBeforeLast('/', "")

            val metadata = opf.child("metadata")
            val title = metadata?.descendants("title")?.firstOrNull()?.text.orEmpty()
            val author = metadata?.descendants("creator")?.map { it.text }?.filter { it.isNotEmpty() }?.joinToString(", ").orEmpty()

            class Item(val path: String, val mediaType: String, val properties: String)
            val items = HashMap<String, Item>()
            val mediaTypes = HashMap<String, String>()
            opf.child("manifest")?.children("item")?.forEach { el ->
                val path = resolve(base, el.getAttribute("href")).first
                val item = Item(path, el.getAttribute("media-type"), el.getAttribute("properties"))
                items[el.getAttribute("id")] = item
                mediaTypes[path] = item.mediaType
            }

            val spineEl = opf.child("spine")
            val refs = spineEl?.children("itemref").orEmpty()
            fun spineFrom(list: List<Element>) = list.mapNotNull { items[it.getAttribute("idref")] }
                .map { SpineItem(it.path, it.mediaType.ifEmpty { "application/xhtml+xml" }) }
            val spine = spineFrom(refs.filter { it.getAttribute("linear") != "no" }).ifEmpty { spineFrom(refs) }
            if (spine.isEmpty()) throw IOException("The EPUB has no chapters")

            val nav = items.values.firstOrNull { "nav" in it.properties.split(' ') }
            val ncx = items[spineEl?.getAttribute("toc").orEmpty()]
                ?: items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            val toc = (nav?.let { parseNav(files, it.path) }.orEmpty())
                .ifEmpty { ncx?.let { parseNcx(files, it.path) }.orEmpty() }
                .filter { it.title.isNotEmpty() }

            return Epub(title, author, spine, toc, mediaTypes)
        }

        /** EPUB 3 table of contents: the `<nav epub:type="toc">` list. */
        private fun parseNav(files: EpubFiles, path: String): List<TocEntry> = try {
            val doc = parseXml(String(files.read(path) ?: return emptyList(), Charsets.UTF_8)).documentElement
            val navs = doc.descendants("nav")
            val toc = navs.firstOrNull { it.attr("type").split(' ').contains("toc") } ?: navs.firstOrNull()
            val out = ArrayList<TocEntry>()
            val dir = path.substringBeforeLast('/', "")
            fun walk(list: Element, depth: Int) {
                for (li in list.children("li")) {
                    val a = li.child("a") ?: li.child("span")
                    val href = a?.getAttribute("href").orEmpty()
                    if (a != null && href.isNotEmpty()) {
                        val (p, frag) = resolve(dir, href)
                        out += TocEntry(a.text, p, frag, depth)
                    }
                    li.child("ol")?.let { walk(it, depth + 1) }
                }
            }
            toc?.child("ol")?.let { walk(it, 0) }
            out
        } catch (e: Exception) {
            emptyList()
        }

        /** EPUB 2 table of contents (toc.ncx). */
        private fun parseNcx(files: EpubFiles, path: String): List<TocEntry> = try {
            val doc = parseXml(String(files.read(path) ?: return emptyList(), Charsets.UTF_8)).documentElement
            val out = ArrayList<TocEntry>()
            val dir = path.substringBeforeLast('/', "")
            fun walk(parent: Element, depth: Int) {
                for (point in parent.children("navPoint")) {
                    val label = point.child("navLabel")?.text.orEmpty()
                    val src = point.child("content")?.getAttribute("src").orEmpty()
                    if (src.isNotEmpty()) {
                        val (p, frag) = resolve(dir, src)
                        out += TocEntry(label, p, frag, depth)
                    }
                    walk(point, depth + 1)
                }
            }
            doc.child("navMap")?.let { walk(it, 0) }
            out
        } catch (e: Exception) {
            emptyList()
        }

        /** Resolves [href] against the folder [dir], returning (path in the EPUB, #fragment). */
        fun resolve(dir: String, href: String): Pair<String, String> {
            val fragment = href.substringAfter('#', "")
            val raw = href.substringBefore('#')
            val decoded = try {
                URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            } catch (e: IllegalArgumentException) {
                raw
            }
            return normalize(if (dir.isEmpty() || decoded.startsWith("/")) decoded.trimStart('/') else "$dir/$decoded") to fragment
        }

        /** Removes "." and ".." parts. */
        fun normalize(path: String): String {
            val out = ArrayList<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                    else -> out += part
                }
            }
            return out.joinToString("/")
        }
    }
}
