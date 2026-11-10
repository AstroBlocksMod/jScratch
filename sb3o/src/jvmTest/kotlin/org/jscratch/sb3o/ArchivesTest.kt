package org.jscratch.sb3o

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArchivesTest {
    @Test
    fun keepsFullPathsWhenAskedTo() {
        val entries =
            linkedMapOf(
                "game/project.json" to "{}".toByteArray(),
                "game/a/x.png" to byteArrayOf(1),
                "game/b/x.png" to byteArrayOf(2),
            )
        val zip = Archives.write(entries)
        val paths = Archives.readPaths(zip)
        assertEquals(entries.keys.toList(), paths.keys.toList())
        assertEquals(listOf<Byte>(2), paths.getValue("game/b/x.png").toList())
        // By file name only, the two images become one entry.
        assertEquals(setOf("project.json", "x.png"), Archives.read(zip).keys)
    }

    @Test
    fun findsProjectJsonWhereScratchLooks() {
        assertEquals("project.json", Archives.projectJsonPath(listOf("a.png", "project.json")))
        assertEquals("game/project.json", Archives.projectJsonPath(listOf("game/project.json", "game/a.png")))
        assertNull(Archives.projectJsonPath(listOf("a/b/project.json")))
        assertNull(Archives.projectJsonPath(listOf("notproject.json")))
    }
}
