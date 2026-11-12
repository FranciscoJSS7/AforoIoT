"""
AforoIoT - Puerta (ESP32, MicroPython) - TI3042 Unidad 3
Sensor: HC-SR04 (entrada) + boton (salida). Actuadores: 3 LEDs (semaforo) + buzzer.
Comunicacion: MQTT (broker de prueba o AWS IoT Core) + comandos por Serial.

Topics (TOPIC_BASE = "aforoiot/fsj7/sala1"):
  <base>/telemetria -> publica cada evento  {"actual","maximo","evento","ts","chk"}
  <base>/estado     -> publica (retain)     {"actual","maximo","bloqueado"}
  <base>/conexion   -> "online"/"offline" (retain, Last Will)
  <base>/comando    -> recibe               {"accion":"bloquear","valor":true}
                                            {"accion":"maximo","valor":15}
                                            {"accion":"reiniciar"}
Los mismos JSON de comando se pueden escribir en el monitor Serial.
"""
import sys
import time
import json
import select
import network
import machine
import ntptime
from machine import Pin, time_pulse_us
from umqtt.simple import MQTTClient

# ---------- CONFIGURACION ----------
USE_AWS = True    # False = broker publico de prueba (1883) | True = AWS IoT Core (8883, TLS)

WIFI_SSID = "Wokwi-GUEST"   # red simulada de Wokwi
WIFI_PASS = ""

TOPIC_BASE = "aforoiot/fsj7/sala1"

if USE_AWS:
    MQTT_HOST = "a211pqxkv42qel-ats.iot.us-east-1.amazonaws.com"  # endpoint de AWS IoT Core
    MQTT_PORT = 8883
    CLIENT_ID = "aforo-esp32-puerta"  # debe coincidir con la politica IoT
    # Certificados entregados por AWS, convertidos a DER (ver nota abajo). NO subir a GitHub.
    # Se guardan como archivos en el ESP32: ca.der, cert.der, key.der
    #   openssl x509 -in AmazonRootCA1.pem -outform DER -out ca.der
    #   openssl x509 -in certificate.pem.crt -outform DER -out cert.der
    #   openssl rsa  -in private.pem.key   -outform DER -out key.der
else:
    MQTT_HOST = "broker.hivemq.com"
    MQTT_PORT = 1883
    CLIENT_ID = "aforo-esp32-puerta-fsj7"

T_TELEMETRIA = TOPIC_BASE + "/telemetria"
T_ESTADO = TOPIC_BASE + "/estado"
T_CONEXION = TOPIC_BASE + "/conexion"
T_COMANDO = TOPIC_BASE + "/comando"

# ---------- PINES ----------
trig = Pin(5, Pin.OUT)
echo = Pin(18, Pin.IN)
btn = Pin(4, Pin.IN, Pin.PULL_UP)
led_verde = Pin(25, Pin.OUT)
led_amarillo = Pin(26, Pin.OUT)
led_rojo = Pin(27, Pin.OUT)
buzzer = Pin(14, Pin.OUT)

# ---------- ESTADO ----------
actual = 0
maximo = 10
bloqueado = False

cliente = None
conectado = False
cola = []            # eventos pendientes cuando no hay conexion
COLA_MAX = 40
proximo_intento = 0
backoff = 1000       # ms
ultimo_ping = 0

machine.freq(80000000)   # ahorro de energia: CPU a 80 MHz
wlan = network.WLAN(network.STA_IF)
wlan.active(True)
try:
    wlan.config(pm=network.WLAN.PM_POWERSAVE)   # modem sleep
except Exception:
    pass


# ---------- UTILIDADES ----------
def ahora():
    # El epoch de MicroPython en ESP32 empieza el ano 2000: se convierte a Unix
    t = time.time() + 946684800
    return t if t > 1700000000 else 0


def checksum(tipo, act, mx, ts):
    # Suma de verificacion simple para comprobar la integridad del mensaje
    s = 0
    for c in "%s|%d|%d|%d" % (tipo, act, mx, ts):
        s = (s * 31 + ord(c)) & 0xFFFF
    return s


def actualizar_leds():
    llena = actual >= maximo
    alto = actual * 100 >= maximo * 80
    led_rojo.value(1 if (llena or bloqueado) else 0)
    led_amarillo.value(1 if (not llena and not bloqueado and alto) else 0)
    led_verde.value(1 if (not llena and not bloqueado and not alto) else 0)


def pitar(veces):
    for _ in range(veces):
        buzzer.on()
        time.sleep_ms(120)
        buzzer.off()
        time.sleep_ms(120)


def distancia_cm():
    trig.off()
    time.sleep_us(2)
    trig.on()
    time.sleep_us(10)
    trig.off()
    us = time_pulse_us(echo, 1, 25000)
    return -1 if us < 0 else us * 0.0343 / 2


# ---------- ENVIO ----------
def publicar(topic, msg, retain=False):
    global conectado
    try:
        cliente.publish(topic, msg, retain)
        return True
    except Exception as e:
        print("[mqtt] error al publicar:", e)
        conectado = False
        return False


def publicar_estado():
    if conectado:
        publicar(T_ESTADO, json.dumps({"actual": actual, "maximo": maximo, "bloqueado": bloqueado}), True)


def armar_evento(tipo):
    ts = ahora()
    return {"actual": actual, "maximo": maximo, "evento": tipo, "ts": ts,
            "chk": checksum(tipo, actual, maximo, ts)}


def registrar_evento(tipo):
    ev = armar_evento(tipo)
    txt = json.dumps(ev)
    print(txt)                      # envio por Serial (siempre)
    if conectado and publicar(T_TELEMETRIA, txt):
        return
    # Sin conexion: se guarda en la cola (si esta llena, se descarta el mas antiguo)
    if len(cola) >= COLA_MAX:
        cola.pop(0)
    cola.append(txt)
    print("[cola] sin conexion, pendientes:", len(cola))


def vaciar_cola():
    while cola and conectado:
        if publicar(T_TELEMETRIA, cola[0]):
            cola.pop(0)
        else:
            return
    print("[cola] sincronizada")


# ---------- LOGICA ----------
def movimiento(entrada):
    global actual
    if entrada:
        if bloqueado or actual >= maximo:
            registrar_evento("rechazado")
            pitar(3)
            return
        actual += 1
        registrar_evento("entrada")
    else:
        if actual <= 0:
            return
        actual -= 1
        registrar_evento("salida")
    actualizar_leds()
    publicar_estado()


def procesar_comando(texto):
    """Procesa un comando JSON (llega por MQTT o por Serial)."""
    global actual, maximo, bloqueado
    try:
        d = json.loads(texto)
    except Exception:
        print("[cmd] JSON invalido")
        return
    accion = d.get("accion", "")
    if accion == "bloquear":
        bloqueado = bool(d.get("valor", False))
    elif accion == "maximo":
        v = d.get("valor", 0)
        if not isinstance(v, int) or v < 1 or v > 1000:
            print("[cmd] maximo fuera de rango")
            return
        maximo = v
    elif accion == "reiniciar":
        actual = 0
    else:
        print("[cmd] accion desconocida")
        return
    pitar(1)
    registrar_evento("ajuste")
    actualizar_leds()
    publicar_estado()


def on_mensaje(topic, msg):
    print("[mqtt]", topic, msg)
    procesar_comando(msg)


# ---------- CONEXION (reconexion automatica con espera creciente) ----------
def contexto_tls():
    import ssl
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.verify_mode = ssl.CERT_REQUIRED
    import secrets   # secrets.py: CA, CERT y KEY en DER (no se sube a GitHub)
    ctx.load_verify_locations(cadata=secrets.CA)
    ctx.load_cert_chain(secrets.CERT, secrets.KEY)
    return ctx


def mantener_conexion():
    global cliente, conectado, proximo_intento, backoff, ultimo_ping
    ahora_ms = time.ticks_ms()

    if not wlan.isconnected():
        conectado = False
        if time.ticks_diff(ahora_ms, proximo_intento) >= 0:
            print("[wifi] conectando...")
            wlan.connect(WIFI_SSID, WIFI_PASS)
            backoff = min(backoff * 2, 30000)
            proximo_intento = time.ticks_add(ahora_ms, backoff)
        return

    if not conectado and time.ticks_diff(ahora_ms, proximo_intento) >= 0:
        try:
            ntptime.settime()   # hora real ANTES de conectar (TLS valida las fechas del certificado)
        except Exception as e:
            print("[ntp] sin hora todavia:", e)
        if USE_AWS and ahora() == 0:
            proximo_intento = time.ticks_add(ahora_ms, 3000)   # sin hora correcta TLS falla: reintentar
            return
        print("[mqtt] conectando...")
        try:
            ssl_ctx = contexto_tls() if USE_AWS else None
            cliente = MQTTClient(CLIENT_ID, MQTT_HOST, port=MQTT_PORT, keepalive=30,
                                 ssl=ssl_ctx)
            cliente.set_last_will(T_CONEXION, b"offline", retain=True, qos=0)
            cliente.set_callback(on_mensaje)
            cliente.connect()
            cliente.subscribe(T_COMANDO)
            conectado = True
            backoff = 1000
            ultimo_ping = ahora_ms
            print("[mqtt] conectado")
            publicar(T_CONEXION, b"online", True)
            publicar_estado()
            vaciar_cola()
            try:
                ntptime.settime()   # hora real para los timestamps
            except Exception:
                pass
        except Exception as e:
            backoff = min(backoff * 2, 30000)
            proximo_intento = time.ticks_add(ahora_ms, backoff)
            print("[mqtt] fallo:", e, "- reintento en", backoff, "ms")


# ---------- PROGRAMA PRINCIPAL ----------
poll = select.poll()
poll.register(sys.stdin, select.POLLIN)

actualizar_leds()
print('AforoIoT listo. Comandos por Serial: {"accion":"bloquear","valor":true}')

ultima_lectura = time.ticks_ms()
ultimo_cerca = 0
ultimo_btn = 0
estaba_cerca = False
btn_prev = 1

while True:
    mantener_conexion()

    # Mensajes entrantes y keepalive de MQTT
    if conectado:
        try:
            cliente.check_msg()
            if time.ticks_diff(time.ticks_ms(), ultimo_ping) > 15000:
                cliente.ping()
                ultimo_ping = time.ticks_ms()
        except Exception as e:
            print("[mqtt] conexion perdida:", e)
            conectado = False

    # Comandos por Serial (una linea JSON)
    if poll.poll(0):
        linea = sys.stdin.readline().strip()
        if linea:
            procesar_comando(linea)

    # Sensor ultrasonico cada 200 ms: entrada cuando alguien se acerca (< 50 cm)
    t = time.ticks_ms()
    if time.ticks_diff(t, ultima_lectura) >= 200:
        ultima_lectura = t
        d = distancia_cm()
        cerca = 0 < d < 50
        if cerca and not estaba_cerca and time.ticks_diff(t, ultimo_cerca) > 1500:
            ultimo_cerca = t
            movimiento(True)
        estaba_cerca = cerca

    # Boton = salida (con anti-rebote)
    b = btn.value()
    if b == 0 and btn_prev == 1 and time.ticks_diff(t, ultimo_btn) > 250:
        ultimo_btn = t
        movimiento(False)
    btn_prev = b

    time.sleep_ms(10)
