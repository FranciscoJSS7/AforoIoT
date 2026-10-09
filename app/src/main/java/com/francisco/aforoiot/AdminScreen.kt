package com.francisco.aforoiot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MODO ADMINISTRADOR: monitorea el aforo en tiempo real y CONTROLA el dispositivo de la puerta
 * (cambia el aforo máximo, bloquea el acceso o reinicia el conteo).
 */
@Composable
fun AdminScreen(modifier: Modifier = Modifier, onVolver: () -> Unit) {
    val sala = rememberSalaEnVivo()

    // Historial: últimos 30 eventos, en tiempo real
    var eventos by remember { mutableStateOf(listOf<Evento>()) }
    DisposableEffect(Unit) {
        val query = AforoRepo.eventosRef.orderByChild("ts").limitToLast(30)
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                eventos = snap.children.mapNotNull { it.getValue(Evento::class.java) }.reversed()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        query.addValueEventListener(listener)
        onDispose { query.removeEventListener(listener) }
    }

    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onVolver) { Text("← Volver") }
            Spacer(Modifier.weight(1f))
            Text("Administrador", fontWeight = FontWeight.Bold)
        }

        if (sala == null) {
            Text("Conectando...")
            return@Column
        }

        // Alerta
        val color = colorAforo(sala)
        val alerta = when {
            sala.bloqueado -> "Acceso bloqueado manualmente"
            sala.porcentaje >= 1f -> "⚠ ALERTA: aforo máximo alcanzado"
            sala.porcentaje >= 0.8f -> "Atención: sobre el 80% del aforo"
            else -> null
        }
        alerta?.let {
            Box(
                Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp)).padding(12.dp)
            ) { Text(it, color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
        }

        // Monitoreo
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(sala.nombre, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${sala.actual}", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = color)
                    Text(" / ${sala.maximo} personas", modifier = Modifier.padding(bottom = 10.dp))
                }
                LinearProgressIndicator(
                    progress = { sala.porcentaje.coerceIn(0f, 1f) },
                    color = color,
                    modifier = Modifier.fillMaxWidth().height(10.dp)
                )
                Text("${(sala.porcentaje * 100).toInt()}% de ocupación", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Control
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aforo máximo", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { AforoRepo.cambiarMaximo(sala.maximo - 1) }) { Text("−") }
                    Text("  ${sala.maximo}  ", fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { AforoRepo.cambiarMaximo(sala.maximo + 1) }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bloquear acceso", modifier = Modifier.weight(1f))
                    Switch(checked = sala.bloqueado, onCheckedChange = { AforoRepo.cambiarBloqueo(it) })
                }
                TextButton(onClick = { AforoRepo.reiniciarConteo() }) { Text("Reiniciar conteo a 0") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Historial", style = MaterialTheme.typography.titleMedium)

        val formato = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(eventos) { ev ->
                val (icono, texto) = when (ev.tipo) {
                    "entrada" -> "🟢" to "Entrada"
                    "salida" -> "🔴" to "Salida"
                    "rechazado" -> "⛔" to "Entrada rechazada"
                    else -> "⚙" to "Ajuste del administrador"
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$icono $texto")
                    Text(formato.format(Date(ev.ts)), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
