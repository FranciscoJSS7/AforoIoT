# AforoIoT — Control de aforo en tiempo real

Sistema IoT de control de aforo para salas de reuniones, oficinas o eventos: un **microcontrolador ESP32** (simulado en Wokwi) cuenta entradas y salidas, y una **aplicación Android** (Kotlin + Jetpack Compose) lo monitorea y lo controla a distancia mediante **MQTT sobre AWS IoT Core**.

TI3042 Aplicaciones Móviles para IoT — **Unidad 3**: Integración de Smart Devices con Aplicaciones Externas — INACAP Curicó.

**Autores:** Francisco Sepúlveda y Maite Arenas.

## Problemática
En reuniones, oficinas y eventos no se sabe en tiempo real cuántas personas hay dentro de un lugar, lo que impide respetar el aforo máximo y tomar decisiones (cerrar el acceso, habilitar otra sala, etc.).

## Arquitectura

```
 ESP32 (Wokwi, MicroPython)  ── MQTT/TLS (certificado X.509) ──►  AWS IoT Core ──► DynamoDB
   sensor ultrasónico + botón                  ▲                       (regla IoT)   (tabla aforo_telemetria)
   LEDs + buzzer (actuadores)                  │
                                               ▼
 App Android (Kotlin/Compose) ── MQTT/TLS ──► AWS IoT Core
   │
   ├── Firebase Authentication  → login y roles (admin / puerta)
   └── Cloud Firestore          → historial con caché local (funciona sin conexión)
```

| Componente | Función |
|---|---|
| **ESP32 (Wokwi)** | Cuenta entradas con el sensor ultrasónico HC-SR04 y salidas con un botón. Semáforo con 3 LEDs y buzzer como actuadores. Publica telemetría y recibe comandos por MQTT o por el puerto serial. |
| **AWS IoT Core** | Broker MQTT con autenticación mutua por certificado. Una regla guarda la telemetría en DynamoDB. |
| **DynamoDB** | Almacenamiento externo (base no estructurada) de la telemetría. |
| **App Android** | Muestra el estado en vivo, envía comandos (bloquear acceso, aforo máximo, reiniciar), guarda el historial en Firestore. |
| **Firebase Auth + Firestore** | Inicio de sesión con roles; historial con copia local y sincronización automática. |

## Comunicación MQTT (base `aforoiot/fsj7/sala1`)

| Topic | Sentido | Contenido |
|---|---|---|
| `.../telemetria` | ESP32 → app / DynamoDB | `{"actual":3,"maximo":10,"evento":"entrada","ts":1794524595,"chk":36424}` |
| `.../estado` (retain) | ESP32 → app | `{"actual":3,"maximo":10,"bloqueado":false}` |
| `.../conexion` (retain, Last Will) | ESP32 → app | `online` / `offline` |
| `.../comando` | app → ESP32 | `{"accion":"bloquear","valor":true}`, `{"accion":"maximo","valor":15}`, `{"accion":"reiniciar"}` |

Los mismos comandos JSON se pueden escribir en el **monitor serial** del ESP32.

## Funcionalidades clave
- **Envío y recepción de instrucciones** en ambos sentidos (app ⇄ microcontrolador), por MQTT y por serial.
- **Reconexión automática** con espera creciente, en el ESP32 y en la app.
- **Almacenamiento temporal y sincronización:** cola de 40 eventos en el ESP32 mientras no hay conexión; caché de Firestore en la app.
- **Integridad de datos:** cada mensaje de telemetría incluye una suma de verificación (`chk`) que la app comprueba.
- **Seguridad** (ISO/IEC 27400): TLS, certificados X.509, roles, reglas de Firestore y de Firebase (ver [docs/UNIDAD3.md](docs/UNIDAD3.md)).

## Cómo ejecutarlo

### 1. Firebase
1. Crear un proyecto con **Authentication** (correo y contraseña), **Realtime Database** (roles) y **Firestore**.
2. Descargar `google-services.json` y copiarlo en `app/` (no se incluye en el repositorio).
3. Publicar las reglas de [`reglas_firebase.json`](reglas_firebase.json) (Realtime Database) y de [`reglas_firestore.rules`](reglas_firestore.rules) (Firestore).
4. Crear usuarios y asignar roles en `/usuarios/{uid}/rol` = `admin` o `puerta`.

### 2. AWS IoT Core
1. Crear el Thing y el certificado, adjuntar una política que permita conectar con los client ID `aforo-esp32-puerta` y `aforo-app`, y publicar/suscribirse a los topics de arriba.
2. Crear una regla IoT que guarde `aforoiot/fsj7/sala1/telemetria` en una tabla de DynamoDB.
3. **Certificados (no se incluyen en el repositorio):**
   - ESP32: pegar `CA`, `CERT` y `KEY` (DER, en base64) en un `secrets.py`/dentro de `main.py` de Wokwi.
   - App: empaquetar certificado y clave en `app/src/main/assets/aforo_cliente.p12` (PKCS12). Configurar el endpoint y el Client ID en `MqttRepo.kt`.

### 3. ESP32 en Wokwi
1. Crear un proyecto **ESP32 + MicroPython** en wokwi.com.
2. Copiar `wokwi_py/main.py` y `wokwi_py/diagram.json`.
3. Poner `USE_AWS = True` y el endpoint de IoT Core. (Con `USE_AWS = False` usa un broker público de prueba.)

### 4. App Android
Abrir en Android Studio, sincronizar Gradle y ejecutar en un emulador o dispositivo. Entrar como `admin` → **Dispositivo ESP32**.

## Estructura del repositorio
```
app/                     Aplicación Android (Kotlin + Compose)
  .../MqttRepo.kt          Cliente MQTT (AWS IoT Core)
  .../HistorialRepo.kt     Historial en Firestore
  .../Esp32Screen.kt       Pantalla del dispositivo ESP32
wokwi_py/                Código y circuito del ESP32 (MicroPython)
wokwi/                   Versión anterior en C++ (Arduino)
docs/                    Documentación (UNIDAD3.md, JUSTIFICACION.md de la Unidad 2)
reglas_firebase.json     Reglas de Realtime Database
reglas_firestore.rules   Reglas de Firestore
```
