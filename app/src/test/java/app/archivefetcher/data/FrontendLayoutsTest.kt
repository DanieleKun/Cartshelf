package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontendLayoutsTest {
    @Test
    fun `short names follow each frontend`() {
        assertEquals("gbc", FrontendLayouts.folderName(FrontendLayout.EsDe, "nintendo-gbc", "Nintendo - Game Boy Color"))
        assertEquals("gc", FrontendLayouts.folderName(FrontendLayout.EsDe, "nintendo-gamecube", "x"))
        assertEquals("gamecube", FrontendLayouts.folderName(FrontendLayout.Batocera, "nintendo-gamecube", "x"))
        assertEquals("psx", FrontendLayouts.folderName(FrontendLayout.Batocera, "sony-ps1", "x"))
    }

    @Test
    fun `unknown consoles and the names layout use the full console name`() {
        assertEquals("Odd Console", FrontendLayouts.folderName(FrontendLayout.EsDe, "other-odd", "Odd Console"))
        assertEquals("Nintendo - NES", FrontendLayouts.folderName(FrontendLayout.Names, "nintendo-nes", "Nintendo - NES"))
    }

    @Test
    fun `unsafe characters are removed from folder names`() {
        assertEquals("A_B", FrontendLayouts.folderName(FrontendLayout.Names, "x", "A/B"))
    }

    @Test
    fun `an unknown layout id falls back to names`() {
        assertEquals(FrontendLayout.Names, FrontendLayout.fromId("nonsense"))
        assertEquals(FrontendLayout.EsDe, FrontendLayout.fromId("esde"))
    }
}
