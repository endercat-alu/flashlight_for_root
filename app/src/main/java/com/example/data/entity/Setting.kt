package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String
)
