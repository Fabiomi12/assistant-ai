package edu.upt.assistant.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import edu.upt.assistant.domain.ChatRepository
import edu.upt.assistant.domain.ChatRepositoryImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {
  @Binds
  abstract fun bindChatRepository(
    impl: ChatRepositoryImpl
  ): ChatRepository
}
