package com.francisco.aforoiot

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Exclude
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction

/** Estado de una sala, tal como está guardado en Firebase en /salas/{id} */
data class Sala(
    val nombre: String = "Sala de reuniones",
    val actual: Long = 0,
    val maximo: Long = 10,
    val bloqueado: Boolean = false
) {
    @get:Exclude val porcentaje: Float get() = if (maximo > 0) actual.toFloat() / maximo else 0f
    @get:Exclude val llena: Boolean get() = actual >= maximo
}

/** Un registro del historial en /eventos/{sala}/{id} */
data class Evento(
    val tipo: String = "",      // entrada | salida | rechazado | ajuste
    val ts: Long = 0,           // hora del servidor (milisegundos)
    val por: String = "",       // correo del usuario que lo generó
    val origen: String = ""     // puerta | admin
)

/**
 * Acceso a Firebase Realtime Database.
 * Toda la comunicación va cifrada por HTTPS/TLS y protegida por las reglas de seguridad.
 */
object AforoRepo {
    const val SALA_ID = "sala1"
    private const val DB_URL = "https://aforoiot-default-rtdb.firebaseio.com"

    private val db = FirebaseDatabase.getInstance(DB_URL)
    val salaRef = db.getReference("salas").child(SALA_ID)
    val eventosRef = db.getReference("eventos").child(SALA_ID)
    private val usuariosRef = db.getReference("usuarios")

    /**
     * Lee el rol del usuario desde /usuarios/{uid}/rol ("admin" o "puerta").
     * El rol solo se puede asignar desde la consola de Firebase: el usuario no puede cambiárselo.
     */
    fun leerRol(uid: String, resultado: (String?) -> Unit) {
        usuariosRef.child(uid).child("rol").get()
            .addOnSuccessListener { resultado(it.getValue(String::class.java)) }
            .addOnFailureListener { resultado(null) }
    }

    private fun usuario() = FirebaseAuth.getInstance().currentUser?.email ?: "desconocido"

    /** Crea la sala con valores por defecto si todavía no existe. */
    fun crearSalaSiNoExiste() {
        salaRef.get().addOnSuccessListener { snap ->
            if (!snap.exists()) {
                salaRef.setValue(Sala())
            }
        }
    }

    /**
     * Registra una entrada o salida.
     * - Usa una TRANSACCIÓN sobre el contador para que si dos dispositivos cuentan al mismo tiempo
     *   no se pierda ningún conteo.
     * - El límite de aforo y el bloqueo los valida el SERVIDOR (reglas de Firebase): aunque alguien
     *   modificara la app, Firebase rechaza una entrada si la sala está llena o bloqueada.
     */
    fun registrarMovimiento(entrada: Boolean, origen: String, resultado: (Boolean, String) -> Unit) {
        salaRef.child("actual").runTransaction(object : Transaction.Handler {
            override fun doTransaction(datos: MutableData): Transaction.Result {
                val actual = datos.getValue(Long::class.java) ?: 0L
                if (!entrada && actual <= 0) return Transaction.abort()
                datos.value = if (entrada) actual + 1 else actual - 1
                return Transaction.success(datos)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                when {
                    committed -> {
                        guardarEvento(if (entrada) "entrada" else "salida", origen)
                        resultado(true, if (entrada) "Entrada registrada" else "Salida registrada")
                    }
                    error?.code == DatabaseError.PERMISSION_DENIED && entrada -> {
                        guardarEvento("rechazado", origen)
                        resultado(false, "Acceso denegado")
                    }
                    error != null -> resultado(false, "Error: ${error.message}")
                    else -> resultado(false, "No hay personas dentro")
                }
            }
        })
    }

    fun cambiarMaximo(nuevo: Long) {
        if (nuevo < 1) return
        salaRef.child("maximo").setValue(nuevo)
        guardarEvento("ajuste", "admin")
    }

    fun cambiarBloqueo(bloquear: Boolean) {
        salaRef.child("bloqueado").setValue(bloquear)
        guardarEvento("ajuste", "admin")
    }

    fun reiniciarConteo() {
        salaRef.child("actual").setValue(0)
        guardarEvento("ajuste", "admin")
    }

    private fun guardarEvento(tipo: String, origen: String) {
        eventosRef.push().setValue(
            mapOf(
                "tipo" to tipo,
                "ts" to ServerValue.TIMESTAMP,
                "por" to usuario(),
                "origen" to origen
            )
        )
    }
}
