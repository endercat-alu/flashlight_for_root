package com.example.data.repository

import com.example.data.dao.FlashlightDao
import com.example.data.entity.FlashlightNode
import com.example.data.entity.Setting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FlashlightRepository(private val dao: FlashlightDao) {

    companion object {
        const val KEY_ACTIVE_NODE_ID = "active_node_id"
        const val KEY_LAST_BRIGHTNESS = "last_brightness"
        const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        const val KEY_THEME_ACCENT = "theme_accent" // neon amber, bright teal, crimson etc.
    }

    val allNodes: Flow<List<FlashlightNode>> = dao.getAllNodes()
    val allSettings: Flow<Map<String, String>> = dao.getAllSettingsFlow().map { list ->
        list.associate { it.key to it.value }
    }

    suspend fun insertNode(node: FlashlightNode): Long {
        return dao.insertNode(node)
    }

    suspend fun deleteNode(id: Int) {
        dao.deleteNodeById(id)
    }

    suspend fun saveSetting(key: String, value: String) {
        dao.insertSetting(Setting(key, value))
    }

    suspend fun getSetting(key: String): String? {
        return dao.getSettingByKey(key)?.value
    }
}
