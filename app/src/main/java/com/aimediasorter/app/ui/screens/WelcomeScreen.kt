package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.data.model.OperationMode
import com.aimediasorter.app.ui.theme.*

@Composable
fun WelcomeScreen(
    operationMode: OperationMode,
    analyzedItemCount: Int,
    onStartSorting: () -> Unit,
    onViewResults: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = PrimaryPurpleContainer,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.5f))
            ) {
                Text(
                    text = stringResource(R.string.welcome_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryPurpleLight,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.nav_settings),
                    tint = TextPrimaryWhite
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // App Logo Emblem
        Surface(
            modifier = Modifier.size(84.dp),
            shape = CircleShape,
            color = PrimaryPurpleContainer,
            border = BorderStroke(2.dp, PrimaryPurple)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = PrimaryPurpleLight,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.welcome_description),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondaryMuted
        )

        Spacer(modifier = Modifier.height(24.dp))

        FeatureHighlightCard(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.feature_ai_title),
            description = stringResource(R.string.feature_ai_desc)
        )

        Spacer(modifier = Modifier.height(12.dp))

        FeatureHighlightCard(
            icon = Icons.Default.CreateNewFolder,
            title = stringResource(R.string.feature_folders_title),
            description = stringResource(R.string.feature_folders_desc)
        )

        Spacer(modifier = Modifier.height(12.dp))

        FeatureHighlightCard(
            icon = Icons.Default.VerifiedUser,
            title = stringResource(R.string.feature_preserve_title),
            description = stringResource(R.string.feature_preserve_desc)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Mode indicator card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AmoledCardSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AmoledSubtleBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.welcome_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondaryMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (operationMode == OperationMode.ANALYSIS_ONLY) {
                        stringResource(R.string.mode_analysis_only)
                    } else {
                        stringResource(R.string.mode_move_enabled)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (operationMode == OperationMode.ANALYSIS_ONLY) StatusEmerald else PrimaryPurpleLight
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartSorting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text(
                text = stringResource(R.string.btn_start_sorting),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        if (analyzedItemCount > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onViewResults,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, PrimaryPurple)
            ) {
                Text(
                    text = stringResource(R.string.btn_resume_results, analyzedItemCount),
                    color = PrimaryPurpleLight
                )
            }
        }
    }
}

@Composable
private fun FeatureHighlightCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AmoledCardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AmoledSubtleBorder)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = PrimaryPurpleContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = PrimaryPurpleLight
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
