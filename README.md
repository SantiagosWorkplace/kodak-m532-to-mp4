# kodak-m532-to-mp4

Programa para Windows que detecta una **Kodak EasyShare M532** conectada por USB y, con un clic:

- Copia las **fotos** (JPG) con la fecha en que se sacaron.
- Convierte los **videos** (MOV Motion JPEG) a **MP4 H.264**, mucho más livianos y compatibles.
- Ordena todo por fecha en el Escritorio:

```
Escritorio\KODAK\
└── 2026-11-13\
    ├── 2026-11-13_20-05-12_100_1168.mp4
    └── Fotos\
        └── 2026-11-13_19-51-43_100_1167.JPG
```

> ✅ **Fotos probadas en una Kodak M532.** ⚠️ La conversión de video se probó con clips de prueba en el formato de la cámara, todavía no con un video grabado por ella.

Programas hermanos: [panasonic-avchd-to-mp4](https://github.com/SantiagosWorkplace/panasonic-avchd-to-mp4) (Panasonic HC-V130) y [sony-nex5-to-mp4](https://github.com/SantiagosWorkplace/sony-nex5-to-mp4) (Sony NEX-5).

## Por qué

La M532 no tiene modo de almacenamiento masivo: se conecta por **PTP** y no aparece como una unidad con letra (`E:`, `G:`...). Por eso los programas que buscan una tarjeta SD no la ven, y Kodak la pensó para su software EasyShare, que ya no existe.

Este programa la lee igual que el Explorador de Windows en "Este equipo": la reconoce por su identificador USB (`VID_040A&PID_0611`), recorre su carpeta `DCIM` y copia los archivos.

## Funciones

- **Detección automática** de la cámara por su identificador USB.
- Botón **Iniciar proceso**: no arranca solo.
- Cartel de estado: *Esperando cámara* → *Cámara lista* → *En proceso...* → *Finalizado*.
- **Fecha correcta**: por PTP la cámara no entrega la fecha de la forma habitual (Windows la muestra vacía o como 1899). El programa usa la **fecha de toma** de cada foto y la pasa a tu hora local.
- **No reprocesa** lo que ya existe en la carpeta de destino: podés conectar la cámara cuantas veces quieras.
- **Rápido**: usa la aceleración por hardware **Intel Quick Sync** si está disponible; si no, x264 por procesador.
- **No borra nada** de la cámara.

## Uso

1. Poné en hora el reloj de la cámara (las carpetas usan esa fecha).
2. Abrí `KODAK.exe`.
3. Conectá la cámara por USB y encendela. Si se abre otro programa (como la importación de fotos de Windows), cerralo.
4. Cuando el cartel diga **Cámara lista**, tocá **Iniciar proceso**.
5. Al ver **Finalizado** podés desconectar la cámara.

## Compilar

Requisitos: Windows, [JDK 21 (Temurin)](https://adoptium.net/) y [FFmpeg](https://www.gyan.dev/ffmpeg/builds/).

```powershell
winget install EclipseAdoptium.Temurin.21.JDK Gyan.FFmpeg
.\build.ps1
```

El resultado queda en `dist\KODAK\`: `KODAK.exe` con su propio Java y `ffmpeg.exe` incluidos, así que no hace falta instalar nada en la PC donde se use. Hay que copiar la carpeta completa.

## Detalles técnicos

| | |
|---|---|
| Conexión | PTP. Detección con `pnputil /enum-devices /connected /class WPD`; lectura y copia con el Shell de Windows (`Shell.Application`) desde PowerShell |
| Entrada | `DCIM\*\*.JPG` y videos `*.MOV` / `*.AVI` / `*.MP4` |
| Fecha | `System.Photo.DateTaken` (o, si falta, `System.DateModified`), convertida de UTC a hora local |
| Video | H.264 High, tamaño original, rango de color de video normal (el Motion JPEG viene en rango completo), Quick Sync `global_quality 18` o x264 `crf 18` |
| Audio | AAC 192 kbps |

## Licencia

Todavía no tiene licencia. Si querés usar el código, abrí un issue.

---

🤖 Generado con [Claude Code](https://claude.com/claude-code)
