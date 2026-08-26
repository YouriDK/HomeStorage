package com.boxpix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.boxpix.app.ui.explorer.ExplorerScreen
import com.boxpix.app.ui.onboarding.OnboardingScreen
import com.boxpix.app.ui.search.SearchScreen
import com.boxpix.app.ui.settings.ManageTagsScreen
import com.boxpix.app.ui.settings.SettingsScreen
import com.boxpix.app.ui.theme.boxpixColors
import com.boxpix.app.ui.trash.TrashScreen
import com.boxpix.app.ui.viewer.ViewerScreen
import com.boxpix.app.ui.worker.WorkerScreen

@Composable
fun BoxpixRoot(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(boxpixColors.bg),
    ) {
        when (state) {
            RootViewModel.RootState.Loading -> Unit
            RootViewModel.RootState.Onboarding -> OnboardingScreen()
            RootViewModel.RootState.Main -> MainNavHost()
        }
    }
}

@Composable
private fun MainNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "explorer") {
        composable("explorer") {
            ExplorerScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenViewer = { nav.navigate("viewer") },
                onOpenSearch = { nav.navigate("search") },
            )
        }
        composable("viewer") {
            ViewerScreen(onBack = { nav.popBackStack() })
        }
        composable("search") {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenViewer = { nav.navigate("viewer") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenTrash = { nav.navigate("trash") },
                onOpenTags = { nav.navigate("managetags") },
                onOpenWorker = { nav.navigate("worker") },
            )
        }
        composable("worker") {
            WorkerScreen(onBack = { nav.popBackStack() })
        }
        composable("managetags") {
            ManageTagsScreen(onBack = { nav.popBackStack() })
        }
        composable("trash") {
            TrashScreen(onBack = { nav.popBackStack() })
        }
    }
}
