package songlistener.io

import songlistener.core.SectionMark
import songlistener.core.SongTake
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TakeStoreTest {
    private val dir = Files.createTempDirectory("takes").toFile()

    private fun take(key: String, at: Long) = SongTake(
        key, "label $key", at,
        List(3) { f -> FloatArray(12) { it * 0.1f + f } },
        listOf(SectionMark(0, 0), SectionMark(2, 1)),
    )

    @Test
    fun `a take survives a round trip`() {
        val store = TakeStore(dir, maxTakesPerSong = 5)
        store.save(take("1|Amazing Grace", 42L))
        val loaded = store.loadAll().single()
        assertEquals("1|Amazing Grace", loaded.songKey)
        // The stored label is replaced by the one derived from the key, number first.
        assertEquals("#1 Amazing Grace", loaded.songLabel)
        assertEquals(42L, loaded.recordedAt)
        assertEquals(3, loaded.frames.size)
        assertContentEquals(take("x", 0).frames[2], loaded.frames[2])
        assertEquals(listOf(SectionMark(0, 0), SectionMark(2, 1)), loaded.sectionMarks)
    }

    @Test
    fun `saving prunes to the newest takes, and delete removes one song only`() {
        val store = TakeStore(dir, maxTakesPerSong = 2)
        listOf(100L, 200L, 300L).forEach { store.save(take("a", it)) }
        store.save(take("b", 150L))
        assertEquals(listOf(200L, 300L), store.loadAll().filter { it.songKey == "a" }.map { it.recordedAt })

        store.delete("a")
        assertEquals(listOf("b"), store.loadAll().map { it.songKey })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `one take can be deleted on its own`() {
        val store = TakeStore(dir, maxTakesPerSong = 5)
        store.save(take("a", 100L)); store.save(take("a", 200L))
        store.deleteTake("a", 100L)
        assertEquals(listOf(200L), store.loadAll().map { it.recordedAt })
    }

    @Test
    fun `clipping survives a round trip, and old takes without it still load`() {
        val store = TakeStore(dir, maxTakesPerSong = 5)
        store.save(take("a", 100L).withClipped(0.25))
        store.save(take("b", 100L))
        assertEquals(mapOf("a" to 0.25, "b" to null), store.loadAll().associate { it.songKey to it.clipped })

        // A take written before clipping was stored: the same bytes without the trailing double.
        val oldDir = Files.createTempDirectory("old-takes").toFile()
        val old = TakeStore(oldDir, maxTakesPerSong = 5)
        old.save(take("a", 100L).withClipped(0.25))
        val f = oldDir.listFiles()!!.single()
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 8))
        val loaded = old.loadAll().single()
        assertEquals(null, loaded.clipped)
        assertEquals(3, loaded.frames.size)
    }

    @Test
    fun `a selector decides which takes are kept`() {
        val store = TakeStore(dir, maxTakesPerSong = 2) { all -> all.sortedBy { it.recordedAt }.take(2) }
        listOf(100L, 200L, 300L).forEach { store.save(take("a", it)) }
        assertEquals(listOf(100L, 200L), store.loadAll().map { it.recordedAt })
    }

    @Test
    fun `deleteAll moves every take aside, where it can be restored from`() {
        val store = TakeStore(dir, maxTakesPerSong = 5)
        store.save(take("a", 1L)); store.save(take("b", 2L))
        val trash = Files.createTempDirectory("trash").toFile()
        store.deleteAll(trash)
        assertTrue(store.loadAll().isEmpty())
        assertEquals(listOf(1L, 2L), TakeStore(trash, maxTakesPerSong = 5).loadAll().map { it.recordedAt })
    }
}
