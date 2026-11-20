package com.familysafety.app.di

import com.familysafety.app.BuildConfig
import com.familysafety.app.data.di.AppVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @AppVersion fun appVersion(): String = BuildConfig.VERSION_NAME
}
