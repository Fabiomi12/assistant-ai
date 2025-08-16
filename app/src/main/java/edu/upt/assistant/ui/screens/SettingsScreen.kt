package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.util.Log
import edu.upt.assistant.domain.ModelDownloadManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  username: String,
  notificationsEnabled: Boolean,
  onUserNameChange: (String) -> Unit,
  onNotificationsToggle: (Boolean) -> Unit,
  onBack: () -> Unit,
  modelUrls: Set<String>,
  activeModel: String,
  onActiveModelChange: (String) -> Unit,
  onAddModel: (String) -> Unit,
  onRemoveModel: (String) -> Unit,
) {
  val context = LocalContext.current
  val downloadManager = remember { ModelDownloadManager(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var newModelUrl by remember { mutableStateOf("") }

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
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
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
          fun commitUsername() {
            if (localUsername != username) onUserNameChange(localUsername)
          }
          OutlinedTextField(
            value = localUsername,
            onValueChange = { localUsername = it },
            label = { Text("Your Name") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitUsername() })
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
        }
      }

      // Model management section
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Text(text = "AI Models", style = MaterialTheme.typography.titleMedium)

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

          modelUrls.forEach { url ->
            val id = downloadManager.fileNameFrom(url)
            val isAvailable = downloadManager.isModelAvailable(id)
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth()
            ) {
              RadioButton(
                selected = activeModel == url,
                onClick = { onActiveModelChange(url) }
              )
              Text(text = id, modifier = Modifier.weight(1f))
              if (isAvailable) {
                TextButton(
                  onClick = {
                    scope.launch {
                      downloadManager.deleteModel(id)
                      onRemoveModel(url)
                    }
                  }
                ) {
                  Icon(Icons.Default.Delete, contentDescription = null)
                  Spacer(Modifier.width(4.dp))
                  Text("Delete")
                }
              } else {
                TextButton(
                  onClick = {
                    scope.launch {
                      try {
                        downloadManager.downloadModel(url).collect { /* no-op */ }
                      } catch (e: Exception) {
                        Log.e("SettingsScreen", "Model download failed", e)
                      }
                    }
                  }
                ) {
                  Icon(Icons.Default.CloudDownload, contentDescription = null)
                  Spacer(Modifier.width(4.dp))
                  Text("Download")
                }
              }
            }
          }
        }
      }
    }
  }
}

