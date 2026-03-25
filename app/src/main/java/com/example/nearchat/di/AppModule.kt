package com.example.nearchat.di

import android.content.Context
import com.example.nearchat.data.datasource.AuthDataSource
import com.example.nearchat.data.datasource.BluetoothDataSource
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideAuthDataSource(
        firebaseAuth: FirebaseAuth,
        firebaseFirestore: FirebaseFirestore
    ): AuthDataSource = AuthDataSource(firebaseAuth, firebaseFirestore)

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
