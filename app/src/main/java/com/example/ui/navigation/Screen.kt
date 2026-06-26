package com.example.ui.navigation

/**
 * Defines all available routes within the application.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Printer : Screen("printer")
    object Scanner : Screen("scanner")
    object History : Screen("history")
    object Settings : Screen("settings")
    object About : Screen("about")
    object Usb : Screen("usb")
}
