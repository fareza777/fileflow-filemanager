package com.fileflow.app.domain.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One installed application, and the files that make it up. */
@androidx.compose.runtime.Immutable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val sizeBytes: Long,
    val isSystem: Boolean,
    /** The base APK first, then every split. */
    val apkPaths: List<String>,
) {
    val splitCount: Int get() = (apkPaths.size - 1).coerceAtLeast(0)
}

/** The result of backing one app up. */
@androidx.compose.runtime.Immutable
data class BackupResult(
    val label: String,
    val files: List<String>,
    val bytes: Long,
    val failure: String? = null,
)

/**
 * Installed apps, as files.
 *
 * An APK is a file like any other, and a phone already holds a copy of every
 * one of them — a backup is a copy out of a directory the PackageManager will
 * name, not a download. Splits are the part worth getting right: a modern app
 * is a base APK plus configuration splits, and copying only the base produces a
 * file that installs as a crash. Everything the package owns is copied, and the
 * set is named so the group is obvious.
 */
@Singleton
class AppBackup @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val packageManager: PackageManager get() = context.packageManager

    suspend fun installed(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val apps = runCatching {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        apps.mapNotNull { info ->
            val application = info.applicationInfo ?: return@mapNotNull null
            val apks = apkPathsOf(application)
            if (apks.isEmpty()) return@mapNotNull null
            val size = apks.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { packageManager.getApplicationLabel(application).toString() }
                    .getOrDefault(info.packageName),
                versionName = info.versionName ?: "",
                sizeBytes = size,
                isSystem = (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                apkPaths = apks,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Where the installer keeps them: base first, then splits. */
    private fun apkPathsOf(application: ApplicationInfo): List<String> {
        val paths = mutableListOf<String>()
        application.sourceDir?.let { paths += it }
        application.splitSourceDirs?.let { splits -> splits.forEach { paths += it } }
        application.publicSourceDir?.takeIf { it !in paths }?.let { paths += it }
        return paths.filter { File(it).isFile }
    }

    /**
     * Copies an app into [destination], base APK and every split. The names keep
     * the package and version together so a folder of backups reads as a list.
     */
    suspend fun export(
        app: InstalledApp,
        destination: File,
        onProgress: (copied: Long, total: Long, name: String) -> Unit = { _, _, _ -> },
    ): BackupResult = withContext(Dispatchers.IO) {
        destination.mkdirs()
        val baseName = buildString {
            append(app.label.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { app.packageName })
            append('-')
            append(app.versionName.ifEmpty { "unknown" })
        }
        val copied = mutableListOf<String>()
        var bytes = 0L

        try {
            app.apkPaths.forEachIndexed { index, path ->
                currentCoroutineContext().ensureActive()
                val source = File(path)
                val suffix = if (index == 0) "" else ".split${index}"
                val target = File(destination, "$baseName$suffix.apk")
                val partial = File(destination, "${target.name}.fileflow-part")
                source.inputStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            bytes += read
                            onProgress(bytes, app.sizeBytes, target.name)
                        }
                    }
                }
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                copied += target.absolutePath
            }
            BackupResult(app.label, copied, bytes)
        } catch (error: Throwable) {
            BackupResult(app.label, copied, bytes, error.message ?: "Could not copy that app")
        }
    }
}
