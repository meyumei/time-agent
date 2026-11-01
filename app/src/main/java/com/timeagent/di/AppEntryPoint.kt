package com.timeagent.di

import android.content.Context
import com.timeagent.data.TimeRepository
import com.timeagent.workout.data.WorkoutDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun timeRepository(): TimeRepository
    fun workoutDatabase(): WorkoutDatabase

    companion object {
        fun from(context: Context): AppEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)
    }
}
