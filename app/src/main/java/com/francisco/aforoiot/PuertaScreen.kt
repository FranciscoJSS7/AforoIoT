package com.francisco.aforoiot

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MODO PUERTA: simula el dispositivo IoT instalado en la entrada.
 * Cuenta personas con el sensor de proximidad del teléfono (o con botones) y envía cada
 * movimiento a Firebase. También RECIBE órdenes del administrador (bloqueo y aforo máximo).
 */
@Composable
fun PuertaScreen(modifier: Modifier = Modifier, onVolver: () -> Unit) {
    val sala = rememberSalaEnVivo()
    var mensaje by remember { mutableStateOf("") }
    var usarSensor by remember { mutableStateOf(false) }

    val registrar: (Boolean) -> Unit = { entrada ->
        AforoRepo.registrarMovimiento(entrada, "puerta") { _, msg -> mensaje = msg }
    }

    // Sensor de proximidad: cada vez que algo se acerca (lejos -> cerca) cuenta una entrada
    val context = LocalContext.current
    DisposableEffect(usarSensor) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        var estabaCerca = false
        var ultimo = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val cerca = e.values[0] < sensor!!.maximumRange
                val ahora = System.currentTimeMillis()
                if (cerca && !estabaCerca && ahora - ultimo > 1000) {
                    ultimo = ahora
                    registrar(true)
                }
                estabaCerca = cerca
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (usarSensor && sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else if (usarSensor) {
            mensaje = "Este dispositivo no tiene sensor de proximidad"
        }
        onDispose { sm.unregisterListener(listener) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onVolver) { Text("← Volver") }
            Spacer(Modifier.weight(1f))
            Text("Modo Puerta", fontWeight = FontWeight.Bold)
        }

        if (sala == null) {
            Spacer(Modifier.height(40.dp))
            Text("Conectando...")
            return@Column
        }

        Spacer(Modifier.height(16.dp))

        // Semáforo grande: lo que vería la persona en la entrada
        val color = colorAforo(sala)
        val estado = when {
            sala.bloqueado -> "ACCESO BLOQUEADO"
            sala.llena -> "AFORO COMPLETO\nNO PASAR"
            else -> "PUEDE PASAR"
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(color, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(estado, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("${sala.actual} / ${sala.maximo}", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("personas", color = Color.White)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(mensaje, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { registrar(true) },
                modifier = Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Verde)
            ) { Text("+ Entra", fontSize = 20.sp) }
            Button(
                onClick = { registrar(false) },
                modifier = Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rojo)
            ) { Text("− Sale", fontSize = 20.sp) }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Usar sensor de proximidad", modifier = Modifier.weight(1f))
            Switch(checked = usarSensor, onCheckedChange = { usarSensor = it })
        }
    }
}
