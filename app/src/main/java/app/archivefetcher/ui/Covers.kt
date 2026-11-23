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
import app.archivefetcher.addon.ConsoleInfo
import app.archivefetcher.data.CoverSources
import coil3.compose.AsyncImage

/**
 * Box art from the libretro thumbnails project, the archive RetroArch uses, looked up by console and game name.
 * The letter tile stays underneath: it shows while the picture loads and whenever there is no picture.
 */
@Composable
fun GameCover(console: ConsoleInfo, fileName: String, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember { (context.applicationContext as App).graph.imageLoader }
    val url = remember(console, fileName) { CoverSources.boxArt(console, fileName) }
    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        Monogram(title, size = 52.dp)
        if (url != null) {
            AsyncImage(
                model = url,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
