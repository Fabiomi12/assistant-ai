package edu.upt.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.upt.assistant.domain.ModelDownloadManager
import edu.upt.assistant.domain.ModelState

@Composable
fun GlobalDownloadIndicator(
    downloadManager: ModelDownloadManager
) {
    val activeDownloads by downloadManager.downloadStates.collectAsState()
    val downloadProgress by downloadManager.downloadProgress.collectAsState()

    val hasActiveDownloads = activeDownloads.values.any { it == ModelState.Downloading }

    if (hasActiveDownloads) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        "Downloading models...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Show progress for each downloading model
                activeDownloads.entries
                    .filter { it.value == ModelState.Downloading }
                    .forEach { (url, _) ->
                        val progress = downloadProgress[url]
                        if (progress != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    downloadManager.fileNameFrom(url),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                LinearProgressIndicator(
                                    progress = { progress.percentage / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${progress.percentage}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
            }
        }
    }
}