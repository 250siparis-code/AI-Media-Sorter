package com.aimediasorter.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aimediasorter.app.R
import com.aimediasorter.app.data.model.DefaultCategories

/** Translate untouched built-in labels without renaming user categories or destination folders. */
@Composable
fun categoryDisplayName(id: String, storedName: String): String {
    val original = DefaultCategories.ALL.firstOrNull { it.id == id } ?: return storedName
    if (storedName != original.defaultName) return storedName
    val resource = when (id) {
        "cat_people" -> R.string.category_display_people
        "cat_food" -> R.string.category_display_food
        "cat_vehicles" -> R.string.category_display_vehicles
        "cat_animals" -> R.string.category_display_animals
        "cat_nature" -> R.string.category_display_nature
        "cat_buildings" -> R.string.category_display_buildings
        "cat_technology" -> R.string.category_display_technology
        "cat_text" -> R.string.category_display_text
        "cat_other" -> R.string.category_display_other
        else -> return storedName
    }
    return stringResource(resource)
}
