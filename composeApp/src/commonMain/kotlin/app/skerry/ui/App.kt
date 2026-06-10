package app.skerry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.skerry.shared.platformName

// Временные значения токенов «night sea»; на следующем шаге палитра целиком
// переезжает из :root HTML-прототипов в Compose-тему (единственный источник правды).
private val NightSeaBackground = Color(0xFF07141E)
private val NightSeaPrimary = Color(0xFF2BBDEE)

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NightSeaPrimary,
            background = NightSeaBackground,
            surface = NightSeaBackground,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Skerry · $platformName",
                    color = NightSeaPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
