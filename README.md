# App de biofeedback para autorregulación en redes sociales

Aplicación Android del Trabajo de Fin de Grado **«Herramientas abiertas de biofeedback en
tiempo real para autorregulación en el uso de redes sociales»** (Carlos Sotomayor Romillo,
Grado en Ingeniería de Tecnologías de la Telecomunicación, EPS-UAM).

La app registra de forma sincronizada dos señales fisiológicas mientras el usuario navega por
redes sociales en un WebView integrado:

- **Frecuencia cardíaca (FC)** vía BLE desde una *Amazfit Band 7* (protocolo Huami 2021, con
  intercambio de claves ECDH B163 y AES-128).
- **Tasa de parpadeo** mediante la cámara frontal + *MediaPipe Face Landmarker* y el cálculo del
  *Eye Aspect Ratio* (EAR), con umbral calibrado por sujeto.

## Arquitectura

- **Lenguaje:** Kotlin (+ `ECDH_B163.java`) · **Patrón:** MVVM · **min SDK 26 / target 35**
- `band/` - `HuamiBleManager` (BLE + autenticación + keep-alive), `ECDH_B163`
- `camera/` - `CameraManager` (CameraX, cámara frontal)
- `detection/` - `FaceLandmarkerHelper`, `BlinkDetector` (EAR + máquina de estados), exportación CSV
- `viewmodel/` - `BlinkViewModel` (coordina cámara, BLE, sesiones y calibración)
- Actividades: `LauncherActivity`, `MainActivity` (sesión), `SettingsActivity`,
  `BandHRTestActivity` y `BlinkTestActivity` (diagnóstico)

## Configuración para compilar

Dos recursos **no están en el repositorio** (credenciales y un binario grande) y hay que añadirlos:

1. **Credenciales de la pulsera.** Copia la plantilla y rellénala con la MAC y el token de tu
   Amazfit Band 7 (obtenidos con [huami-token](https://github.com/argrento/huami-token)):
   ```
   cp app/src/main/java/com/dopamincheker/band/BandCredentials.kt.template \
      app/src/main/java/com/dopamincheker/band/BandCredentials.kt
   ```
2. **Modelo de MediaPipe.** Descarga `face_landmarker.task` desde
   [MediaPipe Face Landmarker](https://developers.google.com/mediapipe/solutions/vision/face_landmarker)
   y colócalo en `app/src/main/assets/face_landmarker.task`.

Después, abre el proyecto en Android Studio y compila normalmente (`Build > Build APK(s)`).

## Créditos y licencias de terceros

- **[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)** (AGPL-3.0) - referencia para
  la ingeniería inversa del protocolo Huami 2021; `ECDH_B163.java` está portado de su código.
- **[huami-token](https://github.com/argrento/huami-token)** - extracción del token de la pulsera.
- **[MediaPipe](https://developers.google.com/mediapipe)** (Apache-2.0) - detección de landmarks faciales.

## Privacidad

Este repositorio **no contiene datos de participantes**. Los registros fisiológicos generados por
la app (carpeta `Resultados/`) quedan excluidos del control de versiones por protección de datos.
