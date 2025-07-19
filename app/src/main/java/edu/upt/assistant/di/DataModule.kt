package edu.upt.assistant.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import edu.upt.assistant.data.local.db.AppDatabase
import edu.upt.assistant.data.local.db.ConversationDao
import edu.upt.assistant.data.local.db.MessageDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

  @Provides @Singleton
  fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
    Room.databaseBuilder(ctx, AppDatabase::class.java, "assistant_db")
      .fallbackToDestructiveMigration(false)
      .build()

  @Provides
  fun provideConversationDao(db: AppDatabase): ConversationDao =
    db.conversationDao()

  @Provides
  fun provideMessageDao(db: AppDatabase): MessageDao =
    db.messageDao()
}
