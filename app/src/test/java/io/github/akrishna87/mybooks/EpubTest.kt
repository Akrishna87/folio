package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.epub.Epub
import io.github.akrishna87.mybooks.epub.EpubFiles
import io.github.akrishna87.mybooks.epub.ReaderSettings
import io.github.akrishna87.mybooks.epub.injectReader
import io.github.akrishna87.mybooks.epub.readerCss
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class EpubTest {

    private class MapFiles(val files: Map<String, String>) : EpubFiles {
        override fun read(path: String) = files[path]?.toByteArray()
    }

    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    @Test
    fun epub3WithNav() {
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Pride and Prejudice</dc:title>
                <dc:creator>Jane Austen</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="cover" href="wrap0000.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="cover" linear="yes"/>
                <itemref idref="nav" linear="no"/>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()
        val nav = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head><title>Contents</title></head>
            <body>
              <nav epub:type="landmarks"><ol><li><a href="wrap0000.xhtml">Cover</a></li></ol></nav>
              <nav epub:type="toc"><ol>
                <li><a href="text/ch%201.xhtml">Chapter&nbsp;I</a>
                  <ol><li><a href="text/ch%201.xhtml#part2">Part two</a></li></ol>
                </li>
                <li><a href="text/ch2.xhtml#start">Chapter II</a></li>
              </ol></nav>
            </body></html>
        """.trimIndent()
        val epub = Epub.parse(MapFiles(mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/nav.xhtml" to nav)))
        assertEquals("Pride and Prejudice", epub.title)
        assertEquals("Jane Austen", epub.author)
        assertEquals(listOf("OEBPS/wrap0000.xhtml", "OEBPS/text/ch 1.xhtml", "OEBPS/text/ch2.xhtml"), epub.spine.map { it.path })
        assertEquals(listOf("Chapter I", "Part two", "Chapter II"), epub.toc.map { it.title })
        assertEquals(listOf(0, 1, 0), epub.toc.map { it.depth })
        assertEquals("part2", epub.toc[1].fragment)
        assertEquals("OEBPS/text/ch2.xhtml", epub.toc[2].path)
        assertEquals("image/jpeg", epub.mediaTypes["OEBPS/images/cover.jpg"])
        assertEquals("Chapter II", epub.chapterTitle(2))
        assertEquals("", epub.chapterTitle(0))
    }

    @Test
    fun epub2WithNcx() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>Emma</dc:title><dc:creator opf:role="aut">Jane Austen</dc:creator>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="a" href="a.html" media-type="application/xhtml+xml"/>
                <item id="b" href="../b.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine>
            </package>
        """.trimIndent()
        val ncx = """
            <?xml version="1.0"?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="p1" playOrder="1"><navLabel><text>VOLUME I</text></navLabel><content src="a.html"/>
                  <navPoint id="p2" playOrder="2"><navLabel><text>CHAPTER I</text></navLabel><content src="a.html#c1"/></navPoint>
                </navPoint>
                <navPoint id="p3" playOrder="3"><navLabel><text>CHAPTER II</text></navLabel><content src="../b.html"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        val epub = Epub.parse(MapFiles(mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/toc.ncx" to ncx)))
        assertEquals(listOf("OEBPS/a.html", "b.html"), epub.spine.map { it.path })
        assertEquals(listOf("VOLUME I", "CHAPTER I", "CHAPTER II"), epub.toc.map { it.title })
        assertEquals(listOf(0, 1, 0), epub.toc.map { it.depth })
        assertEquals("b.html", epub.toc[2].path)
    }

    @Test
    fun paths() {
        assertEquals("OEBPS/images/a b.png" to "x", Epub.resolve("OEBPS/text", "../images/a%20b.png#x"))
        assertEquals("c.xhtml" to "", Epub.resolve("", "./c.xhtml"))
    }

    @Test
    fun readerIsAddedToChapters() {
        val css = readerCss(ReaderSettings())
        assertTrue("CSS must be safe inside XHTML", '<' !in css && '&' !in css)
        val page = injectReader("<html><head><title>x</title></head><body><p>Hi</p></body></html>", css, "https://book.local/r.js")
        assertTrue(page, page.indexOf("__reader_css") < page.indexOf("</head>"))
        assertTrue(page, page.contains("<script src=\"https://book.local/r.js\"></script></head>"))
        val noHead = injectReader("<html><body class=\"a\"><p>Hi</p></body></html>", css, "r.js")
        assertTrue(noHead, noHead.startsWith("<html><body class=\"a\"><meta"))
    }
}
