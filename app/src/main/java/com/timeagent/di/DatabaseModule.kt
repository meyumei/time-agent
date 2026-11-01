package com.timeagent.di

import android.content.Context
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import com.timeagent.workout.data.WorkoutDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    // The factories recover an interrupted restore before constructing either Room database.
    @Provides
    @Singleton
    fun mainDatabase(@ApplicationContext context: Context): TimeAgentDatabase =
        TimeAgentDatabase.open(context)

    @Provides
    @Singleton
    fun workoutDatabase(@ApplicationContext context: Context): WorkoutDatabase =
        WorkoutDatabase.open(context)

    @Provides
    @Singleton
    fun timeRepository(database: TimeAgentDatabase): TimeRepository = TimeRepository(database)
}
