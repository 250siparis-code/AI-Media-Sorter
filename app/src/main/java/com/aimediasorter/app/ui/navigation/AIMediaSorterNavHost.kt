package com.aimediasorter.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aimediasorter.app.R
import com.aimediasorter.app.ui.screens.*
import com.aimediasorter.app.ui.theme.AmoledCardSurface
import com.aimediasorter.app.ui.theme.AmoledPureBlack
import com.aimediasorter.app.ui.theme.PrimaryPurple
import com.aimediasorter.app.ui.viewmodel.MainViewModel

@Composable
fun AIMediaSorterNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ScreenRoute.Welcome.route

    val settings by viewModel.settingsState.collectAsState()
    val categories by viewModel.categoriesState.collectAsState()
    val mediaItems by viewModel.mediaItemsState.collectAsState()
    val analysisProgress by viewModel.analysisProgress.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val sessions by viewModel.sessionsState.collectAsState()
    val exportMessage by viewModel.exportStatusMessage.collectAsState()
    val lastTermuxExport by viewModel.lastTermuxExportResult.collectAsState()

    Scaffold(
        containerColor = AmoledPureBlack,
        bottomBar = {
            NavigationBar(containerColor = AmoledCardSurface) {
                NavigationBarItem(
                    selected = currentRoute == ScreenRoute.Welcome.route,
                    onClick = { navController.navigate(ScreenRoute.Welcome.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_welcome)) }
                )
                NavigationBarItem(
                    selected = currentRoute == ScreenRoute.SourceDestination.route,
                    onClick = { navController.navigate(ScreenRoute.SourceDestination.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_folders)) }
                )
                NavigationBarItem(
                    selected = currentRoute == ScreenRoute.CategoryManagement.route,
                    onClick = { navController.navigate(ScreenRoute.CategoryManagement.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Category, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_categories)) }
                )
                NavigationBarItem(
                    selected = currentRoute == ScreenRoute.AnalysisResults.route,
                    onClick = { navController.navigate(ScreenRoute.AnalysisResults.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.GridView, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_results)) }
                )
                NavigationBarItem(
                    selected = currentRoute == ScreenRoute.FileTransfer.route,
                    onClick = { navController.navigate(ScreenRoute.FileTransfer.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_transfer)) }
                )
                NavigationBarItem(
                    selected = currentRoute == ScreenRoute.Settings.route,
                    onClick = { navController.navigate(ScreenRoute.Settings.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ScreenRoute.Welcome.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(ScreenRoute.Welcome.route) {
                WelcomeScreen(
                    operationMode = settings.operationMode,
                    analyzedItemCount = mediaItems.size,
                    onStartSorting = { navController.navigate(ScreenRoute.SourceDestination.route) },
                    onViewResults = { navController.navigate(ScreenRoute.AnalysisResults.route) },
                    onOpenSettings = { navController.navigate(ScreenRoute.Settings.route) }
                )
            }

            composable(ScreenRoute.SourceDestination.route) {
                val loopErr = viewModel.isFolderSelectionLoop(settings.sourceUri, settings.destinationUri)
                SourceDestinationScreen(
                    settings = settings,
                    hasLoopError = loopErr,
                    onSelectSource = viewModel::selectSourceFolder,
                    onSelectDestination = viewModel::selectDestinationFolder,
                    onMediaTypeChange = viewModel::setMediaTypeFilter,
                    onSubfoldersToggle = viewModel::setIncludeSubfolders,
                    onTransferMethodChange = viewModel::setTransferMethod,
                    onToggleExtension = viewModel::toggleSupportedExtension,
                    onContinue = { navController.navigate(ScreenRoute.CategoryManagement.route) }
                )
            }

            composable(ScreenRoute.CategoryManagement.route) {
                CategoryManagementScreen(
                    categories = categories,
                    onToggleEnabled = viewModel::toggleCategoryEnabled,
                    onSaveCategory = viewModel::saveCategory,
                    onDeleteCategory = viewModel::deleteCustomCategory,
                    onStartAnalysis = {
                        viewModel.startAnalysis()
                        navController.navigate(ScreenRoute.AnalysisProgress.route)
                    }
                )
            }

            composable(ScreenRoute.AnalysisProgress.route) {
                AnalysisProgressScreen(
                    progress = analysisProgress,
                    onTogglePause = viewModel::togglePauseAnalysis,
                    onCancel = {
                        viewModel.cancelAnalysis()
                        navController.popBackStack()
                    },
                    onViewResults = { navController.navigate(ScreenRoute.AnalysisResults.route) }
                )
            }

            composable(ScreenRoute.AnalysisResults.route) {
                AnalysisResultsScreen(
                    mediaItems = mediaItems,
                    categories = categories,
                    lastExportResult = lastTermuxExport,
                    onInspectItem = { itemId ->
                        navController.navigate(ScreenRoute.MediaDetails.createRoute(itemId))
                    },
                    onQuickReassignCategory = viewModel::reassignMediaCategory,
                    onExportCsv = viewModel::exportOperationsCsv,
                    onExportJson = viewModel::exportOperationsJson,
                    onExportMoveScript = viewModel::exportOperationsMoveScript,
                    onExportCopyScript = viewModel::exportOperationsCopyScript,
                    onExportAll = viewModel::exportOperationsAll,
                    onProceedToTransfer = { navController.navigate(ScreenRoute.FileTransfer.route) }
                )
            }

            composable(
                route = ScreenRoute.MediaDetails.route,
                arguments = listOf(navArgument("itemId") { type = NavType.LongType })
            ) { entry ->
                val itemId = entry.arguments?.getLong("itemId") ?: 0L
                val selectedItem = mediaItems.firstOrNull { it.id == itemId }
                MediaDetailsScreen(
                    item = selectedItem,
                    categories = categories,
                    onReassignCategory = viewModel::reassignMediaCategory,
                    onToggleExclude = viewModel::setExcludeMediaItem,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(ScreenRoute.FileTransfer.route) {
                FileTransferScreen(
                    mediaItems = mediaItems,
                    operationMode = settings.operationMode,
                    transferMethod = settings.transferMethod,
                    transferProgress = transferProgress,
                    onConfirmAndExecuteTransfer = { includeReview ->
                        viewModel.executeTransfers(includeReview)
                    }
                )
            }

            composable(ScreenRoute.Settings.route) {
                SettingsScreen(
                    settings = settings,
                    sessions = sessions,
                    exportStatusMessage = exportMessage,
                    onLanguageChange = viewModel::setLanguage,
                    onAiEngineChange = viewModel::setAiEngineMode,
                    onHardwareAccelChange = viewModel::setHardwareAcceleration,
                    onConfidenceChange = viewModel::setConfidenceThreshold,
                    onTimestampPolicyChange = viewModel::setTimestampPolicy,
                    onCollisionPolicyChange = viewModel::setCollisionPolicy,
                    onPreviewBeforeMovingChange = viewModel::setPreviewBeforeMoving,
                    onExportCsv = viewModel::exportCsvReport,
                    onExportJson = viewModel::exportJsonReport
                )
            }
        }
    }
}
