package app.archivefetcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import app.archivefetcher.data.Prefs
import app.archivefetcher.ui.AppTheme
import app.archivefetcher.ui.BrowseHost
import app.archivefetcher.ui.MainViewModel
import app.archivefetcher.ui.Palette

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional: downloads work without it */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Localizer.wrap(newBase, Prefs(newBase).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is what lets Compose see the keyboard insets, so the search field can leave edit mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleAddonLink(intent)
        setContent {
            AppTheme {
                BackHandler(enabled = viewModel.stack.size > 1) { viewModel.back() }
                BrowseHost(
                    viewModel,
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Background)
                        .safeDrawingPadding(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAddonLink(intent)
    }

    /** archivefetcher://addon?url=… only pre-fills the form; installing always needs the user's confirmation. */
    private fun handleAddonLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "archivefetcher" || data.host != "addon") return
        data.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let(viewModel::requestAddon)
    }
}
