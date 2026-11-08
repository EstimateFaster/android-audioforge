package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** P0-05 探针 DAO；P2-01 建立正式 DAO 时删除。 */
@Dao
interface ProbeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(probe: ProbeEntity)

    @Query("SELECT * FROM probe WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): ProbeEntity?

    @Query("DELETE FROM probe")
    suspend fun clear()
}
