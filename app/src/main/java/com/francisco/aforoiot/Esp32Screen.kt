package com.francisco.aforoiot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla del microcontrolador (ESP32) conectado por MQTT:
 * muestra su estado y telemetría en vivo y le envía comandos (bloquear, aforo máximo, reiniciar).
 */
@Composable
fun Esp32Screen(modifier: Modifier = Modifier, onVolver: () -> Unit) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { MqttRepo.conectar(contexto) }

    // Historial guardado en Firestore (con caché local sin conexión)
    var historial by remember { mutableStateOf(listOf<EventoGuardado>()) }
    var desdeCache by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val reg = HistorialRepo.escuchar { lista, cache -> historial = lista; desdeCache = cache }
        onDispose { reg.remove() }
    }

    val dev = MqttRepo.dispositivo
    val formato = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onVolver) { Text("← Volver") }
            Spacer(Modifier.weight(1f))
            Text("Dispositivo ESP32 (MQTT)", fontWeight = FontWeight.Bold)
        }

        // Conexión
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Broker: ${MqttRepo.conexion}", style = MaterialTheme.typography.titleSmall)
                val online = when (MqttRepo.esp32Online) {
                    true -> "ESP32 en línea"
                    false -> "ESP32 desconectado"
                    null -> "ESP32: sin información todavía"
                }
                Text(online, style = MaterialTheme.typography.bodyMedium)
                Text(MqttRepo.BASE, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Estado del ESP32 + control
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (dev == null) {
                    Text("Esperando el estado del ESP32...")
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${dev.actual}", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                        Text(" / ${dev.maximo} personas", modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Aforo máximo", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { MqttRepo.cambiarMaximo(dev.maximo - 1) }) { Text("−") }
                        Text("  ${dev.maximo}  ", fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { MqttRepo.cambiarMaximo(dev.maximo + 1) }) { Text("+") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bloquear acceso", modifier = Modifier.weight(1f))
                        Switch(checked = dev.bloqueado, onCheckedChange = { MqttRepo.bloquear(it) })
                    }
                    TextButton(onClick = { MqttRepo.reiniciar() }) { Text("Reiniciar conteo a 0") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Historial (Firestore)", style = MaterialTheme.typography.titleMedium)
        val pendientes = historial.count { it.pendiente }
        Text(
            when {
                pendientes > 0 -> "☁ $pendientes pendiente(s) de sincronizar"
                desdeCache -> "Mostrando datos guardados en el teléfono (sin conexión)"
                else -> "Sincronizado"
            },
            style = MaterialTheme.typography.bodySmall
        )

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(historial) { ev ->
                val (icono, texto) = when (ev.evento) {
                    "entrada" -> "🟢" to "Entrada"
                    "salida" -> "🔴" to "Salida"
                    "rechazado" -> "⛔" to "Entrada rechazada"
                    else -> "⚙" to "Ajuste"
                }
                val hora = if (ev.ts > 0) formato.format(Date(ev.ts * 1000)) else "--"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$icono $texto  (${ev.actual}/${ev.maximo})${if (ev.integro) "" else "  ⚠ datos alterados"}${if (ev.pendiente) "  ☁" else ""}")
                    Text(hora, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
