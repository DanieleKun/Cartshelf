package app.archivefetcher.data

import app.archivefetcher.addon.ConsoleInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverSourcesTest {
    private fun console(id: String, name: String, thumbnails: String? = null) = ConsoleInfo(id, name, "category", thumbnails)

    @Test
    fun `box art points at the console repository with libretro file naming`() {
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System/master/" +
                "Named_Boxarts/Super%20Mario%20All-Stars%20%2B%20Super%20Mario%20World%20%28USA%29.png",
            CoverSources.boxArt(
                console("nintendo-snes", "Nintendo - Super Nintendo Entertainment System"),
                "Super Mario All-Stars + Super Mario World (USA).zip",
            ),
        )
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy/master/Named_Boxarts/Tom%20_%20Jerry%20%28USA%29.png",
            CoverSources.boxArt(console("nintendo-gb", "Nintendo - Game Boy"), "Tom & Jerry (USA).zip"),
        )
    }

    @Test
    fun `consoles named differently from libretro find their repository`() {
        assertEquals("Sega_-_Mega_Drive_-_Genesis", CoverSources.repositoryFor(console("sega-mega-drive", "Sega - Mega Drive / Genesis")))
        assertEquals("Sega_-_Master_System_-_Mark_III", CoverSources.repositoryFor(console("custom", "Sega - Master System / Mark III")))
        assertEquals("The_3DO_Company_-_3DO", CoverSources.repositoryFor(console("panasonic-3do", "Panasonic - 3DO Interactive Multiplayer")))
    }

    @Test
    fun `an addon can name the repository, and unknown systems ask for nothing`() {
        assertEquals("Sharp_-_X68000", CoverSources.repositoryFor(console("x68k", "My X68000", thumbnails = "Sharp - X68000")))
        assertNull(CoverSources.repositoryFor(console("x68k", "My X68000", thumbnails = "../../evil")))
        assertNull(CoverSources.boxArt(console("arcade-sega-lindbergh", "Arcade - Sega - Lindbergh"), "Game.zip"))
    }

    @Test
    fun `link files lead to a sibling picture only`() {
        val from = "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System/master/Named_Boxarts/A%20%28Europe%29.png".toHttpUrl()
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System/master/Named_Boxarts/A%20(USA).png",
            CoverSources.linkTarget(from, "A (USA).png").toString(),
        )
        assertNull(CoverSources.linkTarget(from, "../../other/repo/x.png"))
        assertNull(CoverSources.linkTarget(from, "\u0089PNG binary"))
        assertNull(CoverSources.linkTarget(from, "line one\nline two.png"))
    }

    @Test
    fun `an addon cannot change the pictures of a console the app knows`() {
        assertEquals(
            "Sega_-_Mega_Drive_-_Genesis",
            CoverSources.repositoryFor(console("sega-mega-drive", "Sega - Mega Drive / Genesis", thumbnails = "Somewhere_Else")),
        )
        assertNull(CoverSources.repositoryFor(console("x", "Custom", thumbnails = "..")))
    }

    @Test
    fun `missing pictures fall back to the names RetroArch tries`() {
        assertEquals(listOf("007 Shitou - The Duel (Japan)"), CoverSources.fallbackNames("007 Shitou - The Duel (Japan) (En)"))
        assertEquals(
            listOf("Game (Europe) (Rev 1)", "Game (Europe)"),
            CoverSources.fallbackNames("Game (Europe) (En,Fr,De) (Rev 1)"),
        )
        assertEquals(emptyList<String>(), CoverSources.fallbackNames("Game (USA)"))
        assertEquals(emptyList<String>(), CoverSources.fallbackNames("Game"))
    }
}
