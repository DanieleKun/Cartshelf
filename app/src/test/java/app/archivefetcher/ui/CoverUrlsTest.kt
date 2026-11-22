package app.archivefetcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverUrlsTest {
    @Test
    fun `box art points at the console repository with libretro file naming`() {
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System/master/" +
                "Named_Boxarts/Super%20Mario%20All-Stars%20%2B%20Super%20Mario%20World%20%28USA%29.png",
            CoverUrls.boxArt("Nintendo - Super Nintendo Entertainment System", "Super Mario All-Stars + Super Mario World (USA).zip"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy/master/Named_Boxarts/Tom%20_%20Jerry%20%28USA%29.png",
            CoverUrls.boxArt("Nintendo - Game Boy", "Tom & Jerry (USA).zip"),
        )
    }
}
