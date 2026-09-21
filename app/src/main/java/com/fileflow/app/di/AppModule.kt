package com.fileflow.app.di

import android.content.Context
import androidx.room.Room
import com.fileflow.app.data.db.ContentIndexDao
import com.fileflow.app.data.db.FileFlowDatabase
import com.fileflow.app.data.db.FileIndexDao
import com.fileflow.app.data.db.JournalDao
import com.fileflow.app.data.db.RecentFolderDao
import com.fileflow.app.data.db.RuleDao
import com.fileflow.app.data.db.RuleLogDao
import com.fileflow.app.data.db.ShortcutDao
import com.fileflow.app.data.db.StarredFileDao
import com.fileflow.app.data.db.TrashDao
import com.fileflow.app.data.db.TrashJournalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Long-lived work that must outlive any screen: trash retention, rule
     * rescheduling, and the self-rearming workers.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FileFlowDatabase =
        FileFlowDatabase.build(context)

    @Provides
    fun provideRuleDao(database: FileFlowDatabase): RuleDao = database.ruleDao()

    @Provides
    fun provideRuleLogDao(database: FileFlowDatabase): RuleLogDao = database.ruleLogDao()

    @Provides
    fun provideTrashDao(database: FileFlowDatabase): TrashDao = database.trashDao()

    @Provides
    fun provideJournalDao(database: FileFlowDatabase): JournalDao = database.journalDao()

    @Provides
    fun provideShortcutDao(database: FileFlowDatabase): ShortcutDao = database.shortcutDao()

    @Provides
    fun provideRecentFolderDao(database: FileFlowDatabase): RecentFolderDao =
        database.recentFolderDao()

    @Provides
    fun provideFileIndexDao(database: FileFlowDatabase): FileIndexDao = database.fileIndexDao()

    @Provides
    fun provideStarredFileDao(database: FileFlowDatabase): StarredFileDao =
        database.starredFileDao()

    @Provides
    fun provideTrashJournalDao(database: FileFlowDatabase): TrashJournalDao =
        database.trashJournalDao()

    @Provides
    fun provideContentIndexDao(database: FileFlowDatabase): ContentIndexDao =
        database.contentIndexDao()
}
