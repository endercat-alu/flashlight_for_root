package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.FlashlightNode
import com.example.data.entity.Setting
import kotlinx.coroutines.flow.Flow

@Dao
interface FlashlightDao {
    @Query("SELECT * FROM flashlight_nodes ORDER BY isCustom ASC, timestamp DESC")
    fun getAllNodes(): Flow<List<FlashlightNode>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: FlashlightNode): Long

    @Query("DELETE FROM flashlight_nodes WHERE id = :id")
    suspend fun deleteNodeById(id: Int)

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun getSettingByKey(key: String): Setting?

    @Query("SELECT * FROM app_settings")
    fun getAllSettingsFlow(): Flow<List<Setting>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: Setting)
}
