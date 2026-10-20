package com.ams.mgr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SchoolClosureDao {
    @Query("SELECT * FROM school_closures ORDER BY date") fun observeAll(): Flow<List<SchoolClosure>>
    @Query("SELECT * FROM school_closures WHERE date BETWEEN :startDate AND :endDate ORDER BY date") suspend fun between(startDate: String, endDate: String): List<SchoolClosure>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(item: SchoolClosure)
    @Query("DELETE FROM school_closures WHERE date = :date") suspend fun delete(date: String)
}
