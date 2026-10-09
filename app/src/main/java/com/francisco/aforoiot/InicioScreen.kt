package com.francisco.aforoiot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Después del login se elige qué rol cumple este dispositivo. */
@Composable
fun InicioScreen(
    email: String,
    rol: String,
    modifier: Modifier = Modifier,
    onPuerta: () -> Unit,
    onAdmin: () -> Unit,
    onCerrarSesion: () -> Unit
) {
    LaunchedEffect(rol) { if (rol == "admin") AforoRepo.crearSalaSiNoExiste() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("¿Qué será este dispositivo?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("$email  ·  rol: $rol", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(32.dp))

        Button(onClick = onPuerta, modifier = Modifier.fillMaxWidth().height(90.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Puerta / Sensor", style = MaterialTheme.typography.titleLarge)
                Text("Cuenta las personas que entran y salen", textAlign = TextAlign.Center)
            }
        }
        // Solo los administradores ven el modo Administrador
        if (rol == "admin") {
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdmin, modifier = Modifier.fillMaxWidth().height(90.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Administrador", style = MaterialTheme.typography.titleLarge)
                Text("Monitorea y controla el aforo", textAlign = TextAlign.Center)
            }
        }
        }

        Spacer(Modifier.height(40.dp))
        OutlinedButton(onClick = onCerrarSesion) { Text("Cerrar sesión") }
    }
}
