package com.example.nearchat.di

import android.content.Context
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.datasource.LocalUserDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBluetoothDataSource(
        @ApplicationContext context: Context,
        localUserDataSource: LocalUserDataSource
    ): BluetoothDataSource = BluetoothDataSource(context, localUserDataSource)

    @Provides
    @Singleton
    fun provideLocalUserDataSource(
        @ApplicationContext context: Context
    ): LocalUserDataSource = LocalUserDataSource(context)
}
