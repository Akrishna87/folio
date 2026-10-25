package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfItemTest {
    @Test
    fun shelfItemKeepsCollections() {
        val item = ShelfItem(Book("gutenberg:1342", Kind.EBOOK, Source.GUTENBERG, "Pride and Prejudice", "Jane Austen", null), collections = listOf("Holiday reads"))
        assertEquals(listOf("Holiday reads"), ShelfItem.fromJson(JSONObject(item.toJson().toString())).collections)
        val old = JSONObject(item.toJson().toString()).apply { remove("collections") }
        assertEquals(emptyList<String>(), ShelfItem.fromJson(old).collections)
    }
}
