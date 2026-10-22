package com.srtcolorsection.studio.export

import com.srtcolorsection.studio.domain.AppSettings
import com.srtcolorsection.studio.domain.Project

enum class ExportKind { PNG_ALL, PNG_SELECTED, VIDEO }

data class ExportRequest(
    val kind: ExportKind,
    val project: Project,
    val settings: AppSettings,
    val sectionIds: List<String> = emptyList(),
    val videoDestinationUri: String? = null,
    val pngTreeUri: String? = null,
    val confirmedHeavy: Boolean = false,
)

data class ExportProgress(
    val phase: String,
    val current: Int,
    val total: Int,
    val filename: String,
    val percent: Int,
    val success: Int,
    val failed: Int,
    val detail: String = "",
)

sealed class ExportUiState {
    data object Idle : ExportUiState()
    data class Running(val progress: ExportProgress) : ExportUiState()
    data class Succeeded(
        val summary: String,
        val diagnostic: String,
        val shareUris: List<String>,
        val mime: String,
    ) : ExportUiState()
    data class Failed(val message: String, val diagnostic: String) : ExportUiState()
    data class Cancelled(val message: String) : ExportUiState()
}

data class SectionImageResult(
    val filename: String,
    val uri: String?,
    val error: String?,
)
