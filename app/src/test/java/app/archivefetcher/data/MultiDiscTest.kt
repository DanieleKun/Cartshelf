package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDiscTest {
    @Test
    fun `disc tags are recognised in their common forms`() {
        assertEquals(MultiDisc.Disc("Final Fantasy VII (USA)", 1), MultiDisc.discOf("Final Fantasy VII (USA) (Disc 1).cue"))
        assertEquals(MultiDisc.Disc("Metal Gear Solid (Europe)", 2), MultiDisc.discOf("Metal Gear Solid (Europe) (Disk 2 of 2).chd"))
        assertEquals(MultiDisc.Disc("Riven (USA)", 3), MultiDisc.discOf("Riven (USA) (CD3).iso"))
        assertEquals(MultiDisc.Disc("Gioco (Italy)", 2), MultiDisc.discOf("Gioco (Italy) [Disco 2].bin"))
        assertEquals(MultiDisc.Disc("Shenmue (Europe) (En,Fr)", 1), MultiDisc.discOf("Shenmue (Disc 1) (Europe) (En,Fr).gdi"))
        assertNull(MultiDisc.discOf("Tetris (World).gb"))
        assertNull(MultiDisc.discOf("Discworld (Europe).cue"))
    }

    @Test
    fun `a playlist lists the best file of each disc in order`() {
        val folder = listOf(
            "Final Fantasy VII (USA) (Disc 3).cue", "Final Fantasy VII (USA) (Disc 3).bin",
            "Final Fantasy VII (USA) (Disc 1).cue", "Final Fantasy VII (USA) (Disc 1).bin",
            "Final Fantasy VII (USA) (Disc 2).cue", "Final Fantasy VII (USA) (Disc 2).bin",
            "Crash Bandicoot (USA).cue", "readme.txt",
        )
        val playlist = MultiDisc.playlists(folder).single()
        assertEquals("Final Fantasy VII (USA).m3u", playlist.fileName)
        assertEquals(
            listOf("Final Fantasy VII (USA) (Disc 1).cue", "Final Fantasy VII (USA) (Disc 2).cue", "Final Fantasy VII (USA) (Disc 3).cue"),
            playlist.entries,
        )
        assertEquals("Final Fantasy VII (USA) (Disc 1).cue\nFinal Fantasy VII (USA) (Disc 2).cue\nFinal Fantasy VII (USA) (Disc 3).cue\n", playlist.content)
    }

    @Test
    fun `chd discs and several games in one folder`() {
        val folder = listOf(
            "Metal Gear Solid (USA) (Disc 1).chd", "Metal Gear Solid (USA) (Disc 2).chd",
            "Parasite Eve (USA) (Disc 2).chd", "Parasite Eve (USA) (Disc 1).chd",
        )
        val playlists = MultiDisc.playlists(folder).associateBy { it.fileName }
        assertEquals(setOf("Metal Gear Solid (USA).m3u", "Parasite Eve (USA).m3u"), playlists.keys)
        assertEquals("Parasite Eve (USA) (Disc 1).chd", playlists.getValue("Parasite Eve (USA).m3u").entries.first())
    }

    @Test
    fun `a single disc, archives and versions that differ are not mixed`() {
        assertTrue(MultiDisc.playlists(listOf("Game (USA) (Disc 1).cue", "Game (USA) (Disc 1).bin")).isEmpty())
        assertTrue(MultiDisc.playlists(listOf("Game (USA) (Disc 1).zip", "Game (USA) (Disc 2).zip")).isEmpty())
        assertTrue(MultiDisc.playlists(listOf("Game (USA) (Disc 1).chd", "Game (Europe) (Disc 2).chd")).isEmpty())
    }
}
