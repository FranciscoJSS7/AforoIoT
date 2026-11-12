/*
  AforoIoT - Puerta (ESP32) - TI3042 Unidad 3
  Sensor: HC-SR04 (entrada) + boton (salida). Actuadores: 3 LEDs (semaforo) + buzzer.
  Comunicacion: MQTT (broker de prueba o AWS IoT Core) + comandos por puerto Serial.

  Topics (TOPIC_BASE = "aforoiot/fsj7/sala1"):
    <base>/telemetria  -> ESP32 publica cada evento   {"actual","maximo","evento","ts","chk"}
    <base>/estado      -> ESP32 publica (retain)      {"actual","maximo","bloqueado"}
    <base>/conexion    -> "online"/"offline" (retain, Last Will)
    <base>/comando     -> ESP32 recibe                {"accion":"bloquear","valor":true}
                                                      {"accion":"maximo","valor":15}
                                                      {"accion":"reiniciar"}
  Los mismos JSON de comando se pueden escribir en el monitor Serial.
*/
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <time.h>

// ---------- CONFIGURACION ----------
#define USE_AWS 0   // 0 = broker publico de prueba (1883) | 1 = AWS IoT Core (8883, TLS + certificados)

const char* WIFI_SSID = "Wokwi-GUEST";   // red simulada de Wokwi
const char* WIFI_PASS = "";

const char* TOPIC_BASE = "aforoiot/fsj7/sala1";

#if USE_AWS
const char* MQTT_HOST = "XXXXXXXX-ats.iot.us-east-1.amazonaws.com";  // endpoint de AWS IoT Core
const int   MQTT_PORT = 8883;
const char* CLIENT_ID = "aforo-esp32-puerta";  // debe coincidir con la politica IoT
// Pegar aqui los certificados entregados por AWS (NO subirlos a GitHub)
const char* CA_CERT   = R"EOF(-----BEGIN CERTIFICATE-----
PEGAR_AMAZON_ROOT_CA1
-----END CERTIFICATE-----)EOF";
const char* DEV_CERT  = R"EOF(-----BEGIN CERTIFICATE-----
PEGAR_CERTIFICADO_DEL_THING
-----END CERTIFICATE-----)EOF";
const char* DEV_KEY   = R"EOF(-----BEGIN RSA PRIVATE KEY-----
PEGAR_CLAVE_PRIVADA
-----END RSA PRIVATE KEY-----)EOF";
#else
const char* MQTT_HOST = "broker.hivemq.com";
const int   MQTT_PORT = 1883;
const char* CLIENT_ID = "aforo-esp32-puerta-fsj7";
#endif

// ---------- PINES ----------
const int PIN_TRIG = 5, PIN_ECHO = 18, PIN_BTN = 4;
const int PIN_VERDE = 25, PIN_AMARILLO = 26, PIN_ROJO = 27, PIN_BUZZER = 14;

// ---------- ESTADO ----------
int  actual = 0, maximo = 10;
bool bloqueado = false;

WiFiClient plano;
WiFiClientSecure seguro;
PubSubClient mqtt(USE_AWS ? (Client&)seguro : (Client&)plano);

String tTelemetria, tEstado, tConexion, tComando;

// Cola de eventos para cuando no hay conexion (almacenamiento temporal)
struct Evento { char tipo[12]; int actual; int maximo; uint32_t ts; };
const int CAP = 40;
Evento cola[CAP];
int colaInicio = 0, colaCount = 0;

unsigned long proximoIntento = 0, backoff = 1000;
unsigned long ultimaLectura = 0, ultimoCerca = 0, ultimoBtn = 0;
bool estabaCerca = false, btnPrev = true;
String lineaSerial;

// ---------- UTILIDADES ----------
uint32_t ahora() {
  time_t t = time(nullptr);
  return t > 1700000000 ? (uint32_t)t : 0;  // 0 si aun no hay hora NTP
}

// Suma de verificacion simple para comprobar integridad del mensaje
uint16_t checksum(const char* tipo, int act, int max, uint32_t ts) {
  char buf[64];
  snprintf(buf, sizeof(buf), "%s|%d|%d|%lu", tipo, act, max, (unsigned long)ts);
  uint32_t s = 0;
  for (char* p = buf; *p; p++) s = (s * 31 + (uint8_t)*p) & 0xFFFF;
  return (uint16_t)s;
}

void actualizarLeds() {
  bool llena = actual >= maximo;
  bool alto = actual * 100 >= maximo * 80;
  digitalWrite(PIN_ROJO, (llena || bloqueado) ? HIGH : LOW);
  digitalWrite(PIN_AMARILLO, (!llena && !bloqueado && alto) ? HIGH : LOW);
  digitalWrite(PIN_VERDE, (!llena && !bloqueado && !alto) ? HIGH : LOW);
}

void pitar(int veces) {
  for (int i = 0; i < veces; i++) {
    digitalWrite(PIN_BUZZER, HIGH); delay(120);
    digitalWrite(PIN_BUZZER, LOW);  delay(120);
  }
}

// ---------- ENVIO ----------
void publicarEstado() {
  JsonDocument d;
  d["actual"] = actual; d["maximo"] = maximo; d["bloqueado"] = bloqueado;
  char out[128]; serializeJson(d, out);
  if (mqtt.connected()) mqtt.publish(tEstado.c_str(), out, true);  // retain
}

void enviarEvento(const Evento& e) {
  JsonDocument d;
  d["actual"] = e.actual; d["maximo"] = e.maximo; d["evento"] = e.tipo; d["ts"] = e.ts;
  d["chk"] = checksum(e.tipo, e.actual, e.maximo, e.ts);
  char out[160]; serializeJson(d, out);
  mqtt.publish(tTelemetria.c_str(), out);
}

void registrarEvento(const char* tipo) {
  Evento e; strncpy(e.tipo, tipo, sizeof(e.tipo)); e.tipo[sizeof(e.tipo) - 1] = 0;
  e.actual = actual; e.maximo = maximo; e.ts = ahora();

  // Envio por Serial (siempre)
  JsonDocument d;
  d["actual"] = e.actual; d["maximo"] = e.maximo; d["evento"] = e.tipo; d["ts"] = e.ts;
  d["chk"] = checksum(e.tipo, e.actual, e.maximo, e.ts);
  serializeJson(d, Serial); Serial.println();

  if (mqtt.connected()) {
    enviarEvento(e);
  } else {
    // Sin conexion: guardar en cola (si esta llena, descarta el mas antiguo)
    if (colaCount == CAP) { colaInicio = (colaInicio + 1) % CAP; colaCount--; }
    cola[(colaInicio + colaCount) % CAP] = e; colaCount++;
    Serial.printf("[cola] sin conexion, pendientes: %d\n", colaCount);
  }
}

void vaciarCola() {
  while (colaCount > 0 && mqtt.connected()) {
    enviarEvento(cola[colaInicio]);
    colaInicio = (colaInicio + 1) % CAP; colaCount--;
  }
  Serial.println("[cola] sincronizada");
}

// ---------- LOGICA ----------
void movimiento(bool entrada) {
  if (entrada) {
    if (bloqueado || actual >= maximo) { registrarEvento("rechazado"); pitar(3); return; }
    actual++; registrarEvento("entrada");
  } else {
    if (actual <= 0) return;
    actual--; registrarEvento("salida");
  }
  actualizarLeds(); publicarEstado();
}

// Procesa un comando JSON (llega por MQTT o por Serial)
void procesarComando(const char* json) {
  JsonDocument d;
  if (deserializeJson(d, json)) { Serial.println("[cmd] JSON invalido"); return; }
  const char* accion = d["accion"] | "";
  if (!strcmp(accion, "bloquear")) {
    bloqueado = d["valor"] | false;
  } else if (!strcmp(accion, "maximo")) {
    int v = d["valor"] | 0;
    if (v < 1 || v > 1000) { Serial.println("[cmd] maximo fuera de rango"); return; }
    maximo = v;
  } else if (!strcmp(accion, "reiniciar")) {
    actual = 0;
  } else { Serial.println("[cmd] accion desconocida"); return; }
  pitar(1); registrarEvento("ajuste"); actualizarLeds(); publicarEstado();
}

void onMensaje(char* topic, byte* payload, unsigned int len) {
  char buf[200]; len = min(len, (unsigned int)199);
  memcpy(buf, payload, len); buf[len] = 0;
  Serial.printf("[mqtt] %s: %s\n", topic, buf);
  procesarComando(buf);
}

// ---------- CONEXION (reconexion automatica con espera creciente) ----------
void mantenerConexion() {
  if (WiFi.status() != WL_CONNECTED) {
    if (millis() >= proximoIntento) {
      Serial.println("[wifi] reconectando...");
      WiFi.reconnect();
      backoff = min(backoff * 2, 30000UL); proximoIntento = millis() + backoff;
    }
    return;
  }
  if (!mqtt.connected() && millis() >= proximoIntento) {
    Serial.println("[mqtt] conectando...");
    if (mqtt.connect(CLIENT_ID, nullptr, nullptr, tConexion.c_str(), 1, true, "offline")) {
      Serial.println("[mqtt] conectado");
      backoff = 1000;
      mqtt.subscribe(tComando.c_str());
      mqtt.publish(tConexion.c_str(), "online", true);
      publicarEstado();
      vaciarCola();
    } else {
      backoff = min(backoff * 2, 30000UL); proximoIntento = millis() + backoff;
      Serial.printf("[mqtt] fallo rc=%d, reintento en %lu ms\n", mqtt.state(), backoff);
    }
  }
}

float leerDistanciaCm() {
  digitalWrite(PIN_TRIG, LOW); delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);
  long us = pulseIn(PIN_ECHO, HIGH, 25000);
  return us == 0 ? -1 : us * 0.0343f / 2;
}

// ---------- SETUP / LOOP ----------
void setup() {
  Serial.begin(115200);
  setCpuFrequencyMhz(80);           // ahorro de energia
  pinMode(PIN_TRIG, OUTPUT); pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_BTN, INPUT_PULLUP);
  pinMode(PIN_VERDE, OUTPUT); pinMode(PIN_AMARILLO, OUTPUT);
  pinMode(PIN_ROJO, OUTPUT);  pinMode(PIN_BUZZER, OUTPUT);
  actualizarLeds();

  tTelemetria = String(TOPIC_BASE) + "/telemetria";
  tEstado     = String(TOPIC_BASE) + "/estado";
  tConexion   = String(TOPIC_BASE) + "/conexion";
  tComando    = String(TOPIC_BASE) + "/comando";

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true);              // modem sleep: menos consumo
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  configTime(0, 0, "pool.ntp.org");

#if USE_AWS
  seguro.setCACert(CA_CERT);
  seguro.setCertificate(DEV_CERT);
  seguro.setPrivateKey(DEV_KEY);
#endif
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setCallback(onMensaje);
  mqtt.setBufferSize(512);
  Serial.println("AforoIoT listo. Comandos por Serial: {\"accion\":\"bloquear\",\"valor\":true}");
}

void loop() {
  mantenerConexion();
  mqtt.loop();

  // Comandos por Serial (una linea JSON)
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') { lineaSerial.trim(); if (lineaSerial.length()) procesarComando(lineaSerial.c_str()); lineaSerial = ""; }
    else lineaSerial += c;
  }

  // Sensor ultrasonico cada 200 ms: entrada cuando alguien se acerca (< 50 cm)
  if (millis() - ultimaLectura >= 200) {
    ultimaLectura = millis();
    float dist = leerDistanciaCm();
    bool cerca = dist > 0 && dist < 50;
    if (cerca && !estabaCerca && millis() - ultimoCerca > 1500) { ultimoCerca = millis(); movimiento(true); }
    estabaCerca = cerca;
  }

  // Boton = salida (con anti-rebote)
  bool btn = digitalRead(PIN_BTN);
  if (!btn && btnPrev && millis() - ultimoBtn > 250) { ultimoBtn = millis(); movimiento(false); }
  btnPrev = btn;

  delay(10);
}
