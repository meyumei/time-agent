package com.timeagent.di

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.ledger.LedgerActivity
import com.timeagent.ledger.PaymentNotificationListener
import com.timeagent.organization.OrganizationActivity
import com.timeagent.workout.data.WorkoutDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(androidx.room.ExperimentalRoomApi::class)
class DatabaseOwnershipTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dependencies get() = AppEntryPoint.from(app)
    private val main get() = dependencies.timeRepository().database

    @Before
    fun bindRoomInvalidationService() {
        val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create().get()
        val binder = requireNotNull(service.onBind(Intent(app, MultiInstanceInvalidationService::class.java)))
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, MultiInstanceInvalidationService::class.java), binder,
        )
    }

    @After
    fun closeTestApplicationDatabases() {
        main.close()
        dependencies.workoutDatabase().close()
        app.deleteDatabase("time-agent.db")
        app.deleteDatabase("workouts.db")
    }

    @Test
    fun hiltEntryPointsAndFactoriesShareBothDatabases() {
        assertSame(main, TimeAgentDatabase.open(app))
        assertSame(dependencies.timeRepository(), AppEntryPoint.from(app).timeRepository())
        assertSame(dependencies.workoutDatabase(), WorkoutDatabase.open(app))
    }

    @Test
    fun finishingSecondaryActivitiesKeepsTheMainDatabaseUsable() = runBlocking {
        main.dao().putTask(TaskEntity("ownership", "T1_OWNERSHIP"))
        val ledger = Robolectric.buildActivity(LedgerActivity::class.java).create()
        assertSame(main, ledger.get().database.get())
        ledger.get().finish()
        ledger.destroy()
        assertEquals("T1_OWNERSHIP", main.dao().task("ownership")!!.title)

        val organization = Robolectric.buildActivity(OrganizationActivity::class.java).create()
        assertSame(main, organization.get().database.get())
        organization.get().finish()
        organization.destroy()
        assertEquals("T1_OWNERSHIP", main.dao().task("ownership")!!.title)
    }

    @Test
    fun destroyingPaymentListenerDoesNotCloseAnotherConsumersDatabase() = runBlocking {
        main.dao().putTask(TaskEntity("ownership", "T1_OWNERSHIP"))
        val service = Robolectric.buildService(PaymentNotificationListener::class.java).create()
        assertSame(main, service.get().database.get())
        service.destroy()
        assertTrue(main.isOpen)
        assertEquals("T1_OWNERSHIP", main.dao().task("ownership")!!.title)
    }
}
