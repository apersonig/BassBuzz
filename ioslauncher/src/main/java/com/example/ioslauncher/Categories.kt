package com.example.ioslauncher

import android.content.pm.ApplicationInfo

/** App Library groups, named the way iOS names them. */
object Categories {
    val ORDER = listOf("Social", "Productivity", "Entertainment", "Creativity", "Games", "Reading", "Travel", "Utilities")

    fun nameOf(category: Int): String = when (category) {
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
        ApplicationInfo.CATEGORY_IMAGE -> "Creativity"
        ApplicationInfo.CATEGORY_GAME -> "Games"
        ApplicationInfo.CATEGORY_NEWS -> "Reading"
        ApplicationInfo.CATEGORY_MAPS -> "Travel"
        else -> "Utilities"
    }
}
