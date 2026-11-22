package app.archivefetcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ZipListingTest {
    private val page = """
        <tr><th>file<th>as jpg<th>timestamp<th>size</tr>
        <tr><td>GBC/<td><td>2024-03-18 06:26<td id="size"></tr>
        <tr><td><a href="//archive.org/download/x/GBC.zip/GBC%2FA%20%26%20B.zip">GBC/A &amp; B (USA).zip</a><td><td>2024-03-18 05:31<td id="size">753495</tr>
        <tr><td><a href="//archive.org/download/x/GBC.zip/GBC%2F..%2Fevil.zip">GBC/../evil.zip</a><td><td>2024<td id="size">10</tr>
        <tr><td><a href="//archive.org/download/x/GBC.zip/Z.zip">Z.zip</a><td><td>2024<td id="size">5</tr>
    """.trimIndent()

    @Test
    fun `members are read with their size and folders and unsafe names are skipped`() {
        assertEquals(
            listOf(ZipMember("GBC/A & B (USA).zip", 753495L), ZipMember("Z.zip", 5L)),
            ZipListing.parse(page),
        )
    }

    @Test
    fun `a page without rows gives no members`() {
        assertEquals(emptyList<ZipMember>(), ZipListing.parse("<html>nothing</html>"))
    }
}
