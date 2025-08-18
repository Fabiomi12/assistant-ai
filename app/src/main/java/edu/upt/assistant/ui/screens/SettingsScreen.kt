package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import edu.upt.assistant.domain.DownloadProgress
import edu.upt.assistant.domain.ModelDownloadManager
import edu.upt.assistant.domain.ModelInfo
import edu.upt.assistant.domain.ModelManagementState
import edu.upt.assistant.domain.ModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  username: String,
  notificationsEnabled: Boolean,
  ragEnabled: Boolean,
  onUserNameChange: (String) -> Unit,
  onNotificationsToggle: (Boolean) -> Unit,
  onRagToggle: (Boolean) -> Unit,
  onBack: () -> Unit,
  modelManagementState: ModelManagementState,
  onActiveModelChange: (String) -> Unit,
  onAddModel: (String) -> Unit,
  onRemoveModel: (String) -> Unit,
  onStartDownload: (String) -> Unit,
  onCancelDownload: (String) -> Unit,
  onDeleteModel: (String) -> Unit,
  downloadManager: ModelDownloadManager
) {
  var newModelUrl by remember { mutableStateOf("") }

  // Collect live download progress
  val downloadProgress by downloadManager.downloadProgress.collectAsState()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        }
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
      // Global download indicator
      if (modelManagementState.hasActiveDownloads) {
        GlobalDownloadIndicator(downloadManager)
      }
      // Profile section
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Text(text = "Profile", style = MaterialTheme.typography.titleMedium)

          var localUsername by rememberSaveable { mutableStateOf(username) }
          LaunchedEffect(username) {
            if (username != localUsername) localUsername = username
          }

          OutlinedTextField(
            value = localUsername,
            onValueChange = { localUsername = it },
            label = { Text("Your Name") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
              if (localUsername != username) onUserNameChange(localUsername)
            })
          )
        }
      }

      // App settings section
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Text(text = "App Settings", style = MaterialTheme.typography.titleMedium)

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Enable notifications", modifier = Modifier.weight(1f))
            Switch(checked = notificationsEnabled, onCheckedChange = onNotificationsToggle)
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Enable RAG (Retrieval-Augmented Generation)")
              Text(
                text = "Uses your documents to enhance AI responses",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Switch(checked = ragEnabled, onCheckedChange = onRagToggle)
          }
        }
      }

      // Enhanced model management section
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Text(text = "AI Models", style = MaterialTheme.typography.titleMedium)

          // Add model URL
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = newModelUrl,
              onValueChange = { newModelUrl = it },
              label = { Text("Add Model URL") },
              modifier = Modifier.weight(1f)
            )
            Button(
              onClick = {
                if (newModelUrl.isNotBlank()) {
                  onAddModel(newModelUrl)
                  newModelUrl = ""
                }
              }
            ) { Text("Add") }
          }

          // Model list with live progress updates
          modelManagementState.models.forEach { modelInfo ->
            ModelManagementItem(
              modelInfo = modelInfo,
              isActive = modelInfo.url == modelManagementState.activeModelUrl,
              downloadProgress = downloadProgress[modelInfo.url], // Live progress
              onSetActive = { onActiveModelChange(modelInfo.url) },
              onStartDownload = { onStartDownload(modelInfo.url) },
              onCancelDownload = { onCancelDownload(modelInfo.url) },
              onDelete = { onDeleteModel(modelInfo.url) },
              onRemove = { onRemoveModel(modelInfo.url) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ModelManagementItem(
  modelInfo: ModelInfo,
  isActive: Boolean,
  downloadProgress: DownloadProgress?,
  onSetActive: () -> Unit,
  onStartDownload: () -> Unit,
  onCancelDownload: () -> Unit,
  onDelete: () -> Unit,
  onRemove: () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isActive)
        MaterialTheme.colorScheme.primaryContainer
      else
        MaterialTheme.colorScheme.surface
    )
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        RadioButton(
          selected = isActive,
          onClick = onSetActive,
          enabled = modelInfo.state == ModelState.Available
        )

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = modelInfo.fileName,
            style = MaterialTheme.typography.bodyMedium
          )
          Text(
            text = when (modelInfo.state) {
              ModelState.Available -> "Ready"
              ModelState.Downloading -> "Downloading..."
              ModelState.NotDownloaded -> "Not downloaded"
              is ModelState.Error -> "Error: ${modelInfo.state.message}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (modelInfo.state) {
              ModelState.Available -> Color.Green
              ModelState.Downloading -> MaterialTheme.colorScheme.primary
              ModelState.NotDownloaded -> MaterialTheme.colorScheme.onSurfaceVariant
              is ModelState.Error -> MaterialTheme.colorScheme.error
            }
          )
        }

        // State indicator icon
        when (modelInfo.state) {
          ModelState.Available -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Available",
            tint = Color.Green
          )
          ModelState.Downloading -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp)
          )
          ModelState.NotDownloaded -> {}
          is ModelState.Error -> Icon(
            Icons.Default.Cancel,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error
          )
        }
      }

      // Download progress bar
      if (downloadProgress != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          LinearProgressIndicator(
            progress = { downloadProgress.percentage / 100f },
            modifier = Modifier.fillMaxWidth()
          )
          Text(
            text = "${downloadProgress.percentage}% - ${formatBytes(downloadProgress.bytesDownloaded)} / ${formatBytes(downloadProgress.totalBytes)}",
            style = MaterialTheme.typography.bodySmall
          )
        }
      }

      // Action buttons
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        when (modelInfo.state) {
          ModelState.NotDownloaded -> {
            TextButton(onClick = onStartDownload) {
              Icon(Icons.Default.CloudDownload, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Download")
            }
          }
          ModelState.Downloading -> {
            TextButton(onClick = onCancelDownload) {
              Icon(Icons.Default.Cancel, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Cancel")
            }
          }
          ModelState.Available -> {
            TextButton(onClick = onDelete) {
              Icon(Icons.Default.Delete, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Delete")
            }
          }
          is ModelState.Error -> {
            TextButton(onClick = onStartDownload) {
              Icon(Icons.Default.CloudDownload, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Retry")
            }
          }
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onRemove) {
          Text("Remove")
        }
      }
    }
  }
}

private fun formatBytes(bytes: Long): String {
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