package app.archivefetcher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveApiTest {
    @Test
    fun `an empty path matches every file of the item`() {
        assertTrue(ArchiveApi.matchesPath("anything/at/all.zip", ""))
    }

    @Test
    fun `a folder path matches the files below it only`() {
        assertTrue(ArchiveApi.matchesPath("roms/gba/game.zip", "roms/gba"))
        assertFalse(ArchiveApi.matchesPath("roms/gba-extra/game.zip", "roms/gba"))
        assertFalse(ArchiveApi.matchesPath("other/game.zip", "roms/gba"))
    }

    @Test
    fun `a file path matches that single file`() {
        assertTrue(ArchiveApi.matchesPath("roms/Atari - 5200.zip", "roms/Atari - 5200.zip"))
        assertFalse(ArchiveApi.matchesPath("roms/Atari - 7800.zip", "roms/Atari - 5200.zip"))
    }
}
