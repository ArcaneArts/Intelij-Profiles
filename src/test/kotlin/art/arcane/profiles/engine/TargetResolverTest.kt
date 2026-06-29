package art.arcane.profiles.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TargetResolverTest {

    @Test
    fun `resolve dedups by canonical key and preserves order`() {
        val resolved = TargetResolver.resolve(listOf("/a", "/a/", "/b")) { true }
        assertEquals(listOf("/a", "/b"), resolved.targets.map { it.toString() })
        assertTrue(resolved.missing.isEmpty())
    }

    @Test
    fun `resolve partitions missing paths`() {
        val resolved = TargetResolver.resolve(listOf("/exists", "/gone")) { it.toString() == "/exists" }
        assertEquals(listOf("/exists"), resolved.targets.map { it.toString() })
        assertEquals(listOf("/gone"), resolved.missing)
    }

    @Test
    fun `canonicalKey collapses trailing slash`() {
        assertEquals(canonicalKey(Path.of("/x/y")), canonicalKey(Path.of("/x/y/")))
    }

    @Test
    fun `canonicalKey resolves a symlink to its real target`() {
        val real = Files.createTempDirectory("profiles-real")
        val link = Files.createTempDirectory("profiles-linkparent").resolve("link")
        Files.createSymbolicLink(link, real)
        assertEquals(canonicalKey(real), canonicalKey(link))
    }
}
