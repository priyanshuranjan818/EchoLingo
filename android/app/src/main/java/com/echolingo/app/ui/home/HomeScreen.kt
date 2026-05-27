package com.echolingo.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echolingo.app.data.api.ApiFactory
import com.echolingo.app.data.api.ImportRequest
import com.echolingo.app.data.preferences.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    settingsRepository: SettingsRepository,
    onOpenPlayer: (String) -> Unit,
) {
    val settings by settingsRepository.settings.collectAsState(initial = com.echolingo.app.data.preferences.AppSettings())
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(settings.serverBaseUrl) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("EchoLingo", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "Paste a YouTube link to import subtitles.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("YouTube URL or video ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Backend URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        settingsRepository.setServerBaseUrl(serverUrl)
                        val api = ApiFactory.create(serverUrl)
                        val response = api.importVideo(ImportRequest(url = url.trim()))
                        onOpenPlayer(response.videoId)
                    } catch (e: Exception) {
                        error = e.message ?: "Import failed"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import Video")
        }
        Spacer(Modifier.height(16.dp))
        if (loading) {
            CircularProgressIndicator()
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
