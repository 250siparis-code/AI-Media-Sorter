package com.aimediasorter.app.ui.navigation

sealed class ScreenRoute(val route: String) {
    data object Welcome : ScreenRoute("welcome")
    data object SourceDestination : ScreenRoute("source_destination")
    data object CategoryManagement : ScreenRoute("category_management")
    data object AnalysisProgress : ScreenRoute("analysis_progress")
    data object AnalysisResults : ScreenRoute("analysis_results")
    data object MediaDetails : ScreenRoute("media_details/{itemId}") {
        fun createRoute(itemId: Long) = "media_details/$itemId"
    }
    data object FileTransfer : ScreenRoute("file_transfer")
    data object Settings : ScreenRoute("settings")
}
