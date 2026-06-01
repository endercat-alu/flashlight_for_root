package com.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.repository.FlashlightRepository
import com.example.ui.FlashlightViewModel
import com.example.ui.FlashlightViewModelFactory
import com.example.ui.RootState
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                // Initialize database and repository inside setContent
                val context = LocalContext.current
                val application = context.applicationContext as Application
                val database = AppDatabase.getInstance(application)
                val repository = FlashlightRepository(database.flashlightDao())
                val factory = FlashlightViewModelFactory(application, repository)
                val viewModel: FlashlightViewModel = viewModel(factory = factory)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // Keep a beautifully styled bottom padding for system gestures
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                ) { innerPadding ->
                    FlashlightDashboard(
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashlightDashboard(
    viewModel: FlashlightViewModel,
    modifier: Modifier = Modifier
) {
    // State collection
    val rootState by viewModel.rootState.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val currentB by viewModel.currentBrightness.collectAsState()
    val maxB by viewModel.maxBrightness.collectAsState()
    val availableTriggers by viewModel.availableTriggers.collectAsState()
    val currentTrigger by viewModel.currentTrigger.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header Bar
            Text(
                text = "极简手电筒 (Root)",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // Root Status
            val rootStateText = when (rootState) {
                is RootState.Available -> "Root 权限: 已授权"
                is RootState.Checking -> "Root 权限: 检测中..."
                else -> "Root 权限: 未授权"
            }
            Text(text = rootStateText, fontSize = 14.sp)
            if (rootState !is RootState.Available) {
                Button(onClick = { viewModel.checkRootPresence() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("重新检测 Root")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Power button
            Button(
                onClick = { viewModel.toggleTorch() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(if (currentB > 0) "关闭手电筒" else "打开手电筒", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            val maxRange = maxB.toFloat().coerceAtLeast(1f)
            val currentClamped = currentB.toFloat().coerceIn(0f, maxRange)

            // Slider
            Text(text = "亮度: $currentB / $maxB", fontSize = 14.sp)
            Slider(
                value = currentClamped,
                onValueChange = { newValue ->
                    viewModel.setBrightness(newValue.toInt())
                },
                valueRange = 0f..maxRange,
                modifier = Modifier.fillMaxWidth()
            )

            if (availableTriggers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                var expanded by remember { mutableStateOf(false) }

                Text(text = "触发器 (Trigger)", fontSize = 14.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .clickable { expanded = true }
                        .padding(16.dp)
                ) {
                    Text(
                        text = currentTrigger.ifEmpty { "无" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        availableTriggers.forEach { trigger ->
                            DropdownMenuItem(
                                text = { Text(trigger) },
                                onClick = {
                                    viewModel.setTrigger(trigger)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            // Nodes List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("控制节点", fontWeight = FontWeight.Bold)
                Row {
                    IconButton(onClick = { viewModel.scanFoldersForNodes() }) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "扫描")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "添加")
                    }
                }
            }

            if (isScanning) {
                Text("正在扫描...", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }

            // Nodes List
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(allNodes) { node ->
                    val isSelected = selectedNode?.id == node.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectNode(node) }
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                                else Color.Transparent
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = node.name, fontWeight = FontWeight.Bold, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text(text = node.brightnessPath, fontSize = 10.sp, color = Color.Gray)
                        }
                        if (node.isCustom) {
                            IconButton(onClick = { viewModel.removeNode(node.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var customName by remember { mutableStateOf("") }
        var customPath by remember { mutableStateOf("") }
        var customMaxPath by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加节点") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("名称") }
                    )
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("路径") }
                    )
                    OutlinedTextField(
                        value = customMaxPath,
                        onValueChange = { customMaxPath = it },
                        label = { Text("最大亮度路径(选填)") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (customPath.isNotBlank()) {
                        viewModel.addCustomNode(customName, customPath, customMaxPath)
                        showAddDialog = false
                    }
                }) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
