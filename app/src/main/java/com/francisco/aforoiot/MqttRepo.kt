package com.francisco.aforoiot

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/** Estado de la puerta (ESP32) tal como lo publica el microcontrolador en <base>/estado */
data class EstadoEsp32(val actual: Int, val maximo: Int, val bloqueado: Boolean)

/** Un evento de telemetría recibido por MQTT. [integro] = la suma de verificación coincide. */
data class EventoMqtt(val evento: String, val actual: Int, val maximo: Int, val ts: Long, val integro: Boolean)

/**
 * Cliente MQTT de la app: habla con el microcontrolador (ESP32) a través del broker.
 * - Recibe telemetría y estado en vivo; envía comandos (bloquear, aforo máximo, reiniciar).
 * - Conexión cifrada (TLS) y reconexión automática; si se pierde la conexión, los comandos
 *   quedan en un buffer y se envían al reconectar.
 *
 * Para pasar a AWS IoT Core solo cambian BROKER y la autenticación (ver README).
 */
object MqttRepo {
    // true = AWS IoT Core (TLS con certificado de cliente) | false = broker público de prueba (HiveMQ, TLS)
    const val USAR_AWS = true
    val BROKER = if (USAR_AWS) "ssl://a211pqxkv42qel-ats.iot.us-east-1.amazonaws.com:8883"
                       else "ssl://broker.hivemq.com:8883"
    // Client ID: en AWS debe coincidir con lo que permite la política IoT
    private const val CLIENT_ID_AWS = "aforo-app"
    // Certificado de cliente (PKCS12) guardado en app/src/main/assets/ (no se sube a GitHub)
    private const val P12_ARCHIVO = "aforo_cliente.p12"
    private const val P12_CLAVE = "aforo"
    const val BASE = "aforoiot/fsj7/sala1"
    private const val T_TELEMETRIA = "$BASE/telemetria"
    private const val T_ESTADO = "$BASE/estado"
    private const val T_CONEXION = "$BASE/conexion"
    private const val T_COMANDO = "$BASE/comando"

    // Estado observable desde las pantallas (Compose se actualiza solo al cambiar)
    var conexion by mutableStateOf("Desconectado")
        private set
    var dispositivo by mutableStateOf<EstadoEsp32?>(null)
        private set
    var esp32Online by mutableStateOf<Boolean?>(null)
        private set
    val telemetria = mutableStateListOf<EventoMqtt>()

    private val main = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    private var backoff = 1000L

    private var socketFactory: SSLSocketFactory? = null

    /** TLS con certificado de cliente (autenticación mutua), como exige AWS IoT Core. */
    private fun crearSocketFactory(context: Context): SSLSocketFactory {
        val ks = KeyStore.getInstance("PKCS12")
        context.assets.open(P12_ARCHIVO).use { ks.load(it, P12_CLAVE.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, P12_CLAVE.toCharArray())
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(kmf.keyManagers, null, null)   // Amazon Root CA 1 ya es de confianza en Android
        return ctx.socketFactory
    }

    fun conectar(context: Context) {
        if (client != null) return
        if (USAR_AWS) {
            try { socketFactory = crearSocketFactory(context.applicationContext) }
            catch (e: Exception) { conexion = "Falta el certificado: ${e.message}"; return }
        }
        val id = if (USAR_AWS) CLIENT_ID_AWS else "aforo-app-" + UUID.randomUUID().toString().take(8)
        val c = MqttAsyncClient(BROKER, id, MemoryPersistence())
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                backoff = 1000L
                main.post { conexion = "Conectado" }
                try { c.subscribe(arrayOf(T_ESTADO, T_TELEMETRIA, T_CONEXION), intArrayOf(1, 1, 1)) } catch (_: Exception) {}
            }
            override fun connectionLost(cause: Throwable?) {
                main.post { conexion = "Sin conexión, reconectando..." }
            }
            override fun messageArrived(topic: String, message: MqttMessage) {
                procesar(topic, String(message.payload))
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        // Comandos enviados sin conexión: se guardan y salen al reconectar
        c.setBufferOpts(DisconnectedBufferOptions().apply {
            isBufferEnabled = true; bufferSize = 50; isPersistBuffer = false; isDeleteOldestMessages = true
        })
        client = c
        intentarConexion()
    }

    private fun intentarConexion() {
        val c = client ?: return
        main.post { conexion = "Conectando..." }
        val opciones = MqttConnectOptions().apply {
            isAutomaticReconnect = true      // reconexión automática tras una pérdida
            maxReconnectDelay = 30000        // espera creciente, máximo 30 s
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            this@MqttRepo.socketFactory?.let { this.socketFactory = it }   // certificado de cliente (AWS)
        }
        try {
            c.connect(opciones, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    // La primera conexión no se reintenta sola: lo hacemos con espera creciente
                    main.post { conexion = "Sin conexión (${exception?.message}), reintentando en ${backoff / 1000} s" }
                    main.postDelayed({ if (client === c && !c.isConnected) intentarConexion() }, backoff)
                    backoff = minOf(backoff * 2, 30000L)
                }
            })
        } catch (e: Exception) {
            main.post { conexion = "Error: ${e.message}" }
        }
    }

    fun desconectar() {
        try { client?.disconnect() } catch (_: Exception) {}
        client = null
        conexion = "Desconectado"
    }

    // ---------- Recepción ----------
    private fun procesar(topic: String, texto: String) {
        try {
            when (topic) {
                T_ESTADO -> {
                    val j = JSONObject(texto)
                    val e = EstadoEsp32(j.getInt("actual"), j.getInt("maximo"), j.getBoolean("bloqueado"))
                    main.post { dispositivo = e }
                }
                T_CONEXION -> main.post { esp32Online = texto == "online" }
                T_TELEMETRIA -> {
                    val j = JSONObject(texto)
                    val tipo = j.getString("evento")
                    val act = j.getInt("actual"); val mx = j.getInt("maximo"); val ts = j.getLong("ts")
                    val ok = j.optInt("chk", -1) == checksum(tipo, act, mx, ts)  // integridad
                    val ev = EventoMqtt(tipo, act, mx, ts, ok)
                    HistorialRepo.guardar(ev)   // historial en Firestore (con caché sin conexión)
                    main.post {
                        telemetria.add(0, ev)
                        while (telemetria.size > 50) telemetria.removeAt(telemetria.size - 1)
                    }
                }
            }
        } catch (_: Exception) {
            // Mensaje mal formado: se ignora
        }
    }

    /** Misma suma de verificación que calcula el ESP32. */
    private fun checksum(tipo: String, act: Int, mx: Int, ts: Long): Int {
        var s = 0
        for (c in "$tipo|$act|$mx|$ts") s = (s * 31 + c.code) and 0xFFFF
        return s
    }

    // ---------- Envío de comandos al ESP32 ----------
    private fun comando(json: JSONObject) {
        val c = client ?: return
        try {
            c.publish(T_COMANDO, MqttMessage(json.toString().toByteArray()).apply { qos = 1 })
        } catch (e: Exception) {
            main.post { conexion = "No se pudo enviar: ${e.message}" }
        }
    }

    fun bloquear(valor: Boolean) = comando(JSONObject().put("accion", "bloquear").put("valor", valor))
    fun cambiarMaximo(valor: Int) {
        if (valor < 1 || valor > 1000) return
        comando(JSONObject().put("accion", "maximo").put("valor", valor))
    }
    fun reiniciar() = comando(JSONObject().put("accion", "reiniciar"))
}
