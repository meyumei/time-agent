package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RecurringSchemaTest {
    @Test fun `recurring rules and instances have separate persistence from actual records`() {
        val db=TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        try {
            val tables=db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
            assertTrue("周期规则与实例尚未实现",tables.containsAll(listOf("recurring_rules","recurring_instances")))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM time_records").use{c->c.moveToFirst();assertEquals(0,c.getInt(0))}
        } finally {db.close()}
    }
}
