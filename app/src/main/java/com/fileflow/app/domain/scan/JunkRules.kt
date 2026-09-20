package com.fileflow.app.domain.scan

import com.fileflow.app.domain.Candidate

/**
 * One definition of "junk" shared by the cleaner and the home-screen summary,
 * so the number on the dashboard always matches the number in the tool.
 */
object JunkRules {

    val TEMP_EXTENSIONS = setOf(
        "tmp", "temp", "part", "crdownload", "partial", "cache", "bak", "old",
    )
    val LOG_EXTENSIONS = setOf("log", "stacktrace", "dmp", "crash", "anr")
    val INSTALLER_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm")

    const val THIRTY_DAYS = 30L * 86_400_000L

    fun isThumbnailPath(path: String): Boolean {
        val normalized = path.lowercase()
        return normalized.contains("/.thumbnails/") ||
            normalized.contains("/thumbnails/") ||
            (normalized.contains("/cache/") && normalized.contains("thumb"))
    }

    fun isTemp(candidate: Candidate): Boolean = candidate.extension in TEMP_EXTENSIONS

    fun isLog(candidate: Candidate): Boolean = candidate.extension in LOG_EXTENSIONS

    fun isStaleInstaller(candidate: Candidate, now: Long = System.currentTimeMillis()): Boolean =
        candidate.extension in INSTALLER_EXTENSIONS && now - candidate.lastModified > THIRTY_DAYS

    fun classify(candidate: Candidate, now: Long = System.currentTimeMillis()): JunkKind? = when {
        isThumbnailPath(candidate.path) -> JunkKind.THUMBNAILS
        candidate.sizeBytes == 0L -> JunkKind.EMPTY_FILES
        isTemp(candidate) -> JunkKind.TEMP
        isLog(candidate) -> JunkKind.LOGS
        isStaleInstaller(candidate, now) -> JunkKind.OLD_APK
        else -> null
    }
}
