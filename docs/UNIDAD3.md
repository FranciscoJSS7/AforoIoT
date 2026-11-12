# AforoIoT — Documentación técnica, Unidad 3

**Asignatura:** TI3042 Aplicaciones Móviles para IoT — Unidad 3
**Estudiantes:** Francisco Sepúlveda y Maite Arenas — INACAP Curicó

Este documento describe cómo la solución cumple los criterios de la Unidad 3 (comunicación segura entre una app Android y un microcontrolador, según la familia ISO 27000). Las pruebas se marcan como ✅ (realizada) o ⬜ (pendiente de ejecutar o de documentar con evidencia).

---

## 1. Arquitectura y flujo de datos

```
ESP32 (Wokwi) ── MQTT/TLS ──► AWS IoT Core ──► regla IoT ──► DynamoDB (aforo_telemetria)
                                   ▲
                                   └──── MQTT/TLS ──── App Android ── Firebase Auth / Firestore
```

1. El ESP32 lee el sensor ultrasónico (entradas) y el botón (salidas), actualiza el semáforo y publica un evento JSON en `aforoiot/fsj7/sala1/telemetria`.
2. AWS IoT Core reparte el mensaje a la app y, mediante una regla, lo guarda en DynamoDB.
3. La app muestra el estado en vivo, guarda el historial en Firestore y publica comandos en `.../comando`.
4. El ESP32 recibe los comandos y los ejecuta (bloquear, aforo máximo, reiniciar), y responde con un evento `ajuste` y el nuevo `estado`.

## 2. Comunicación serial (criterio 3.1.2 y 3.1.3)

- **Envío desde el microcontrolador:** cada evento se imprime por el puerto serial como JSON (además de publicarse por MQTT).
- **Recepción en el microcontrolador:** acepta comandos JSON escritos en el monitor serial, por ejemplo `{"accion":"bloquear","valor":true}`. Se procesan con la misma función que los comandos recibidos por MQTT.
- **Desde la aplicación móvil:** la app envía y recibe las mismas instrucciones por MQTT (Wokwi simula el microcontrolador, por lo que la app no se conecta por cable serial físico).

## 3. Almacenamiento temporal y sincronización (3.1.1.4 y 3.1.2.7)

| Nivel | Mecanismo |
|---|---|
| **ESP32** | Si no hay conexión, los eventos se guardan en una cola de hasta 40 entradas (se descarta el más antiguo si se llena). Al reconectar se publican todos (`[cola] sincronizada`). |
| **App** | Los eventos recibidos se guardan en **Firestore**, que mantiene una copia local en el teléfono: sin conexión se sigue viendo el historial y las escrituras pendientes se envían solas al reconectar (la pantalla muestra `☁ N pendiente(s)`). Los comandos enviados sin conexión quedan en un buffer de 50 mensajes. |
| **Almacenamiento externo** | AWS IoT Core guarda cada evento en DynamoDB (base NoSQL) mediante una regla. Firestore es el almacenamiento externo del lado de la app. |

## 4. Reconexión automática (3.1.1.3)

- **ESP32:** reintenta WiFi y MQTT con espera creciente (1 s, 2 s, 4 s… hasta 30 s). Sincroniza la hora (NTP) antes de conectar, porque TLS valida las fechas del certificado.
- **App:** el cliente MQTT usa reconexión automática (espera máxima 30 s). Si la primera conexión falla, reintenta con espera creciente y muestra el motivo en pantalla.
- **Last Will:** si el ESP32 se cae, el broker publica `conexion = offline` y la app muestra "ESP32 desconectado".

## 5. Integridad de los datos (3.1.1.2)

Cada mensaje de telemetría incluye un campo `chk` (suma de verificación calculada sobre `evento|actual|maximo|ts`). La app recalcula el valor al recibir el mensaje y, si no coincide, marca el evento como "⚠ datos alterados". El broker, además, cifra el canal con TLS, y las reglas de Firestore validan tipo y rango de cada campo.

> Limitación: `chk` detecta alteraciones accidentales o simples, pero no sustituye una firma criptográfica (HMAC). Se deja como mejora.

## 6. Seguridad según ISO/IEC 27400 (3.1.1.1, 3.1.4.11)

| Lineamiento | Medida implementada |
|---|---|
| **Cifrado en tránsito** | MQTT sobre TLS 1.2 (puerto 8883) hacia AWS IoT Core. Tráfico sin cifrar deshabilitado en la app (`usesCleartextTraffic="false"`). |
| **Autenticación del dispositivo** | Autenticación mutua con certificado X.509 (ESP32 y app). El servidor se verifica con Amazon Root CA 1. |
| **Autenticación del usuario** | Firebase Authentication (correo y contraseña); la contraseña no se guarda en el teléfono. |
| **Control de acceso** | Roles `admin` y `puerta` (Realtime Database). Solo el admin ve la pantalla del dispositivo ESP32. Políticas IoT que limitan conexión, publicación y suscripción a los topics del proyecto. |
| **Denegar por defecto** | Reglas de Firestore y de Realtime Database parten con acceso denegado y solo abren lo necesario. |
| **Integridad y trazabilidad** | Historial inmutable en Firestore (solo `create`, sin `update` ni `delete`) y campo `chk` en los mensajes. |
| **Secretos fuera del repositorio** | `google-services.json`, certificados, claves y `secrets.py` están en `.gitignore`. |
| **Copias de seguridad** | `allowBackup="false"` en la app, para que el certificado empaquetado no se copie en backups. |

## 7. Protección de datos personales (3.1.4.12)

- La telemetría (conteo de personas, aforo máximo, hora) **no contiene datos personales**: no se identifica a quienes entran.
- El único dato personal es el **correo del usuario** de la app, administrado por Firebase Authentication; no viaja por MQTT ni se guarda en Firestore (los eventos de Firestore no incluyen el usuario).
- Los datos viajan siempre cifrados y el acceso al historial exige sesión iniciada.

## 8. Plan de contingencia y continuidad operativa (3.1.4.13)

| Falla | Efecto | Respuesta automática | Recuperación |
|---|---|---|---|
| **Cae el WiFi del ESP32** | No hay telemetría ni comandos | El ESP32 sigue contando y guarda los eventos en la cola (40). El semáforo local sigue funcionando. | Reconecta con espera creciente y sincroniza la cola. |
| **Cae internet en la app** | La app deja de ver el estado | Muestra "Sin conexión, reconectando…" y sigue mostrando el historial de la caché de Firestore. Los comandos quedan en buffer. | Reconexión automática; Firestore sincroniza lo pendiente. |
| **AWS IoT Core no disponible** | Se corta la comunicación app ⇄ ESP32 | ESP32 y app reintentan con espera creciente (máx. 30 s). El ESP32 sigue operando de forma autónoma. | Reconexión al volver el servicio. |
| **Certificado vencido o revocado** | AWS rechaza la conexión | La app y el ESP32 muestran el error de conexión. | Generar un certificado nuevo, adjuntarlo a la política y reemplazar el de la app y el del ESP32. |
| **Se pierde el ESP32 (corte de energía)** | El broker publica `offline` | La app muestra "ESP32 desconectado". | Al reencender, el ESP32 publica `online` y su estado. |
| **Firestore o DynamoDB no disponibles** | No se guarda el historial | Firestore guarda localmente; DynamoDB recibe después los mensajes que el broker entregue. | Sincronización automática al volver. |
| **Mensaje con formato inválido** | Comando no válido | El ESP32 lo ignora (`[cmd] JSON invalido`) y no cambia su estado. | Reenviar el comando. |

## 9. Evaluación del consumo de energía (3.1.3.10)

**Medidas aplicadas para reducir el consumo:**

| Dispositivo | Medida |
|---|---|
| **ESP32** | CPU a 80 MHz (`machine.freq`), WiFi en modo `PM_POWERSAVE` (modem sleep), lectura del sensor cada 200 ms (no continua), `sleep` de 10 ms entre ciclos. |
| **App** | La conexión MQTT se abre al entrar a la pantalla del dispositivo y se cierra al cerrar sesión. `keepAlive` de 30 s. Sin sondeos periódicos: los datos se reciben por suscripción. |

**Cómo se mide (pendiente de ejecutar con datos reales):**
- *App:* con el emulador o un dispositivo, `adb shell dumpsys batterystats --reset`, usar la app unos minutos y revisar `dumpsys batterystats` y el Battery Historian. ⬜
- *ESP32:* Wokwi es un simulador y no entrega consumo real. En una placa física se mediría la corriente con un multímetro o un medidor USB, comparando WiFi sin ahorro y con modem sleep. ⬜

## 10. Compatibilidad con Android (3.1.4)

La app tiene `minSdk 24` (Android 7.0) y `targetSdk 37`. Probada en el emulador **Medium Phone API 37**. ⬜ Pendiente: probar al menos en otra versión (por ejemplo API 30) para documentar la compatibilidad.

## 11. Pruebas de funcionamiento

| # | Prueba | Resultado |
|---|---|---|
| 1 | El ESP32 (Wokwi) se conecta a AWS IoT Core con certificado | ✅ `[mqtt] conectado` |
| 2 | Las entradas y salidas del ESP32 llegan a DynamoDB | ✅ tabla `aforo_telemetria` con los eventos |
| 3 | La app se conecta a AWS IoT Core con certificado | ✅ "Broker: Conectado" |
| 4 | La app recibe el estado y la telemetría del ESP32 en vivo | ⬜ verificar con el ESP32 en línea tras la conexión de la app a AWS |
| 5 | La app envía comandos (bloquear, aforo máximo) y el ESP32 los ejecuta | ✅ comprobado con broker de prueba · ⬜ repetir con AWS |
| 6 | Comandos por el monitor serial del ESP32 | ⬜ |
| 7 | Cola sin conexión del ESP32 y sincronización al reconectar | ✅ `[cola] sincronizada` en el monitor serial |
| 8 | Historial guardado en Firestore | ✅ colección `salas/sala1/eventos` |
| 9 | Historial visible sin conexión (caché de Firestore) | ⬜ |
| 10 | Rechazo de entrada con aforo completo (buzzer + evento `rechazado`) | ✅ visto en el monitor serial |
| 11 | AWS rechaza una conexión con un Client ID que la política no permite | ✅ AWS cierra la conexión |

## 12. Limitaciones conocidas y mejoras

- El ESP32 y la app comparten el mismo certificado (con distinto Client ID) para simplificar la prueba. En producción cada dispositivo tendría su propio certificado y política (mínimo privilegio).
- El certificado de la app va empaquetado en la APK; en producción se usarían credenciales temporales (Amazon Cognito) o aprovisionamiento por dispositivo.
- Las reglas de Firestore exigen sesión iniciada pero no distinguen roles (los roles viven en Realtime Database).
- La clave de la tabla DynamoDB es `sala + ts` (segundos): dos eventos en el mismo segundo se sobrescribirían. Mejora: usar una clave única por mensaje (`newuuid()` o `timestamp()` en milisegundos).
- `chk` es una suma de verificación, no una firma criptográfica.
- El consumo de energía real no se pudo medir en simulación.
