package com.timeagent.di

import android.os.SystemClock
import com.timeagent.core.ClockSource
import com.timeagent.core.SystemClockSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @Singleton
    fun clockSource(): ClockSource = SystemClockSource(monotonicMillis = SystemClock::elapsedRealtime)
}
