package com.francisco.aforoiot

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query

/** Un evento guardado en Firestore. [pendiente] = aún no sincronizado con el servidor. */
data class EventoGuardado(
    val id: String,
    val evento: String,
    val actual: Long,
    val maximo: Long,
    val ts: Long,
    val integro: Boolean,
    val pendiente: Boolean
)

/**
 * Historial de telemetría en Firestore: /salas/{sala}/eventos/{id}
 * Firestore guarda una copia local en el teléfono: si no hay conexión, las escrituras quedan
 * en cola y se sincronizan solas al reconectar (almacenamiento temporal + sincronización).
 */
object HistorialRepo {
    private fun coleccion() = FirebaseFirestore.getInstance()
        .collection("salas").document(AforoRepo.SALA_ID).collection("eventos")

    /** Guarda un evento recibido por MQTT. El id es determinista para no duplicarlo. */
    fun guardar(e: EventoMqtt) {
        val ts = if (e.ts > 0) e.ts else System.currentTimeMillis() / 1000
        val id = "${ts}_${e.evento}_${e.actual}"
        coleccion().document(id).set(
            mapOf(
                "evento" to e.evento,
                "actual" to e.actual.toLong(),
                "maximo" to e.maximo.toLong(),
                "ts" to ts,
                "integro" to e.integro
            )
        ).addOnFailureListener { /* duplicado o sin permiso: se ignora */ }
    }

    /** Escucha los últimos 50 eventos en tiempo real. [onCambio] recibe la lista y si viene de la caché local. */
    fun escuchar(onCambio: (List<EventoGuardado>, Boolean) -> Unit): ListenerRegistration =
        coleccion().orderBy("ts", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val lista = snap.documents.map { d ->
                    EventoGuardado(
                        id = d.id,
                        evento = d.getString("evento") ?: "",
                        actual = d.getLong("actual") ?: 0,
                        maximo = d.getLong("maximo") ?: 0,
                        ts = d.getLong("ts") ?: 0,
                        integro = d.getBoolean("integro") ?: true,
                        pendiente = d.metadata.hasPendingWrites()
                    )
                }
                onCambio(lista, snap.metadata.isFromCache)
            }
}
