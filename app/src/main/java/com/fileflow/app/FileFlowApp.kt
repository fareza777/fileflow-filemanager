package com.fileflow.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.fileflow.app.core.analysis.PdfText
import com.fileflow.app.di.ApplicationScope
import com.fileflow.app.data.repo.FileIndexRepository
import com.fileflow.app.domain.rules.RuleScheduler
import com.fileflow.app.domain.scan.FileIndexScheduler
import com.fileflow.app.domain.scan.PartialSweeper
import com.fileflow.app.domain.scan.TrashJanitor
import com.fileflow.app.service.ModelDownloadService
import com.fileflow.app.service.PhotoWorkService
import com.fileflow.app.service.TransferService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltAndroidApp
class FileFlowApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var ruleScheduler: RuleScheduler
    @Inject lateinit var trashJanitor: TrashJanitor
    @Inject lateinit var partialSweeper: PartialSweeper
    @Inject lateinit var indexScheduler: FileIndexScheduler
    @Inject lateinit var indexRepository: FileIndexRepository
    @Inject lateinit var syncScheduler: com.fileflow.app.domain.sync.SyncScheduler
    @Inject lateinit var fileActions: com.fileflow.app.data.repo.FileActionsRepository

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * Thumbnails are the one thing this app loads thousands of, so the shared
     * loader is configured rather than left at its defaults.
     *
     * Coil's stock singleton has no video decoder, so a video row could only
     * ever show a play glyph. Registering it here also means the per-request
     * `.decoderFactory(...)` calls can go away, which matters because building a
     * decoder per row is work done on every scroll frame.
     *
     * The disk cache is capped explicitly: the default is a percentage of free
     * space, which on a nearly-full phone is either nothing or a multi-gigabyte
     * folder of JPEGs the app then has to explain in the storage analyzer.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(CROSSFADE_MS)
        .memoryCache {
            MemoryCache.Builder(this)
                // Coil's default is 25% of the app's available heap already;
                // naming it keeps the intent obvious if it is ever tuned.
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbnails"))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        // Channels exist before anything can post to them: a foreground service
        // that is the first to use a channel is rejected by the framework, and
        // that rejection kills the process.
        runCatching { ModelDownloadService.ensureChannelEarly(this) }
        runCatching { PhotoWorkService.ensureChannelEarly(this) }
        runCatching { TransferService.ensureChannelEarly(this) }
        // PDFBox reads its font and encoding tables from its own assets, and the
        // loader needs a context to reach them. Doing it here rather than on the
        // first PDF keeps that work off the indexing path, where it would be paid
        // once per document if the initialisation ever failed.
        runCatching { PdfText.initialise(this) }
        // Periodic rule work does not survive an app update or a long powered-off
        // stretch, so it is re-armed on every process start. The quiet period
        // lets the first frame land before WorkManager and the trash sweep
        // start competing for disk IO.
        applicationScope.launch {
            delay(STARTUP_QUIET_PERIOD_MS)
            runCatching { ruleScheduler.syncAll() }
            runCatching { trashJanitor.purgeExpired() }
            // A hard kill can leave a half-written file from an interrupted
            // copy; nothing else will ever clean it up.
            runCatching { partialSweeper.sweep() }
            // Search is only fast once the index exists, so build it once, on
            // the first launch — but only if storage is idle. Firing a full
            // index build three seconds in, while the home screen was starting
            // its own sweep of the same directories, made both slower than
            // either would have been alone.
            runCatching { indexScheduler.ensureScheduled() }
            runCatching {
                // Build the index on first launch, and refresh it once it has
                // gone stale. The periodic worker only fires on IDLE + CHARGING,
                // so a phone that is rarely both could carry an index weeks old
                // while search kept answering from it with total confidence.
                // `rebuildIfIdle` yields to whatever the home screen is already
                // doing, so this never competes with the launch sweep.
                if (!indexRepository.hasContent() || !indexRepository.isFresh()) {
                    indexRepository.rebuildIfIdle()
                }
            }
            // Undone journal entries pile up forever otherwise.
            runCatching { fileActions.pruneJournal() }
            // Saved folder pairs are re-armed here for the same reason the rules
            // are: a periodic request does not survive an update or a long
            // powered-off stretch, and a sync that quietly stopped is worse than
            // one that was never set up.
            runCatching { syncScheduler.syncAll() }
        }
    }

    private companion object {
        /**
         * Long enough that the first frame, the entrance animation and the home
         * screen's own metrics all land before the maintenance pass starts.
         *
         * This was three seconds, which put WorkManager's start-up and the trash
         * sweep on top of the home screen's storage walk — three disk-heavy jobs
         * competing during launch. The work is idempotent and nothing depends on
         * it being early, so it can afford to wait.
         */
        const val STARTUP_QUIET_PERIOD_MS = 7_000L

        /** Long enough to read as a fade, short enough not to lag a fling. */
        const val CROSSFADE_MS = 160

        /**
         * Thumbnails are all small decodes, so 96 MB holds tens of thousands of
         * them — far more than a session scrolls past.
         */
        const val DISK_CACHE_BYTES = 96L * 1024 * 1024
    }
}
