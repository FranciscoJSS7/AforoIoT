# AforoIoT — Documento de justificación

**Asignatura:** TI3042 Aplicaciones Móviles para IoT — Unidad 2
**Estudiante:** Francisco Sepúlveda — INACAP Curicó

---

## 1. Problemática

En salas de reuniones, oficinas y eventos no existe una forma simple de saber **en tiempo real cuántas personas hay dentro** de un lugar. Esto provoca:

- Superar el aforo máximo permitido (riesgo de seguridad y de incumplir normativa).
- No poder reaccionar a tiempo (cerrar el acceso, habilitar otra sala).
- Falta de registro histórico para tomar decisiones (horas de mayor uso, rechazos).

**Objetivo:** construir un sistema IoT con dos dispositivos Android conectados de forma inalámbrica: un dispositivo en la entrada que **cuenta personas** (sensor) y un dispositivo del administrador que **monitorea y controla** el aforo.

## 2. Funcionalidades

| Dispositivo | Funcionalidad |
|---|---|
| Ambos | Inicio de sesión con correo y contraseña |
| Ambos | Acceso según rol (`admin` / `puerta`) |
| Puerta (sensor) | Cuenta entradas con el **sensor de proximidad** o con botones (+ Entra / − Sale) |
| Puerta (sensor) | Semáforo: verde (puede pasar), amarillo (≥ 80 %), rojo (aforo completo), gris (bloqueado) |
| Puerta (sensor) | Recibe en tiempo real las órdenes del administrador |
| Administrador | Ocupación en tiempo real (número, % y barra) |
| Administrador | Alertas al 80 % y al 100 % del aforo |
| Administrador | **Control remoto:** cambiar aforo máximo, bloquear acceso, reiniciar conteo |
| Administrador | Historial de entradas, salidas, rechazos y ajustes con fecha y hora |

## 3. Herramientas de desarrollo

| Herramienta | Uso | Justificación |
|---|---|---|
| Android Studio | IDE | Herramienta oficial de Google para Android. |
| Kotlin | Lenguaje | Lenguaje recomendado por Google para Android; más seguro frente a errores de valores nulos. |
| Jetpack Compose (Material 3) | Interfaz | Estándar actual de diseño de interfaces Android; sigue las guías Material Design. |
| Firebase Authentication | Login | Ver punto 5. |
| Firebase Realtime Database | Datos | Sincronización en tiempo real entre dispositivos (ver punto 4). |
| SensorManager (proximidad) | Sensor IoT | API nativa de Android para leer sensores del dispositivo. |
| Git + GitHub | Control de versiones | Respaldo y entrega del código. |

### ¿Por qué Firebase y no SQLite o MySQL?

| Criterio | SQLite (local) | API + MySQL | **Firebase (elegido)** |
|---|---|---|---|
| Comunicación entre 2 dispositivos | No (los datos quedan en un solo teléfono) | Sí, pero hay que programar y alojar un servidor | **Sí, incluida** |
| Tiempo real | No | Requiere *polling* o WebSockets propios | **Sí, automático** |
| Seguridad (HTTPS, autenticación) | Manual | Manual | **Incluida** |
| Escalabilidad | Baja | Depende del servidor | **Alta (servicio administrado)** |
| Facilidad de implementación | Alta | Baja | **Alta** |

Como el problema exige que **dos dispositivos compartan los mismos datos al instante**, SQLite no sirve por sí solo, y montar una API con MySQL agrega un servidor que hay que mantener y asegurar. Firebase entrega las tres cosas (datos compartidos, tiempo real y seguridad) con un costo de implementación bajo y un plan gratuito suficiente para el proyecto.

**Modelo de datos (JSON):**
```
usuarios/{uid}/rol              -> "admin" | "puerta"
salas/sala1                     -> { nombre, actual, maximo, bloqueado }
eventos/sala1/{id}              -> { tipo, ts, por, origen }
```

## 4. Comunicación inalámbrica: WiFi / Internet

| Criterio | Bluetooth | **WiFi + Firebase (elegido)** | MQTT |
|---|---|---|---|
| Distancia | ~10 m, mismo lugar | **Ilimitada (vía Internet)** | Ilimitada |
| Consumo de energía | Bajo | Medio (aceptable: dispositivos con carga) | Bajo |
| Estabilidad | Se corta al alejarse o con obstáculos | **Alta; reconexión automática y caché sin conexión** | Alta, requiere un *broker* propio |
| Varios dispositivos a la vez | Limitado (emparejamiento 1 a 1) | **Sí** | Sí |

**Justificación:** el administrador no necesariamente está junto a la puerta (puede estar en otra oficina o fuera del edificio), por lo que Bluetooth no sirve por su alcance. MQTT es una buena opción para IoT, pero requiere instalar y asegurar un servidor *broker*. Firebase Realtime Database usa una conexión persistente cifrada (WebSocket sobre TLS) que funciona como un canal de publicación/suscripción similar a MQTT, sin servidor propio. Además, si se pierde la conexión, la app guarda los cambios y los envía al reconectarse.

## 5. Autenticación: Firebase Authentication

| Criterio | Justificación |
|---|---|
| Seguridad | Las contraseñas **no se guardan en el teléfono**; Firebase las almacena con *hash* en sus servidores y bloquea temporalmente tras varios intentos fallidos. |
| Escalabilidad | Soporta miles de usuarios sin cambios en la app. |
| Facilidad | Integración directa con las reglas de seguridad de la base de datos (`auth.uid`). |

Frente a SQLite (credenciales guardadas en el dispositivo, vulnerables si se roba el teléfono) y a una API propia con MySQL (hay que programar cifrado, sesiones y protección contra ataques), Firebase Authentication es la opción más segura con menor esfuerzo.

## 6. Diseño de la interfaz (pantallas y navegación)

```
[Login] ──> [Inicio: elegir modo] ──> [Puerta / Sensor]
                     │
                     └──(solo admin)──> [Administrador]
```

| Pantalla | Contenido |
|---|---|
| Login | Correo, contraseña (con botón Ver/Ocultar), mensajes de error claros |
| Inicio | Correo y rol del usuario; botones según el rol; cerrar sesión |
| Puerta | Semáforo grande con personas/aforo, botones + Entra / − Sale, interruptor del sensor de proximidad |
| Administrador | Alerta de color, ocupación con barra de %, controles (máximo, bloqueo, reinicio) e historial |

*(Las capturas de cada pantalla se muestran en el video demostrativo.)*

## 7. Seguridad IoT según ISO/IEC 27400

La norma ISO/IEC 27400 entrega lineamientos de seguridad y privacidad para soluciones IoT. Medidas aplicadas:

| Lineamiento | Medida implementada |
|---|---|
| **Autenticación** | Login obligatorio con Firebase Authentication. Sin sesión no se puede leer ni escribir nada. |
| **Control de acceso y mínimo privilegio** | Roles `admin` y `puerta`. La puerta solo puede contar; solo el admin cambia el aforo, bloquea o ve el historial. Los roles se asignan solo desde la consola: ningún usuario puede cambiarse el rol. |
| **Cifrado de datos en tránsito** | Toda comunicación va por HTTPS/TLS. Se deshabilitó el tráfico sin cifrar en la app (`usesCleartextTraffic="false"`). |
| **Protección de credenciales** | Las contraseñas no se almacenan en el dispositivo. El archivo de configuración `google-services.json` no se publica en GitHub. |
| **Validación en el servidor** | Las reglas de Firebase validan cada dato (tipos, rangos) y **rechazan una entrada si la sala está llena o bloqueada**, aunque la app fuera modificada. |
| **Integridad y trazabilidad (auditoría)** | Historial que **no se puede editar ni borrar**; cada registro guarda la hora del servidor y el correo real del usuario (no falsificables). |
| **Consistencia con varios dispositivos** | El conteo usa transacciones atómicas para no perder datos si dos dispositivos cuentan al mismo tiempo. |
| **Denegar por defecto** | Las reglas parten con `".read": false, ".write": false` y solo abren lo necesario. |

Las reglas completas están en [`reglas_firebase.json`](../reglas_firebase.json).

## 8. Pruebas de funcionamiento

Pruebas realizadas con una **tablet Samsung Galaxy Tab S8+** (rol administrador) y un **emulador Android** (rol puerta), conectados por WiFi.

| # | Prueba | Resultado esperado | Resultado |
|---|---|---|---|
| 1 | Login con contraseña incorrecta | Mensaje "Correo o contraseña incorrectos" | ✅ OK |
| 2 | Login con credenciales correctas | Ingresa a la pantalla de inicio | ✅ OK |
| 3 | Usuario con rol `puerta` | Solo ve el modo Puerta | ✅ OK |
| 4 | Usuario con rol `admin` | Ve modo Puerta y Administrador | ✅ OK |
| 5 | Entrada desde la puerta | El conteo sube al instante en la tablet | ✅ OK |
| 6 | Salida desde la puerta | El conteo baja; no baja de 0 | ✅ OK |
| 7 | Aforo alcanzado | Puerta en rojo, "Acceso denegado", alerta en admin | ✅ OK |
| 8 | Bloqueo desde el admin | Puerta en gris y rechaza entradas; salidas permitidas | ✅ OK |
| 9 | Cambio de aforo máximo | Se refleja al instante en la puerta | ✅ OK |
| 10 | Sensor de proximidad | Cada acercamiento cuenta una entrada | ✅ OK (sensor virtual del emulador) |
| 11 | Historial | Registra entradas, salidas, rechazos y ajustes con hora | ✅ OK |
| 12 | Seguridad en servidor | Entrada rechazada por Firebase con sala llena | ✅ OK |
