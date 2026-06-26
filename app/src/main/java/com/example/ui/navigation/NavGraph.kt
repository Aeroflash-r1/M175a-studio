package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.ui.screens.about.AboutScreen
import com.example.ui.screens.history.HistoryScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.printer.PrinterScreen
import com.example.ui.screens.scanner.ScannerScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.usb.UsbScreen

/**
 * Main navigation graph for the application.
 * Manages routing between all core screens.
 */
@Composable
fun MainNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(onNavigate = { route -> navController.navigate(route) })
        }
        composable(Screen.Printer.route) {
            PrinterScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Scanner.route) {
            ScannerScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.History.route) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Usb.route) {
            UsbScreen(onBack = { navController.popBackStack() })
        }
    }
}
