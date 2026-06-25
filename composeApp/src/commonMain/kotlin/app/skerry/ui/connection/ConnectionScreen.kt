package app.skerry.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.terminal.TerminalScreen

/**
 * Экран подключения: форма SSH → живой терминал. Держит [ConnectionController] на
 * времени жизни композиции (scope из [rememberCoroutineScope]). По состоянию
 * [ConnectionUiState] показывает форму, индикатор подключения, терминал или ошибку.
 */
@Composable
fun ConnectionScreen(transport: SshTransport, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // scope из rememberCoroutineScope стабилен на время композиции — в ключе не нужен.
    val controller = remember(transport) { ConnectionController(transport, scope) }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val state = controller.uiState) {
            is ConnectionUiState.Form -> ConnectionForm(
                onConnect = { target, password -> controller.connect(target, SshAuth.Password(password)) },
            )

            is ConnectionUiState.Connecting -> ConnectingIndicator()

            is ConnectionUiState.Connected -> Column(Modifier.fillMaxSize()) {
                SessionBar(onDisconnect = controller::disconnect)
                TerminalScreen(state.terminal, Modifier.weight(1f))
            }

            is ConnectionUiState.Error -> ConnectionError(
                message = state.message,
                onBack = controller::dismissError,
            )

            // Обрыв соединения: показываем застывший терминал и даём закрыть сессию (вернуться к форме).
            is ConnectionUiState.Disconnected -> Column(Modifier.fillMaxSize()) {
                SessionBar(onDisconnect = controller::disconnect)
                TerminalScreen(state.terminal, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConnectionForm(onConnect: (SshTarget, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val canConnect = host.isNotBlank() && username.isNotBlank() && portNumber != null

    Column(
        modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Новое подключение", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Хост") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Порт") },
            singleLine = true,
            isError = portNumber == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Пользователь") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (canConnect) onConnect(SshTarget(host.trim(), portNumber, username.trim()), password) },
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Подключиться")
        }
    }
}

@Composable
private fun ConnectingIndicator() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text("Подключение…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionBar(onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Сессия", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onDisconnect) { Text("Отключиться") }
    }
}

@Composable
private fun ConnectionError(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Не удалось подключиться", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.fillMaxWidth())
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
}
