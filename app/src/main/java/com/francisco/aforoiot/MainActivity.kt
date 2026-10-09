package com.francisco.aforoiot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.francisco.aforoiot.ui.theme.AforoIoTTheme
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AforoIoTTheme {
                val auth = remember { FirebaseAuth.getInstance() }
                var usuario by remember { mutableStateOf(auth.currentUser) }
                // Pantalla actual: "inicio", "puerta" o "admin"
                var pantalla by remember { mutableStateOf("inicio") }
                // Rol del usuario: null = cargando, "" = sin rol asignado
                var rol by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(usuario?.uid) {
                    rol = null
                    usuario?.uid?.let { uid -> AforoRepo.leerRol(uid) { rol = it ?: "" } }
                }

                BackHandler(enabled = usuario != null && pantalla != "inicio") { pantalla = "inicio" }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val mod = Modifier.padding(innerPadding)
                    when {
                        usuario == null -> LoginScreen(
                            modifier = mod,
                            onLoginOk = { usuario = auth.currentUser; pantalla = "inicio" }
                        )
                        rol == null -> CargandoScreen(mod)
                        rol == "" -> SinRolScreen(mod, onCerrarSesion = { auth.signOut(); usuario = null })
                        pantalla == "puerta" -> PuertaScreen(mod, onVolver = { pantalla = "inicio" })
                        pantalla == "admin" && rol == "admin" -> AdminScreen(mod, onVolver = { pantalla = "inicio" })
                        else -> InicioScreen(
                            email = usuario?.email ?: "",
                            rol = rol ?: "",
                            modifier = mod,
                            onPuerta = { pantalla = "puerta" },
                            onAdmin = { pantalla = "admin" },
                            onCerrarSesion = { auth.signOut(); usuario = null }
                        )
                    }
                }
            }
        }
    }
}
