package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WhatsAppLinkRecordDao {

    @Query("SELECT * FROM whatsapp_link_records ORDER BY timestamp DESC")
    fun getAllRecordsFlow(): Flow<List<WhatsAppLinkRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: WhatsAppLinkRecordEntity): Long

    @Query("DELETE FROM whatsapp_link_records")
    suspend fun clearAll()
}
