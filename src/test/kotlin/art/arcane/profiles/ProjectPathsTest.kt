package art.arcane.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPathsTest {

    @Test
    fun `normalize collapses relative segments`() {
        assertEquals("/a/c", ProjectPaths.normalize("/a/b/../c"))
    }

    @Test
    fun `normalize strips trailing separator`() {
        assertEquals(ProjectPaths.normalize("/a/b"), ProjectPaths.normalize("/a/b/"))
    }

    @Test
    fun `sameProject treats trailing slash as equal`() {
        assertTrue(ProjectPaths.sameProject("/work/proj", "/work/proj/"))
    }

    @Test
    fun `sameProject distinguishes different projects`() {
        assertFalse(ProjectPaths.sameProject("/work/a", "/work/b"))
    }

    @Test
    fun `normalizeAll removes duplicates preserving order`() {
        assertEquals(listOf("/a", "/b"), ProjectPaths.normalizeAll(listOf("/a", "/a/", "/b")))
    }

    @Test
    fun `sameSet ignores order and duplicates`() {
        assertTrue(ProjectPaths.sameSet(listOf("/a", "/b", "/a"), listOf("/b", "/a")))
    }

    @Test
    fun `sameSet false when membership differs`() {
        assertFalse(ProjectPaths.sameSet(listOf("/a", "/b"), listOf("/a")))
    }
}
