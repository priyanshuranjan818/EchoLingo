package com.echolingo.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.echolingo.app.data.preferences.AppSettings
import com.echolingo.app.data.preferences.SettingsRepository
import com.echolingo.app.domain.model.FontSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settingsRepository: SettingsRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings by settingsRepository.settings.collectAsState(
        initial = AppSettings()
    )

    var serverUrl  by remember(settings.serverBaseUrl) { mutableStateOf(settings.serverBaseUrl) }
    var groqKey    by remember(settings.groqApiKey)    { mutableStateOf(settings.groqApiKey) }
    var groqKeyVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Title
            Text(
                "Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            HorizontalDivider()

            // --- Subtitle font size ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Subtitle Size",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontSize.entries.forEach { size ->
                        FilterChip(
                            selected = settings.fontSize == size,
                            onClick = { scope.launch { settingsRepository.setFontSize(size) } },
                            label = {
                                Text(
                                    text = when (size) {
                                        FontSize.S -> "Small"
                                        FontSize.M -> "Medium"
                                        FontSize.L -> "Large"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }

            HorizontalDivider()

            // --- Server URL ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Backend Server URL",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                settingsRepository.setServerBaseUrl(serverUrl)
                            }
                        }
                    ) {
                        Text("Save")
                    }
                }
            }

            HorizontalDivider()

            // --- Groq API Key (BYOK) ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Groq API Key  (Shadowing Mode)",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Get a free key at console.groq.com/keys",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    label = { Text("gsk_…") },
                    singleLine = true,
                    visualTransformation = if (groqKeyVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                    trailingIcon = {
                        TextButton(onClick = { groqKeyVisible = !groqKeyVisible }) {
                            Text(
                                if (groqKeyVisible) "Hide" else "Show",
                                fontSize = 11.sp,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (groqKey.isNotBlank()) {
                        Text(
                            "✅ Key saved",
                            fontSize = 12.sp,
                            color = Color(0xFF4CAF50),
                        )
                    } else {
                        Text(
                            "⚠ Shadowing needs a key",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { scope.launch { settingsRepository.setGroqApiKey(groqKey) } }
                    ) {
                        Text("Save")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
