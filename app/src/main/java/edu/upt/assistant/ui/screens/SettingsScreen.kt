package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.domain.ModelDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelInfo(
    val isAvailable: Boolean,
    val sizeInBytes: Long = 0L,
    val modelName: String = "Gemma 3n E4B"
)

@HiltViewModel
class SettingsModelViewModel @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager
) : ViewModel() {

    private val _modelInfo = MutableStateFlow(ModelInfo(false))
    val modelInfo: StateFlow<ModelInfo> = _modelInfo.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    init {
        refreshModelInfo()
    }

    fun refreshModelInfo() {
        val isAvailable = modelDownloadManager.isModelAvailable()
        val size = if (isAvailable) {
            // Get file size if model exists
            try {
                java.io.File(modelDownloadManager.getModelPath()).length()
            } catch (e: Exception) {
                0L
            }
        } else 0L

        _modelInfo.value = ModelInfo(
            isAvailable = isAvailable,
            sizeInBytes = size
        )
    }

    fun deleteModel() {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                modelDownloadManager.deleteModel()
                refreshModelInfo()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isDeleting.value = false
            }
        }
    }
}

private val interestOptions = listOf(
    "Hiking", "Reading", "Cooking", "Music", "Travel", "Gaming",
    "Football", "Basketball", "Tennis", "Swimming", "Cycling", "Yoga",
    "Photography", "Painting", "Drawing", "Sculpture", "Writing", "Poetry",
    "Tech News", "Programming", "AI/ML", "Blockchain", "Gadgets",
    "Movies", "TV Shows", "Theater", "Comedy", "Stand-up",
    "Fitness", "Weightlifting", "Running", "CrossFit", "Pilates",
    "Foodie", "Vegan Cooking", "Baking", "Barbecue",
    "Travel", "Backpacking", "Road Trips", "Beach", "Mountains",
    "Gardening", "Astrology", "Meditation",
    "Board Games", "Chess", "Poker", "Role-Playing Games"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    username: String,
    notificationsEnabled: Boolean,
    selectedInterests: Set<String>,
    customInterest: String,
    onUserNameChange: (String) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onInterestsChange: (Set<String>) -> Unit,
    onCustomInterestChange: (String) -> Unit,
    onBack: () -> Unit,
    onDownloadModel: () -> Unit = {},
    modelViewModel: SettingsModelViewModel = hiltViewModel()
) {
    val modelInfo by modelViewModel.modelInfo.collectAsState()
    val isDeleting by modelViewModel.isDeleting.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // User Profile Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.titleMedium
                    )

                    var localUsername by rememberSaveable { mutableStateOf(username) }
                    LaunchedEffect(username) {
                        if (username != localUsername) {
                            localUsername = username
                        }
                    }
                    fun commitUsername() {
                        if (localUsername != username) {
                            onUserNameChange(localUsername)
                        }
                    }
                    OutlinedTextField(
                        value = localUsername,
                        onValueChange = { localUsername = it },
                        label = { Text("Your Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) commitUsername() },
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitUsername() })
                    )
                }
            }

            // App Settings Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "App Settings",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable notifications", modifier = Modifier.weight(1f))
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = onNotificationsToggle
                        )
                    }
                }
            }

            // Hobbies Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Hobbies",
                        style = MaterialTheme.typography.titleMedium
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        interestOptions.forEach { item ->
                            FilterChip(
                                selected = selectedInterests.contains(item),
                                onClick = {
                                    val newSet = selectedInterests.toMutableSet().apply {
                                        if (contains(item)) remove(item) else add(item)
                                    }
                                    onInterestsChange(newSet)
                                },
                                label = { Text(item) }
                            )
                        }
                    }

                    var localCustom by rememberSaveable { mutableStateOf(customInterest) }
                    LaunchedEffect(customInterest) {
                        if (customInterest != localCustom) {
                            localCustom = customInterest
                        }
                    }
                    fun commitCustom() {
                        if (localCustom != customInterest) {
                            onCustomInterestChange(localCustom)
                        }
                    }
                    OutlinedTextField(
                        value = localCustom,
                        onValueChange = { localCustom = it },
                        label = { Text("Other Interests") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) commitCustom() },
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitCustom() })
                    )
                }
            }

            // Model Management Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "AI Model Management",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Model Status
                    ModelStatusCard(
                        modelInfo = modelInfo,
                        isDeleting = isDeleting
                    )

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!modelInfo.isAvailable) {
                            Button(
                                onClick = onDownloadModel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download Model")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onDownloadModel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Re-download")
                            }

                            OutlinedButton(
                                onClick = { showDeleteDialog = true },
                                enabled = !isDeleting,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                if (isDeleting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isDeleting) "Deleting..." else "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete AI Model") },
            text = {
                Text("Are you sure you want to delete the AI model? You'll need to download it again to use chat features.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        modelViewModel.deleteModel()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ModelStatusCard(
    modelInfo: ModelInfo,
    isDeleting: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (modelInfo.isAvailable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Status:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = when {
                        isDeleting -> "Deleting..."
                        modelInfo.isAvailable -> "Available"
                        else -> "Not Downloaded"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isDeleting -> MaterialTheme.colorScheme.onSurfaceVariant
                        modelInfo.isAvailable -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (modelInfo.isAvailable && modelInfo.sizeInBytes > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Size:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatFileSize(modelInfo.sizeInBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Model:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = modelInfo.modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (modelInfo.isAvailable) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        // Note: This preview won't show the model management section properly
        // due to ViewModel dependency, but shows the overall layout
    }
}