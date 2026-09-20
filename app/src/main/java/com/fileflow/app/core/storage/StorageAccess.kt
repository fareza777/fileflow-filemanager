package com.fileflow.app.core.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import com.fileflow.app.core.model.VolumeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * All-files access is the only thing that makes a real file manager
     * possible on API 30+; below that the legacy read/write permissions are
     * enough. Nothing else in the app should branch on the SDK version.
     */
    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLegacyReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Even with all-files access, Android 11+ hides these two trees from
     * everyone. Surfacing them as normal folders would produce permanent
     * "permission denied" errors, so they are marked as locked instead.
     *
     * The check covers the directory itself *and* everything beneath it: an
     * endsWith test alone marked `/Android/data` as restricted but let
     * `/Android/data/com.whatsapp` through, so navigating one level deeper
     * looked allowed and then listed nothing.
     */
    fun isRestrictedPath(path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val normalized = path.replace('\\', '/').trimEnd('/')
        return RESTRICTED_SEGMENTS.any { segment ->
            normalized.endsWith(segment) || normalized.contains("$segment/")
        }
    }

    companion object {
        /**
         * The trees Android 11+ walls off from every app. Shared with the
         * MediaStore readers, which have to recognise the same paths without a
         * Context of their own.
         */
        val RESTRICTED_SEGMENTS = listOf(
            "/Android/data",
            "/Android/obb",
        )
    }
}

@Singleton
class VolumeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun volumes(): List<VolumeInfo> {
        val result = mutableListOf<VolumeInfo>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            manager?.storageVolumes?.forEach { volume ->
                val dir = volume.directory ?: return@forEach
                if (!dir.exists()) return@forEach
                val stats = runCatching { StatFs(dir.absolutePath) }.getOrNull() ?: return@forEach
                result += VolumeInfo(
                    id = volume.uuid ?: dir.absolutePath,
                    label = if (volume.isPrimary) "Internal storage" else "SD card",
                    rootPath = dir.absolutePath,
                    isRemovable = volume.isRemovable,
                    isPrimary = volume.isPrimary,
                    totalBytes = stats.totalBytes,
                    freeBytes = stats.availableBytes,
                )
            }
        }

        if (result.isEmpty()) {
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.exists()) {
                val stats = runCatching { StatFs(root.absolutePath) }.getOrNull()
                result += VolumeInfo(
                    id = "primary",
                    label = "Internal storage",
                    rootPath = root.absolutePath,
                    isRemovable = false,
                    isPrimary = true,
                    totalBytes = stats?.totalBytes ?: 0L,
                    freeBytes = stats?.availableBytes ?: 0L,
                )
            }
        }

        return result.sortedByDescending { it.isPrimary }
    }

    fun primaryRoot(): String =
        volumes().firstOrNull { it.isPrimary }?.rootPath
            ?: Environment.getExternalStorageDirectory()?.absolutePath
            ?: "/storage/emulated/0"

    /**
     * Every writable root. Rules that are not scoped to a folder walk these.
     */
    fun roots(): List<String> = volumes().map { it.rootPath }

    /**
     * The mount point ("/storage/emulated/0", "/storage/1A2B-3C4D") that a path
     * belongs to. Renames only stay instant while source and destination share
     * one, so this decides between renameTo and a real copy.
     */
    fun volumeRootOf(path: String): String {
        val normalized = path.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 && parts[0] == "storage" && parts[1] == "emulated" &&
                parts.size >= 3 -> "/storage/emulated/${parts[2]}"
            parts.size >= 2 && parts[0] == "storage" -> "/storage/${parts[1]}"
            normalsRootMatch(normalized) != null -> normalsRootMatch(normalized)!!
            else -> path.substringBeforeLast('/', path)
        }
    }

    private fun normalsRootMatch(path: String): String? =
        volumes().firstOrNull { path.startsWith(it.rootPath) }?.rootPath

    fun isSameVolume(a: String, b: String): Boolean = volumeRootOf(a) == volumeRootOf(b)

    fun siblingDirectory(root: String, vararg segments: String): File =
        File(root, segments.joinToString(File.separator))

    /**
     * Free space, or null when the volume cannot be measured. Null is not the
     * same as "unlimited": the caller decides whether to skip its space check,
     * rather than a failure silently reading as an enormous disk.
     */
    fun freeSpaceAt(path: String): Long? =
        runCatching { StatFs(path).availableBytes }.getOrNull()
}
