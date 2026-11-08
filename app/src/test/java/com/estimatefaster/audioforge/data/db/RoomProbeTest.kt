package com.estimatefaster.audioforge.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-05：在 JVM（Robolectric）上真正跑 Room 的建库 + 写入 + 回读，
 * 这样 CI 的 `testDebugUnitTest` 就能验证 KSP 生成的 DAO/Database 可用，
 * 不必等真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomProbeTest {

    private lateinit var db: AppDatabase
    private lateinit var probe: RoomProbe

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        probe = RoomProbe(db.probeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun probe_writes_and_reads_back() = runTest {
        val result = probe.run()
        assertTrue("期望通过，实际是 $result", result is RoomProbeState.Passed)

        val row = db.probeDao().find(ProbeEntity.PROBE_ID)
        assertEquals(1, row?.counter)
        assertEquals("room-smoke", row?.note)
        assertTrue((row?.updatedAt ?: 0L) > 0L)
    }

    @Test
    fun probe_accumulates_counter_across_runs() = runTest {
        probe.run()
        val second = probe.run()
        assertTrue(second is RoomProbeState.Passed)
        assertEquals(2, (second as RoomProbeState.Passed).counter)
    }

    @Test
    fun failed_query_is_reported_not_thrown() = runTest {
        db.close()
        val result = probe.run()
        assertTrue("期望失败状态，实际是 $result", result is RoomProbeState.Failed)
    }
}
