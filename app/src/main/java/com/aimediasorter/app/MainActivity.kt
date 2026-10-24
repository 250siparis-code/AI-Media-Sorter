package com.aimediasorter.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.aimediasorter.app.ui.navigation.AIMediaSorterNavHost
import com.aimediasorter.app.ui.theme.AIMediaSorterTheme
import com.aimediasorter.app.ui.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        com.aimediasorter.app.data.diagnostics.Diagnostics.operation(com.aimediasorter.app.domain.diagnostics.DiagnosticKind.PERMISSION)
            .event(com.aimediasorter.app.domain.diagnostics.DiagnosticStage.PERMISSION_CHECK,
                if(result.values.all { it }) com.aimediasorter.app.domain.diagnostics.DiagnosticStatus.SUCCEEDED else com.aimediasorter.app.domain.diagnostics.DiagnosticStatus.FAILED)
        // Storage Access Framework (OpenDocumentTree) works even without broad media permissions,
        // while READ_MEDIA_IMAGES / VIDEO + ACCESS_MEDIA_LOCATION preserve unredacted EXIF tags.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        requestMediaPermissionsIfNeeded()

        setContent {
            AIMediaSorterTheme {
                AIMediaSorterNavHost(viewModel = viewModel)
            }
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
