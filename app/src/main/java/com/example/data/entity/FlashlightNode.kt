package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "flashlight_nodes")
data class FlashlightNode(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val brightnessPath: String,
    val maxBrightnessPath: String,
    val isCustom: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
