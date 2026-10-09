# AforoIoT — Control de aforo en tiempo real

Aplicación Android (Kotlin + Jetpack Compose) que simula un sistema IoT de control de aforo para salas de reuniones, oficinas o eventos.

Proyecto individual — TI3042 Aplicaciones Móviles para IoT, Unidad 2 — INACAP Curicó.

## Problemática
En reuniones, oficinas y eventos no se sabe en tiempo real cuántas personas hay dentro de un lugar, lo que impide respetar el aforo máximo y tomar decisiones (cerrar el acceso, habilitar otra sala, etc.).

## Solución
Dos dispositivos Android conectados de forma inalámbrica (WiFi / Internet) a través de Firebase:

| Dispositivo | Rol | Función |
|---|---|---|
| Teléfono / emulador | **Puerta (sensor IoT)** | Cuenta entradas y salidas con el sensor de proximidad o con botones. Muestra un semáforo: verde (puede pasar), amarillo (sobre 80%), rojo (aforo completo), gris (bloqueado). |
| Tablet | **Administrador (monitor)** | Ve la ocupación en tiempo real, recibe alertas, cambia el aforo máximo, bloquea el acceso y revisa el historial. |

## Tecnologías
- Android Studio, Kotlin, Jetpack Compose
- Firebase Authentication (correo y contraseña)
- Firebase Realtime Database (sincronización en tiempo real)
- Sensor de proximidad (`SensorManager`)

## Seguridad (ISO/IEC 27400)
- Autenticación obligatoria; la contraseña no se guarda en el dispositivo.
- Control de acceso por roles (`admin` / `puerta`); los roles solo se asignan desde la consola.
- Comunicación cifrada: solo HTTPS/TLS (`usesCleartextTraffic="false"`).
- Reglas de seguridad en el servidor (`reglas_firebase.json`): validan datos y rechazan entradas si la sala está llena o bloqueada.
- Historial de auditoría inmutable (no se puede editar ni borrar), con hora del servidor y usuario.
- Conteo con transacciones para evitar errores con varios dispositivos a la vez.

## Cómo ejecutarlo
1. Crear un proyecto en Firebase con Authentication (correo/contraseña) y Realtime Database.
2. Descargar `google-services.json` y copiarlo en `app/` (no se incluye en el repositorio).
3. Publicar las reglas de `reglas_firebase.json`.
4. Crear usuarios y asignar roles en `/usuarios/{uid}/rol` = `admin` o `puerta`.
5. Abrir en Android Studio y ejecutar en dos dispositivos.

## Autor
Francisco Sepúlveda
