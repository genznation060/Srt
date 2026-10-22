package com.srtcolorsection.studio.data

import android.content.Context
import com.srtcolorsection.studio.domain.AppSettings
import com.srtcolorsection.studio.domain.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class JsonStores(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val projectFile = File(context.filesDir, "project.json")
    private val settingsFile = File(context.filesDir, "settings.json")

    fun loadProject(): Project? = projectFile.takeIf { it.exists() }?.let { file ->
        runCatching { json.decodeFromString<Project>(file.readText()) }.getOrNull()
    }

    fun saveProject(project: Project) {
        projectFile.writeText(json.encodeToString(project))
    }

    fun loadSettings(): AppSettings = settingsFile.takeIf { it.exists() }?.let { file ->
        runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrNull()
    } ?: AppSettings()

    fun saveSettings(settings: AppSettings) {
        settingsFile.writeText(json.encodeToString(settings))
    }
}
