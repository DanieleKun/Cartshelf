package app.archivefetcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.archivefetcher.App
import app.archivefetcher.data.RomNaming
import coil3.compose.AsyncImage
import java.net.URLEncoder

/**
 * Box art from the libretro thumbnails project, the archive RetroArch uses, looked up by console and game name.
 * The letter tile stays underneath: it shows while the picture loads and whenever there is no picture.
 */
@Composable
fun GameCover(consoleName: String, fileName: String, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember { (context.applicationContext as App).graph.imageLoader }
    val url = remember(consoleName, fileName) { CoverUrls.boxArt(consoleName, fileName) }
    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        Monogram(title, size = 52.dp)
        AsyncImage(
            model = url,
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

object CoverUrls {
    private const val BASE = "https://raw.githubusercontent.com/libretro-thumbnails"

    /** Characters libretro replaces with `_` in thumbnail file names. */
    private val replaced = Regex("""[&*/:`<>?\\|"]""")

    fun boxArt(consoleName: String, fileName: String): String {
        val name = replaced.replace(RomNaming.stemOf(fileName), "_")
        // One repository per console: "Nintendo - Game Boy" lives in libretro-thumbnails/Nintendo_-_Game_Boy.
        return "$BASE/${encode(consoleName.replace(' ', '_'))}/master/Named_Boxarts/${encode(name)}.png"
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}
