package com.aimediasorter.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AIMediaSorterNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ScreenRoute.Welcome.route

    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val categories by viewModel.categoriesState.collectAsStateWithLifecycle()
    val mediaItems by viewModel.mediaItemsState.collectAsStateWithLifecycle()
    val analysisProgress by viewModel.analysisProgress.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val sessions by viewModel.sessionsState.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportStatusMessage.collectAsStateWithLifecycle()
    val loopError by viewModel.folderLoopError.collectAsStateWithLifecycle()
    val lastTermuxExport by viewModel.lastTermuxExportResult.collectAsStateWithLifecycle()

    val galleryLinks by viewModel.galleryLinksState.collectAsStateWithLifecycle()
    val plans by viewModel.physicalPlansState.collectAsStateWithLifecycle()
    val operationPreview by viewModel.operationPreview.collectAsStateWithLifecycle()
    var operationIds by remember { mutableStateOf(emptySet<Long>()) }
    if(operationIds.isNotEmpty()) OperationIntentDialog(operationIds,mediaItems,categories,galleryLinks,
        onPreview=viewModel::requestOperationPreview,onDismiss={operationIds=emptySet()})
    operationPreview?.let { OperationPreviewDialog(it,viewModel::confirmOperationPreview,viewModel::dismissOperationPreview) }

    val cloudUi by viewModel.cloudUi.collectAsStateWithLifecycle()
    val cloudConsent by viewModel.cloudConsent.collectAsStateWithLifecycle()
    if (cloudConsent) CloudConsentDialog(settings.cloudOptions, cloudUi.hasKey, cloudUi.modelVerified,
        onConfirm=viewModel::approveCloudBatch,
        onDismiss={ viewModel.dismissCloudConsent(); navController.popBackStack() })

    val snackbar = remember { SnackbarHostState() }
    val statusText = exportMessage?.let { status ->
        when (status) {
            "EXPORT_READY" -> stringResource(R.string.export_ready)
            "EXPORT_SAVED" -> stringResource(R.string.export_saved)
            "ORGANIZE_REQUIRED" -> stringResource(R.string.transfer_analysis_mode_banner)
            else -> stringResource(R.string.action_failed) + " " + status.removePrefix("ERROR:")
        }
    }
    LaunchedEffect(statusText) {
        if (statusText != null) {
            snackbar.showSnackbar(statusText)
            viewModel.dismissStatusMessage()
        }
    }
    Scaffold(
        modifier=Modifier.semantics { testTagsAsResourceId=true },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = AmoledPureBlack,
        bottomBar = {
            NavigationBar(containerColor = AmoledCardSurface) {
                val tabs=listOf(
                    Triple(ScreenRoute.Welcome,R.string.nav_analyze,Icons.Default.Home),
                    Triple(ScreenRoute.AnalysisResults,R.string.virtual_folders,Icons.Default.GridView),
                    Triple(ScreenRoute.Review,R.string.virtual_review,Icons.Default.RateReview),
                    Triple(ScreenRoute.FileTransfer,R.string.nav_operations,Icons.AutoMirrored.Filled.DriveFileMove),
                    Triple(ScreenRoute.Settings,R.string.nav_settings,Icons.Default.Settings)
                )
                tabs.forEach { (screen,label,icon) ->
                    NavigationBarItem(modifier=if(screen==ScreenRoute.Settings) Modifier.testTag("nav-settings") else Modifier,
                        selected=currentRoute==screen.route,
                        onClick={navController.navigate(screen.route) { launchSingleTop=true }},
                        icon={Icon(icon,contentDescription=null)},label={Text(stringResource(label),maxLines=1)})
                }
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
                val loopErr = loopError
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
                VirtualGalleryScreen(mediaItems,categories,galleryLinks,
                    onInspect={navController.navigate(ScreenRoute.MediaDetails.createRoute(it))},
                    onCorrect=viewModel::updateVirtualCategories,onReanalyze=viewModel::requestReanalysis,
                    onOrganize={operationIds=it},onAnalyze={navController.navigate(ScreenRoute.SourceDestination.route)},
                    onAdvanced={navController.navigate(ScreenRoute.AdvancedResults.route)})
            }
            composable(ScreenRoute.Review.route) {
                VirtualGalleryScreen(mediaItems,categories,galleryLinks,initialFolder="__review__",
                    onInspect={navController.navigate(ScreenRoute.MediaDetails.createRoute(it))},
                    onCorrect=viewModel::updateVirtualCategories,onReanalyze=viewModel::requestReanalysis,
                    onOrganize={operationIds=it},onAnalyze={navController.navigate(ScreenRoute.SourceDestination.route)},
                    onAdvanced={navController.navigate(ScreenRoute.AdvancedResults.route)})
            }
            composable(ScreenRoute.AdvancedResults.route) {
                AnalysisResultsScreen(
                    mediaItems = mediaItems,
                    categories = categories,
                    lastExportResult = lastTermuxExport,
                    onSaveExport = viewModel::saveLastExport,
                    isAnalysisRunning = analysisProgress.isRunning,
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
                MediaReviewScreen(selectedItem,categories,galleryLinks,
                    onSave=viewModel::updateVirtualCategories,onReanalyze=viewModel::requestReanalysis,
                    onOrganize={operationIds=it},onExclude=viewModel::setExcludeMediaItem,
                    onBack={navController.popBackStack()})
            }

            composable(ScreenRoute.FileTransfer.route) {
                PhysicalOperationsScreen(plans,mediaItems,transferProgress,viewModel::cancelTransfers,
                    onGallery={navController.navigate(ScreenRoute.AnalysisResults.route)},
                    onDestination={navController.navigate(ScreenRoute.SourceDestination.route)})
            }

            composable(ScreenRoute.Settings.route) {
                SettingsScreen(
                    settings = settings,
                    cloudUi = cloudUi,
                    onCloudOptions = viewModel::setCloudOptions,
                    onSaveKey = viewModel::saveGeminiKey,
                    onClearKey = viewModel::clearGeminiKey,
                    onRefreshModels = viewModel::refreshGeminiModels,
                    onRefreshCloudState = viewModel::refreshCloudLocalState,
                    onTestModel = viewModel::testGeminiModel,
                    sessions = sessions,
                    exportStatusMessage = exportMessage,
                    onLanguageChange = viewModel::setLanguage,
                    onAiEngineChange = viewModel::setAiEngineMode,
                    onHardwareAccelChange = viewModel::setHardwareAcceleration,
                    onConfidenceChange = viewModel::setConfidenceThreshold,
                    onTimestampPolicyChange = viewModel::setTimestampPolicy,
                    onCollisionPolicyChange = viewModel::setCollisionPolicy,
                    onOperationModeChange = viewModel::setOperationMode,
                    canSaveExport = lastTermuxExport != null,
                    onSaveExport = viewModel::saveLastExport,
                    onExportCsv = viewModel::exportCsvReport,
                    onExportJson = viewModel::exportJsonReport
                )
            }
        }
    }
}
