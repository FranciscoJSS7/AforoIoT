package com.francisco.aforoiot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

val Verde = Color(0xFF2E7D32)
val Amarillo = Color(0xFFF9A825)
val Rojo = Color(0xFFC62828)
val Gris = Color(0xFF616161)

/** Color según qué tan llena está la sala */
fun colorAforo(sala: Sala): Color = when {
    sala.bloqueado -> Gris
    sala.porcentaje >= 1f -> Rojo
    sala.porcentaje >= 0.8f -> Amarillo
    else -> Verde
}

/** Escucha la sala en tiempo real: cada cambio en Firebase actualiza la pantalla al instante. */
@Composable
fun rememberSalaEnVivo(): Sala? {
    var sala by remember { mutableStateOf<Sala?>(null) }
    DisposableEffect(Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                sala = snap.getValue(Sala::class.java)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        AforoRepo.salaRef.addValueEventListener(listener)
        onDispose { AforoRepo.salaRef.removeEventListener(listener) }
    }
    return sala
}

@androidx.compose.runtime.Composable
fun CargandoScreen(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.then(androidx.compose.ui.Modifier.fillMaxSize()),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) { androidx.compose.material3.CircularProgressIndicator() }
}

/** Usuario autenticado pero sin rol: no puede hacer nada hasta que un administrador le asigne uno. */
@androidx.compose.runtime.Composable
fun SinRolScreen(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, onCerrarSesion: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier.then(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp)),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Text("Usuario sin rol asignado")
        androidx.compose.material3.Text("Pide a un administrador que te asigne el rol \"admin\" o \"puerta\".")
        androidx.compose.material3.OutlinedButton(onClick = onCerrarSesion) { androidx.compose.material3.Text("Cerrar sesión") }
    }
}
