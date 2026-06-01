package com.example.ui

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.entity.FlashlightNode
import com.example.data.repository.FlashlightRepository
import com.example.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class RootState {
    object Unchecked : RootState()
    object Checking : RootState()
    object Available : RootState()
    object Unavailable : RootState()
}

class FlashlightViewModel(
    application: Application,
    private val repository: FlashlightRepository
) : AndroidViewModel(application) {

    private val _rootState = MutableStateFlow<RootState>(RootState.Unchecked)
    val rootState: StateFlow<RootState> = _rootState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Nodes and Settings loaded from Room db
    val allNodes = MutableStateFlow<List<FlashlightNode>>(emptyList())
    val allSettings = MutableStateFlow<Map<String, String>>(emptyMap())

    // Active flashlight node
    private val _selectedNode = MutableStateFlow<FlashlightNode?>(null)
    val selectedNode: StateFlow<FlashlightNode?> = _selectedNode.asStateFlow()

    // Operating statuses
    private val _currentBrightness = MutableStateFlow(0)
    val currentBrightness: StateFlow<Int> = _currentBrightness.asStateFlow()

    private val _maxBrightness = MutableStateFlow(255)
    val maxBrightness: StateFlow<Int> = _maxBrightness.asStateFlow()

    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private val _themeAccentIndex = MutableStateFlow(0)
    val themeAccentIndex: StateFlow<Int> = _themeAccentIndex.asStateFlow()

    private val _availableTriggers = MutableStateFlow<List<String>>(emptyList())
    val availableTriggers: StateFlow<List<String>> = _availableTriggers.asStateFlow()

    private val _currentTrigger = MutableStateFlow("")
    val currentTrigger: StateFlow<String> = _currentTrigger.asStateFlow()

    // Conflated flow channel for writing brightness to the system. Prevents lag on continuous sliding!
    private val brightnessWriteFlow = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        // Observe settings & nodes
        viewModelScope.launch {
            repository.allNodes.collect { list ->
                allNodes.value = list
                if (list.isEmpty()) {
                    prepopulateDefaultNodesAndScan()
                } else {
                    restoreLastSelectedNode(list)
                }
            }
        }

        viewModelScope.launch {
            repository.allSettings.collect { settings ->
                allSettings.value = settings
                _hapticEnabled.value = settings[FlashlightRepository.KEY_HAPTIC_ENABLED]?.toBoolean() ?: true
                _themeAccentIndex.value = settings[FlashlightRepository.KEY_THEME_ACCENT]?.toIntOrNull() ?: 0
                
                // If selected node is already initialized, restore its saved last brightness if we are toggling on
                settings[FlashlightRepository.KEY_LAST_BRIGHTNESS]?.toIntOrNull()?.let { lastB ->
                    if (_currentBrightness.value == 0 && lastB > 0) {
                        // Keep it tracked, but don't force write it now unless turned on
                    }
                }
            }
        }

        // Execute background writes safely
        viewModelScope.launch(Dispatchers.IO) {
            brightnessWriteFlow.collect { valToSet ->
                val node = _selectedNode.value ?: return@collect
                val command = "echo $valToSet > ${node.brightnessPath}"
                RootShell.writeCommand(command)
                Log.d("FlashlightVM", "Sysfs written: $command")
            }
        }

        // Check root status initially
        checkRootPresence()
    }

    fun checkRootPresence() {
        viewModelScope.launch {
            _rootState.value = RootState.Checking
            val rootAvailable = RootShell.checkRootAvailable()
            if (rootAvailable) {
                _rootState.value = RootState.Available
                // Start a persistent su shell in background
                withContext(Dispatchers.IO) {
                    RootShell.startPersistentShell()
                }
                // Perform a refresh on max brightness after knowing root is ready
                refreshMaxBrightness()
            } else {
                _rootState.value = RootState.Unavailable
            }
        }
    }

    private fun prepopulateDefaultNodesAndScan() {
        viewModelScope.launch(Dispatchers.IO) {
            val defaultNodes = listOf(
                FlashlightNode(
                    name = "Standard Flashlight",
                    brightnessPath = "/sys/class/leds/flashlight/brightness",
                    maxBrightnessPath = "/sys/class/leds/flashlight/max_brightness"
                ),
                FlashlightNode(
                    name = "Camera Torch",
                    brightnessPath = "/sys/class/leds/torch/brightness",
                    maxBrightnessPath = "/sys/class/leds/torch/max_brightness"
                ),
                FlashlightNode(
                    name = "LED Torch 0",
                    brightnessPath = "/sys/class/leds/led:torch_0/brightness",
                    maxBrightnessPath = "/sys/class/leds/led:torch_0/max_brightness"
                ),
                FlashlightNode(
                    name = "Flash Torch 0",
                    brightnessPath = "/sys/class/leds/led:flash_torch_0/brightness",
                    maxBrightnessPath = "/sys/class/leds/led:flash_torch_0/max_brightness"
                )
            )
            for (node in defaultNodes) {
                repository.insertNode(node)
            }
            // Trigger automatic sysfs sweep for custom configurations
            scanFoldersForNodes()
        }
    }

    fun scanFoldersForNodes() {
        if (_isScanning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            val foundPaths = mutableSetOf<String>()

            // 1. Scan /sys/class/leds/ via Java
            val ledsDir = File("/sys/class/leds")
            if (ledsDir.exists() && ledsDir.isDirectory) {
                ledsDir.listFiles()?.forEach { dir ->
                    val bFile = File(dir, "brightness")
                    if (bFile.exists()) {
                        foundPaths.add(bFile.absolutePath)
                    }
                }
            }

            // 2. Scan via Root ls fallback
            val rootOutput = RootShell.readCommandOutput("ls /sys/class/leds")
            if (!rootOutput.isNullOrBlank()) {
                rootOutput.split("\n", "\r")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { sub ->
                        foundPaths.add("/sys/class/leds/$sub/brightness")
                    }
            }

            // Save detected unique nodes to Room Database
            for (bPath in foundPaths) {
                val dirPath = bPath.substringBeforeLast("/")
                val maxBPath = "$dirPath/max_brightness"
                val nodeName = dirPath.substringAfterLast("/").replace("_", " ").split(" ")
                    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

                val exists = allNodes.value.any { it.brightnessPath == bPath }
                if (!exists) {
                    val mVal = readMaxBrightnessFromNode(maxBPath)
                    repository.insertNode(
                        FlashlightNode(
                            name = nodeName,
                            brightnessPath = bPath,
                            maxBrightnessPath = maxBPath,
                            isCustom = false
                        )
                    )
                }
            }
            _isScanning.value = false
        }
    }

    private fun readMaxBrightnessFromNode(maxBPath: String): Int {
        // Try direct first
        try {
            val file = File(maxBPath)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                val parsed = content.toIntOrNull()
                if (parsed != null && parsed > 0) return parsed
            }
        } catch (e: Exception) {
            // ignore
        }

        // Try root fallback
        val output = RootShell.readCommandOutput("cat $maxBPath")
        if (!output.isNullOrBlank()) {
            val parsed = output.trim().toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return 255 // standard default
    }

    private fun restoreLastSelectedNode(nodes: List<FlashlightNode>) {
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            val lastIdStr = repository.getSetting(FlashlightRepository.KEY_ACTIVE_NODE_ID)
            val restoredNode = if (lastIdStr != null) {
                val lastId = lastIdStr.toIntOrNull()
                nodes.find { it.id == lastId } ?: nodes.first()
            } else {
                nodes.first()
            }
            selectNode(restoredNode)
        }
    }

    fun selectNode(node: FlashlightNode) {
        _selectedNode.value = node
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSetting(FlashlightRepository.KEY_ACTIVE_NODE_ID, node.id.toString())
            refreshMaxBrightness()
            // Read active brightness if possible to sync state
            readActiveBrightness()
            readTriggers()
        }
    }

    private fun readTriggers() {
        val node = _selectedNode.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val triggerPath = node.brightnessPath.substringBeforeLast("/") + "/trigger"
            var triggerContent = ""
            try {
                val f = File(triggerPath)
                if (f.exists() && f.canRead()) {
                    triggerContent = f.readText().trim()
                }
            } catch (e: Exception) {
                // ignore
            }
            if (triggerContent.isEmpty()) {
                val output = RootShell.readCommandOutput("cat $triggerPath")
                if (!output.isNullOrBlank()) {
                    triggerContent = output.trim()
                }
            }

            if (triggerContent.isNotEmpty()) {
                // Example format: none [timer] oneshot heartbeat 
                val tokens = triggerContent.split("\\s+".toRegex())
                val available = mutableListOf<String>()
                var current = ""
                for (token in tokens) {
                    if (token.startsWith("[") && token.endsWith("]")) {
                        val active = token.substring(1, token.length - 1)
                        available.add(active)
                        current = active
                    } else {
                        available.add(token)
                    }
                }
                _availableTriggers.value = available
                _currentTrigger.value = current
            } else {
                _availableTriggers.value = emptyList()
                _currentTrigger.value = ""
            }
        }
    }

    fun setTrigger(trigger: String) {
        val node = _selectedNode.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val triggerPath = node.brightnessPath.substringBeforeLast("/") + "/trigger"
            RootShell.writeCommand("echo $trigger > $triggerPath")
            _currentTrigger.value = trigger
        }
    }

    private fun refreshMaxBrightness() {
        val node = _selectedNode.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val maxVal = readMaxBrightnessFromNode(node.maxBrightnessPath)
            _maxBrightness.value = maxVal
        }
    }

    private fun readActiveBrightness() {
        val node = _selectedNode.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var activeB = 0
            try {
                val f = File(node.brightnessPath)
                if (f.exists() && f.canRead()) {
                    activeB = f.readText().trim().toIntOrNull() ?: 0
                }
            } catch (e: Exception) {
                // ignore
            }
            if (activeB == 0) {
                val output = RootShell.readCommandOutput("cat ${node.brightnessPath}")
                if (!output.isNullOrBlank()) {
                    activeB = output.trim().toIntOrNull() ?: 0
                }
            }
            _currentBrightness.value = activeB
        }
    }

    fun setBrightness(value: Int) {
        val boundedVal = value.coerceIn(0, _maxBrightness.value)
        _currentBrightness.value = boundedVal
        
        // Feed into the conflated write stream channel to update physically
        brightnessWriteFlow.tryEmit(boundedVal)

        if (boundedVal > 0) {
            // Save last used non-zero intensity level
            viewModelScope.launch {
                repository.saveSetting(FlashlightRepository.KEY_LAST_BRIGHTNESS, boundedVal.toString())
            }
        }
    }

    fun toggleTorch() {
        triggerHaptic()
        val current = _currentBrightness.value
        if (current > 0) {
            // Turn off
            setBrightness(0)
        } else {
            // Turn on - retrieve last non-zero brightness or default to max
            viewModelScope.launch {
                val lastSavedStr = repository.getSetting(FlashlightRepository.KEY_LAST_BRIGHTNESS)
                val targetB = lastSavedStr?.toIntOrNull() ?: (_maxBrightness.value / 2).coerceAtLeast(1)
                setBrightness(targetB)
            }
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        _hapticEnabled.value = enabled
        viewModelScope.launch {
            repository.saveSetting(FlashlightRepository.KEY_HAPTIC_ENABLED, enabled.toString())
        }
    }

    fun setThemeAccent(index: Int) {
        _themeAccentIndex.value = index
        viewModelScope.launch {
            repository.saveSetting(FlashlightRepository.KEY_THEME_ACCENT, index.toString())
        }
    }

    fun addCustomNode(name: String, brightnessPath: String, maxBrightnessPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val custom = FlashlightNode(
                name = name.takeIf { it.isNotBlank() } ?: "Custom Node",
                brightnessPath = brightnessPath.trim(),
                maxBrightnessPath = maxBrightnessPath.trim().takeIf { it.isNotBlank() } ?: "${brightnessPath.trim().substringBeforeLast("/")}/max_brightness",
                isCustom = true
            )
            val newId = repository.insertNode(custom)
            // Auto Select the newly added custom node
            val updatedNode = custom.copy(id = newId.toInt())
            selectNode(updatedNode)
        }
    }

    fun removeNode(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteNode(id)
            // If we deleted the active node, reset selection to first
            if (_selectedNode.value?.id == id) {
                val remaining = allNodes.value.filter { it.id != id }
                if (remaining.isNotEmpty()) {
                    selectNode(remaining.first())
                } else {
                    _selectedNode.value = null
                    _currentBrightness.value = 0
                }
            }
        }
    }

    fun triggerHaptic() {
        if (!_hapticEnabled.value) return
        val context = getApplication<Application>().applicationContext
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Close root shell cleanly when ViewModel terminates
        RootShell.close()
    }
}

class FlashlightViewModelFactory(
    private val application: Application,
    private val repository: FlashlightRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FlashlightViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FlashlightViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
