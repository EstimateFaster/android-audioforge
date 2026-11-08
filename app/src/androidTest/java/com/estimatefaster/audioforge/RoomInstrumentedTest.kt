package com.estimatefaster.audioforge

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.ProbeEntity
import com.estimatefaster.audioforge.data.db.RoomProbe
import com.estimatefaster.audioforge.data.db.RoomProbeState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** P0-05 的真机/模拟器版本：在设备 SQLite 上跑一次写入+回读。 */
@RunWith(AndroidJUnit4::class)
class RoomInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var probe: RoomProbe

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        probe = RoomProbe(db.probeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun probe_writes_and_reads_back_on_device() = runBlocking {
        val result = probe.run()
        assertTrue("期望通过，实际是 $result", result is RoomProbeState.Passed)
        assertEquals(1, db.probeDao().find(ProbeEntity.PROBE_ID)?.counter)
    }
}
