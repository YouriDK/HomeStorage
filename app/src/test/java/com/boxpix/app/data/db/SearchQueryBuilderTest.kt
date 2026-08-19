package com.boxpix.app.data.db

import androidx.sqlite.db.SupportSQLiteProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryBuilderTest {

    @Test
    fun `always scopes to the current root subtree`() {
        val query = SearchQueryBuilder.build(
            providerId = "freebox",
            rootDisplayPath = "/Archive 1",
            nameContains = null,
            fromEpochSeconds = null,
            toEpochSeconds = null,
            folderPrefix = null,
            pathsWithAllTags = null,
        )
        assertTrue(query.sql.contains("(folderDisplayPath = ? OR folderDisplayPath LIKE ?)"))
        assertEquals(listOf("freebox", "/Archive 1", "/Archive 1/%"), query.boundArgs())
    }

    @Test
    fun `folder chip narrows within the root scope`() {
        val query = SearchQueryBuilder.build(
            providerId = "freebox",
            rootDisplayPath = "/Archive 1",
            nameContains = "plage",
            fromEpochSeconds = null,
            toEpochSeconds = null,
            folderPrefix = "/Archive 1/Photos/Trips 2026",
            pathsWithAllTags = null,
        )
        assertEquals(
            listOf(
                "freebox",
                "/Archive 1",
                "/Archive 1/%",
                "%plage%",
                "/Archive 1/Photos/Trips 2026",
                "/Archive 1/Photos/Trips 2026/%",
            ),
            query.boundArgs(),
        )
    }

    @Test
    fun `a trailing slash on the root does not break the prefix match`() {
        val query = SearchQueryBuilder.build(
            providerId = "freebox",
            rootDisplayPath = "/Archive 1/",
            nameContains = null,
            fromEpochSeconds = null,
            toEpochSeconds = null,
            folderPrefix = null,
            pathsWithAllTags = null,
        )
        assertEquals(listOf("freebox", "/Archive 1", "/Archive 1/%"), query.boundArgs())
    }

    @Test
    fun `the virtual root scopes to nothing extra`() {
        // "/" trims to empty: every folder of the provider is legitimately in scope.
        val query = SearchQueryBuilder.build(
            providerId = "fake",
            rootDisplayPath = "/",
            nameContains = null,
            fromEpochSeconds = null,
            toEpochSeconds = null,
            folderPrefix = null,
            pathsWithAllTags = null,
        )
        assertFalse(query.sql.contains("folderDisplayPath ="))
        assertEquals(listOf<Any>("fake"), query.boundArgs())
    }

    /** Replays the bind calls to recover the positional arguments. */
    private fun androidx.sqlite.db.SupportSQLiteQuery.boundArgs(): List<Any?> {
        val slots = arrayOfNulls<Any?>(argCount)
        bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { slots[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { slots[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { slots[index - 1] = value }
            override fun bindString(index: Int, value: String) { slots[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { slots[index - 1] = value }
            override fun clearBindings() = error("unused")
            override fun close() = Unit
        })
        return slots.toList()
    }
}
