package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceUserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeviceUser(user: DeviceUserEntity)

    @Query("SELECT * FROM device_user_records WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDeviceUserById(deviceId: String): DeviceUserEntity?

    @Query("SELECT * FROM device_user_records ORDER BY lastSyncedAt DESC")
    fun getAllDeviceUsersFlow(): Flow<List<DeviceUserEntity>>

    @Query("SELECT COUNT(*) FROM device_user_records")
    suspend fun getDeviceUserCount(): Int
}
