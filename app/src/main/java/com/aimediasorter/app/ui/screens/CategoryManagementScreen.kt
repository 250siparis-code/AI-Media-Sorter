package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.ui.theme.*

@Composable
fun CategoryManagementScreen(
    categories: List<CategoryEntity>,
    onToggleEnabled: (CategoryEntity) -> Unit,
    onSaveCategory: (String?, String, String, String, Int) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onStartAnalysis: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.categories_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.categories_subtitle),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            FilledTonalButton(
                onClick = {
                    editingCategory = null
                    showEditDialog = true
                },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = PrimaryPurpleContainer)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = PrimaryPurpleLight)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.btn_add_category), color = PrimaryPurpleLight)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categories, key = { it.id }) { category ->
                CategoryRowCard(
                    category = category,
                    onToggle = { onToggleEnabled(category) },
                    onEdit = {
                        editingCategory = category
                        showEditDialog = true
                    },
                    onDelete = if (!category.isDefault) {
                        { onDeleteCategory(category.id) }
                    } else null
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onStartAnalysis,
            enabled = categories.any { it.isEnabled },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text(
                text = stringResource(R.string.btn_start_analysis),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }

    if (showEditDialog) {
        CategoryEditDialog(
            initial = editingCategory,
            onDismiss = { showEditDialog = false },
            onConfirm = { id, name, desc, folder, prio ->
                onSaveCategory(id, name, desc, folder, prio)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun CategoryRowCard(
    category: CategoryEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AmoledCardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AmoledSubtleBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(category.colorHex).copy(alpha = 0.22f),
                border = BorderStroke(1.dp, Color(category.colorHex))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color(category.colorHex)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${stringResource(R.string.category_subfolder_fmt, category.destinationSubfolder)} • ${stringResource(R.string.category_priority_fmt, category.priority)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryPurpleLight
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.dialog_edit_category_title),
                    tint = TextSecondaryMuted
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.btn_delete),
                        tint = StatusCoral
                    )
                }
            }
            Switch(
                checked = category.isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@Composable
private fun CategoryEditDialog(
    initial: CategoryEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String?, String, String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var folder by remember { mutableStateOf(initial?.destinationSubfolder ?: "") }
    val validFolder = runCatching { com.aimediasorter.app.domain.storage.SafeRelativePath.segments(folder.ifBlank { name }) }.isSuccess
    var priorityText by remember { mutableStateOf((initial?.priority ?: 7).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmoledElevatedSurface,
        title = {
            Text(
                text = if (initial == null) {
                    stringResource(R.string.dialog_add_category_title)
                } else {
                    stringResource(R.string.dialog_edit_category_title)
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_category_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.field_category_desc)) }
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text(stringResource(R.string.field_category_folder)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it },
                    label = { Text(stringResource(R.string.field_category_priority)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && validFolder,
                onClick = {
                    val prio = priorityText.toIntOrNull()?.coerceIn(1, 10) ?: 7
                    onConfirm(initial?.id, name, desc, folder.ifBlank { name }, prio)
                }
            ) {
                Text(stringResource(R.string.btn_save), color = PrimaryPurpleLight)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel), color = TextSecondaryMuted)
            }
        }
    )
}
