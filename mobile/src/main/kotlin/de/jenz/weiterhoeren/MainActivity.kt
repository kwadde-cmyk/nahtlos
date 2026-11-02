package de.jenz.weiterhoeren

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import de.jenz.weiterhoeren.ui.EpisodesScreen
import de.jenz.weiterhoeren.ui.HomeScreen
import de.jenz.weiterhoeren.ui.HomeViewModel
import de.jenz.weiterhoeren.ui.MiniPlayer
import de.jenz.weiterhoeren.ui.PlayerScreen
import de.jenz.weiterhoeren.ui.SettingsScreen
import de.jenz.weiterhoeren.ui.WeiterTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            WeiterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val player by homeViewModel.playerState.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val showMini = !player.episodeKey.isNullOrBlank() && !route.startsWith("player")
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) homeViewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.weight(1f)) {
            composable("home") {
                HomeScreen(
                    onOpenPodcast = { id -> nav.navigate("podcast/${Uri.encode(id)}") },
                    onOpenPlayer = { key ->
                        val encoded = key?.let { Uri.encode(it) }
                        nav.navigate(if (encoded == null) "player" else "player?episode=$encoded")
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    viewModel = homeViewModel,
                )
            }
            composable(
                "podcast/{podcastId}",
                arguments = listOf(navArgument("podcastId") { type = NavType.StringType }),
            ) {
                EpisodesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { key -> nav.navigate("player?episode=${Uri.encode(key)}") },
                )
            }
            composable(
                "player?episode={episode}",
                arguments = listOf(navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }),
            ) {
                PlayerScreen(onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
        if (showMini) {
            MiniPlayer(
                snapshot = player,
                onClick = { nav.navigate("player?episode=${Uri.encode(player.episodeKey)}") },
                onToggle = { homeViewModel.togglePlayback() },
            )
        }
    }
}
