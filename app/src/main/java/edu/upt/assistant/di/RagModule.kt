package edu.upt.assistant.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import edu.upt.assistant.data.SettingsKeys
import edu.upt.assistant.data.local.db.ConversationDao
import edu.upt.assistant.data.local.db.DocumentDao
import edu.upt.assistant.data.local.db.MessageDao
import edu.upt.assistant.data.local.db.MemoryDao
import edu.upt.assistant.domain.ChatRepository
import edu.upt.assistant.domain.ChatRepositoryImpl
import edu.upt.assistant.domain.memory.MemoryRepository
import edu.upt.assistant.domain.rag.ConditionalChatRepository
import edu.upt.assistant.domain.rag.DocumentProcessor
import edu.upt.assistant.domain.rag.DocumentRepository
import edu.upt.assistant.domain.rag.RagChatRepository
import edu.upt.assistant.domain.rag.VectorStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RagModule {

    @Provides
    @Singleton
    fun provideDocumentProcessor(): DocumentProcessor = DocumentProcessor()

    @Provides
    @Singleton
    fun provideVectorStore(@ApplicationContext context: Context): VectorStore = VectorStore(context)

    @Provides
    @Singleton
    fun provideDocumentRepository(
        documentDao: DocumentDao,
        documentProcessor: DocumentProcessor,
        vectorStore: VectorStore
    ): DocumentRepository = DocumentRepository(documentDao, documentProcessor, vectorStore)

    @Provides
    @Singleton
    fun provideMemoryRepository(
        memoryDao: MemoryDao,
        vectorStore: VectorStore
    ): MemoryRepository = MemoryRepository(memoryDao, vectorStore)

    @Provides
    @Singleton
    fun provideRagChatRepository(
        baseRepository: ChatRepositoryImpl,
        documentRepository: DocumentRepository,
        conv: ConversationDao,
        msgDao: MessageDao,
        memoryRepository: MemoryRepository,
        @ApplicationContext context: Context
    ): RagChatRepository = RagChatRepository(baseRepository, documentRepository, memoryRepository,msgDao, conv, context)

    @Provides
    @Singleton
    fun provideChatRepository(
        baseRepository: ChatRepositoryImpl,
        ragRepository: RagChatRepository,
        dataStore: DataStore<Preferences>
    ): ChatRepository = ConditionalChatRepository(baseRepository, ragRepository, dataStore)
}