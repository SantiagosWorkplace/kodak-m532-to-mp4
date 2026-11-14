import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta la Kodak EasyShare M532 al conectarla por USB y, al tocar "Iniciar proceso", copia las
 * fotos y convierte los videos (MOV Motion JPEG) a MP4 H.264. Todo va a Escritorio\KODAK\<fecha>\
 * (las fotos en la subcarpeta Fotos). Lo que ya existe en el destino no se procesa de nuevo.
 * No borra nada de la cámara.
 *
 * La M532 no tiene modo de almacenamiento masivo: se conecta por PTP y no aparece como unidad.
 * Los archivos se leen con el Shell de Windows, la misma vía que usa el Explorador en "Este equipo".
 */
public class KodakConverter {

    static final String CAMERA_NAME = "Kodak EasyShare M532";
    /** VID/PID USB de la cámara. */
    static final String USB_ID = "VID_040A&PID_0611";
    /** Nombre con el que aparece en "Este equipo" ("M532 Digital Camera"). */
    static final String SHELL_NAME = "M532";
    static final String OUTPUT_FOLDER = "KODAK";
    static final String PHOTOS_FOLDER = "Fotos";
    /** crf (x264) o global_quality (Quick Sync). Más bajo = más calidad y más bitrate. */
    static final int QUALITY = 18;
    static final int POLL_MS = 3000;

    enum Kind { PHOTO, VIDEO }

    /** Un archivo de la cámara: su ruta dentro de la cámara, nombre, fecha y tipo. */
    record Media(String path, String name, long modified, Kind kind) {
    }

    static final Set<String> PHOTO_EXT = Set.of("JPG", "JPEG");
    static final Set<String> VIDEO_EXT = Set.of("MOV", "AVI", "MP4");

    static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)");
    static final Pattern OUT_TIME = Pattern.compile("out_time_us=(\\d+)");

    static final Color GRAY = new Color(0x6B7280);
    static final Color BLUE = new Color(0x2563EB);
    static final Color ORANGE = new Color(0xD97706);
    static final Color GREEN = new Color(0x16A34A);
    static final Color RED = new Color(0xDC2626);

    final JFrame frame = new JFrame(OUTPUT_FOLDER);
    final JLabel badge = new JLabel();
    final JLabel status = new JLabel("Conectá la cámara por USB.");
    final JProgressBar progress = new JProgressBar(0, 1000);
    final JTextArea log = new JTextArea(14, 60);
    final JButton startButton = new JButton("Iniciar proceso");

    Path outputRoot = desktop().resolve(OUTPUT_FOLDER);
    String ffmpeg;
    List<String> videoEncoder;
    Path ptpScript;

    volatile boolean cameraConnected;
    volatile boolean running;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new KodakConverter().start());
    }

    void start() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        buildUi();
        setBadge("Esperando cámara", GRAY);

        ffmpeg = findFfmpeg();
        if (ffmpeg == null) {
            setBadge("Error", RED);
            status.setText("No se encontró ffmpeg.");
            log("ERROR: no se encontró ffmpeg.exe. Ponelo en la misma carpeta que este programa.");
            return;
        }
        log("Carpeta de salida: " + outputRoot);

        Thread watcher = new Thread(() -> {
            videoEncoder = pickEncoder(ffmpeg);
            log(videoEncoder.contains("h264_qsv")
                    ? "Conversión acelerada con la placa de video Intel (Quick Sync)."
                    : "Conversión por procesador (no hay Quick Sync disponible).");
            watchLoop();
        }, "watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    void buildUi() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        progress.setStringPainted(true);
        progress.setString("");

        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 13f));
        badge.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        JButton open = new JButton("Abrir carpeta " + OUTPUT_FOLDER);
        open.addActionListener(e -> {
            try {
                Files.createDirectories(outputRoot);
                Desktop.getDesktop().open(outputRoot.toFile());
            } catch (IOException ex) {
                log("No se pudo abrir la carpeta: " + ex.getMessage());
            }
        });

        startButton.setEnabled(false);
        startButton.setFont(startButton.getFont().deriveFont(Font.BOLD));
        startButton.addActionListener(e -> {
            if (!cameraConnected || running) return;
            running = true;
            startButton.setEnabled(false);
            Thread worker = new Thread(() -> {
                try {
                    processCamera();
                } catch (Exception ex) {
                    setBadge("Finalizado con errores", RED);
                    setStatus("Error: " + ex.getMessage());
                    log("ERROR: " + ex);
                } finally {
                    running = false;
                    SwingUtilities.invokeLater(() -> startButton.setEnabled(cameraConnected));
                }
            }, "worker");
            worker.setDaemon(true);
            worker.start();
        });

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 6));
        buttons.add(open);
        buttons.add(startButton);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));
        statusRow.add(badge);
        statusRow.add(status);

        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.add(statusRow, BorderLayout.NORTH);
        left.add(progress, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(12, 8));
        top.add(left, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(top, BorderLayout.NORTH);
        root.add(new JScrollPane(log), BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ---------------------------------------------------------------- detección

    void watchLoop() {
        while (true) {
            try {
                boolean present = isCameraConnected();
                if (present && !cameraConnected) {
                    cameraConnected = true;
                    log("Cámara " + CAMERA_NAME + " detectada. Tocá \"Iniciar proceso\".");
                    if (!running) {
                        setBadge("Cámara lista", BLUE);
                        setStatus("Cámara conectada");
                        SwingUtilities.invokeLater(() -> startButton.setEnabled(true));
                    }
                } else if (!present && cameraConnected) {
                    cameraConnected = false;
                    log("Cámara desconectada.");
                    SwingUtilities.invokeLater(() -> startButton.setEnabled(false));
                    if (!running) {
                        setBadge("Esperando cámara", GRAY);
                        setStatus("Conectá la cámara por USB.");
                    }
                }
            } catch (Exception e) {
                log("Error: " + e.getMessage());
            }
            sleep(POLL_MS);
        }
    }

    /** true si Windows tiene la cámara conectada como dispositivo portátil (WPD). */
    static boolean isCameraConnected() {
        StringBuilder out = new StringBuilder();
        runLines(List.of("pnputil", "/enum-devices", "/connected", "/class", "WPD"),
                line -> out.append(line.toUpperCase()).append('\n'));
        return out.indexOf("USB\\" + USB_ID) >= 0;
    }

    // ----------------------------------------------------------------- proceso

    void processCamera() throws IOException {
        setBadge("En proceso...", ORANGE);
        setProgress(0);

        List<Media> media = listCamera();
        if (media == null) {
            setBadge("Finalizado con errores", RED);
            setStatus("No se pudo leer la cámara. Desconectala y volvé a conectarla.");
            return;
        }

        Map<Media, Path> pending = new LinkedHashMap<>();
        int photos = 0, newPhotos = 0, videos = 0, newVideos = 0;
        for (Kind kind : Kind.values()) {
            for (Media m : media) {
                if (m.kind() != kind) continue;
                Path target = targetFor(m);
                boolean isNew = !Files.exists(target);
                if (isNew) pending.put(m, target);
                if (kind == Kind.PHOTO) {
                    photos++;
                    if (isNew) newPhotos++;
                } else {
                    videos++;
                    if (isNew) newVideos++;
                }
            }
        }
        log(videos + " videos (" + newVideos + " nuevos) y " + photos + " fotos (" + newPhotos + " nuevas) en la cámara.");

        int failed = 0;
        Path staging = Files.createTempDirectory("kodak-ptp");
        try {
            Map<Media, File> local = pending.isEmpty() ? Map.of() : fetch(new ArrayList<>(pending.keySet()), staging);
            int i = 0;
            for (Map.Entry<Media, Path> e : pending.entrySet()) {
                i++;
                Media m = e.getKey();
                File source = local.get(m);
                if (source == null) {
                    failed++;
                    continue;
                }
                if (m.kind() == Kind.VIDEO) {
                    setStatus("Convirtiendo video " + i + " de " + pending.size() + ": " + m.name());
                    setProgress(0);
                    if (!convert(source, m.name(), e.getValue())) failed++;
                } else {
                    setStatus("Guardando " + i + " de " + pending.size() + ": " + m.name());
                    setProgress(i * 1000 / pending.size());
                    if (!saveFile(source, m, e.getValue())) failed++;
                }
            }
        } finally {
            deleteTree(staging);
        }

        int done = pending.size() - failed;
        setProgress(pending.isEmpty() ? -1 : 1000);
        String summary = pending.isEmpty()
                ? "No había nada nuevo. Podés desconectar la cámara."
                : done + " archivos listos" + (failed > 0 ? ", " + failed + " con error" : "")
                  + ". Podés desconectar la cámara.";
        if (failed > 0) setBadge("Finalizado con errores", RED);
        else setBadge("Finalizado", GREEN);
        setStatus(summary);
        log(summary);
    }

    static Kind kindOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toUpperCase();
        if (PHOTO_EXT.contains(ext)) return Kind.PHOTO;
        if (VIDEO_EXT.contains(ext)) return Kind.VIDEO;
        return null;
    }

    /**
     * Destino según la fecha del archivo: <fecha>\<fecha_hora>_<nombre>.mp4 para los videos y
     * <fecha>\Fotos\<fecha_hora>_<nombre> para las fotos.
     */
    Path targetFor(Media m) {
        Date date = new Date(m.modified());
        String day = new SimpleDateFormat("yyyy-MM-dd").format(date);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(date);
        Path dir = outputRoot.resolve(day);
        if (m.kind() == Kind.PHOTO) return dir.resolve(PHOTOS_FOLDER).resolve(stamp + "_" + m.name());
        return dir.resolve(stamp + "_" + m.name().replaceFirst("\\.[^.]+$", "") + ".mp4");
    }

    /** Mueve la foto de la carpeta temporal al destino, con la fecha de la cámara. */
    boolean saveFile(File source, Media m, Path target) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source.toPath(), part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setLastModified(m.modified());
            log("OK  " + m.name() + "  ->  " + outputRoot.relativize(target));
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            log("ERROR al guardar " + m.name() + ": " + e.getMessage());
            return false;
        }
    }

    boolean convert(File clip, String name, Path target) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            // Video progresivo: se mantiene el tamaño (en pares) y el Motion JPEG, que viene en
            // rango completo, se pasa al rango de video normal.
            String vf = "scale=trunc(iw/2)*2:trunc(ih/2)*2:out_range=tv,setsar=1";
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    ffmpeg, "-hide_banner", "-y", "-nostdin",
                    "-i", clip.getAbsolutePath(),
                    "-vf", vf));
            cmd.addAll(videoEncoder);
            cmd.addAll(Arrays.asList(
                    "-c:a", "aac", "-b:a", "192k",
                    "-movflags", "+faststart",
                    "-progress", "pipe:1", "-nostats",
                    "-f", "mp4", part.toString()));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            double durationUs = 0;
            List<String> tail = new LinkedList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    tail.add(line);
                    if (tail.size() > 15) tail.remove(0);
                    Matcher d = DURATION.matcher(line);
                    if (durationUs == 0 && d.find()) {
                        durationUs = (Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60
                                + Double.parseDouble(d.group(3))) * 1_000_000;
                    }
                    Matcher t = OUT_TIME.matcher(line);
                    if (durationUs > 0 && t.find()) {
                        setProgress((int) Math.min(1000, Long.parseLong(t.group(1)) * 1000 / durationUs));
                    }
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                Files.deleteIfExists(part);
                log("ERROR al convertir " + name + ":");
                tail.forEach(l -> log("   " + l));
                return false;
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            log("OK  " + name + "  ->  " + outputRoot.relativize(target));
            return true;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            log("ERROR al convertir " + name + ": " + e.getMessage());
            return false;
        }
    }

    // --------------------------------------------------------------------- PTP

    /**
     * Lee y copia archivos de la cámara con el Shell de Windows.
     * Modo list: una línea "F<TAB>ruta<TAB>fecha UTC<TAB>tamaño" por archivo.
     * Modo copy: copia cada ruta de ListFile a Dest\<n>\ y escribe "OK<TAB>n" o "ERR<TAB>n<TAB>motivo".
     */
    static final String PTP_SCRIPT = """
            param([string]$Mode, [string]$Match, [string]$Dest, [string]$ListFile)
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            $shell = New-Object -ComObject Shell.Application
            $device = @($shell.NameSpace(17).Items()) | Where-Object { $_.Name -like "*$Match*" } | Select-Object -First 1
            if (-not $device) { 'NODEV'; exit 2 }

            function Get-Name($item) {
                $n = $null
                try { $n = $item.ExtendedProperty('System.FileName') } catch {}
                if ([string]::IsNullOrEmpty($n)) { $n = $item.Name }
                return $n
            }

            # Por PTP, ModifyDate y Size vienen vacios: se usan las propiedades del sistema.
            function Get-Size($item) {
                $s = $null
                try { $s = $item.ExtendedProperty('System.Size') } catch {}
                if ($s) { return [long]$s }
                return [long]$item.Size
            }

            # Fecha en UTC (asi la entrega ExtendedProperty).
            function Get-Date-Utc($item) {
                foreach ($p in 'System.Photo.DateTaken', 'System.DateModified', 'System.DateCreated') {
                    $v = $null
                    try { $v = $item.ExtendedProperty($p) } catch {}
                    if ($v) { return ([datetime]$v).ToString('yyyy-MM-ddTHH:mm:ss') }
                }
                return ''
            }

            $files = [ordered]@{}
            function Walk($folder, $rel) {
                foreach ($item in @($folder.Items())) {
                    $name = Get-Name $item
                    $path = if ($rel) { "$rel/$name" } else { $name }
                    if ($item.IsFolder) { Walk $item.GetFolder $path }
                    else { $script:files[$path] = $item }
                }
            }
            Walk $device.GetFolder ''

            if ($Mode -eq 'list') {
                foreach ($path in $files.Keys) {
                    $item = $files[$path]
                    "F`t$path`t$(Get-Date-Utc $item)`t$(Get-Size $item)"
                }
                exit 0
            }

            $paths = @(Get-Content -LiteralPath $ListFile -Encoding UTF8)
            for ($i = 0; $i -lt $paths.Count; $i++) {
                try {
                    $item = $files[$paths[$i]]
                    if (-not $item) { throw 'not found on the camera' }
                    $dir = Join-Path $Dest $i
                    New-Item -ItemType Directory -Force -Path $dir | Out-Null
                    # 4 sin ventana de progreso, 16 si a todo, 512 sin confirmar carpetas, 1024 sin ventana de error
                    $shell.NameSpace($dir).CopyHere($item, 4 + 16 + 512 + 1024)
                    $expected = Get-Size $item
                    $deadline = (Get-Date).AddSeconds(60 + $expected / 1MB * 5)
                    $last = -1
                    while ($true) {
                        if ((Get-Date) -gt $deadline) { throw 'timeout' }
                        Start-Sleep -Milliseconds 300
                        $file = Get-ChildItem -LiteralPath $dir -File | Select-Object -First 1
                        if (-not $file) { continue }
                        try { $fs = [IO.File]::Open($file.FullName, 'Open', 'Read', 'None'); $len = $fs.Length; $fs.Close() } catch { continue }
                        if ($expected -gt 0 -and $len -ge $expected) { break }
                        if ($expected -le 0 -and $len -gt 0 -and $len -eq $last) { break }
                        $last = $len
                    }
                    "OK`t$i"
                } catch {
                    "ERR`t$i`t$($_.Exception.Message)"
                }
            }
            """;

    /** Fotos y videos de la carpeta DCIM de la cámara, o null si no se pudo leer. */
    List<Media> listCamera() {
        setStatus("Leyendo la cámara...");
        List<Media> media = new ArrayList<>();
        boolean[] noDevice = {false};
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        int exit = runPtp(List.of("list", SHELL_NAME), line -> {
            if (line.trim().equals("NODEV")) noDevice[0] = true;
            String[] f = line.split("\t");
            if (f.length < 3 || !f[0].equals("F")) return;
            String path = f[1];
            String name = path.substring(path.lastIndexOf('/') + 1);
            Kind kind = kindOf(name);
            if (kind == null || !path.toUpperCase().contains("DCIM/")) return;
            long modified;
            try {
                modified = iso.parse(f[2]).getTime();
            } catch (ParseException e) {
                modified = System.currentTimeMillis();
            }
            media.add(new Media(path, name, modified, kind));
        });
        if (noDevice[0]) {
            log("ERROR: Windows no muestra la cámara en \"Este equipo\".");
            return null;
        }
        if (exit != 0) {
            log("ERROR: no se pudo leer la cámara (código " + exit + ").");
            return null;
        }
        media.sort(Comparator.comparing(Media::path));
        return media;
    }

    /** Copia los archivos de la cámara a una carpeta temporal. Devuelve los que llegaron. */
    Map<Media, File> fetch(List<Media> items, Path staging) throws IOException {
        Path list = staging.resolve("lista.txt");
        Files.write(list, items.stream().map(Media::path).toList(), StandardCharsets.UTF_8);
        Path files = Files.createDirectories(staging.resolve("archivos"));
        Map<Media, File> local = new HashMap<>();
        int[] count = {0};
        setStatus("Copiando de la cámara 0 de " + items.size());
        runPtp(List.of("copy", SHELL_NAME, files.toString(), list.toString()), line -> {
            String[] f = line.split("\t", 3);
            if (f.length < 2 || !f[1].matches("\\d+")) return;
            int i = Integer.parseInt(f[1]);
            if (i >= items.size()) return;
            Media m = items.get(i);
            if (f[0].equals("OK")) {
                File[] got = files.resolve(f[1]).toFile().listFiles(File::isFile);
                if (got != null && got.length > 0) local.put(m, got[0]);
            } else if (f[0].equals("ERR")) {
                log("ERROR al copiar " + m.name() + " de la cámara: " + (f.length > 2 ? f[2] : ""));
            }
            count[0]++;
            setStatus("Copiando de la cámara " + count[0] + " de " + items.size());
            setProgress(count[0] * 1000 / items.size());
        });
        return local;
    }

    int runPtp(List<String> args, Consumer<String> onLine) {
        try {
            if (ptpScript == null) {
                ptpScript = Files.createTempFile("kodak-ptp", ".ps1");
                ptpScript.toFile().deleteOnExit();
                Files.writeString(ptpScript, PTP_SCRIPT, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log("ERROR: " + e.getMessage());
            return -1;
        }
        List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-Sta",
                "-ExecutionPolicy", "Bypass", "-File", ptpScript.toString()));
        cmd.addAll(args);
        return runLines(cmd, onLine);
    }

    // ------------------------------------------------------------------ helpers

    /** Ejecuta un comando y pasa cada línea de su salida a onLine. Devuelve el código de salida. */
    static int runLines(List<String> cmd, Consumer<String> onLine) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) onLine.accept(line);
            }
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    /** Usa Intel Quick Sync si la PC lo tiene (mucho más rápido); si no, el procesador. */
    static List<String> pickEncoder(String ffmpeg) {
        List<String> qsv = Arrays.asList("-c:v", "h264_qsv", "-preset", "slower", "-global_quality", String.valueOf(QUALITY),
                "-profile:v", "high", "-pix_fmt", "nv12");
        List<String> cmd = new ArrayList<>(Arrays.asList(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=black:s=640x480:d=0.2"));
        cmd.addAll(qsv);
        cmd.addAll(Arrays.asList("-f", "null", "-"));
        if (runLines(cmd, line -> {
        }) == 0) return qsv;
        return Arrays.asList("-c:v", "libx264", "-preset", "faster", "-crf", String.valueOf(QUALITY),
                "-profile:v", "high", "-pix_fmt", "yuv420p");
    }

    /** Busca ffmpeg.exe junto al programa y, si no, en el PATH. */
    static String findFfmpeg() {
        List<File> dirs = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) dirs.add(new File(appPath).getParentFile());
        try {
            File jar = new File(KodakConverter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            dirs.add(jar.getParentFile());
            if (jar.getParentFile() != null) dirs.add(jar.getParentFile().getParentFile());
        } catch (Exception ignored) {
        }
        for (File dir : dirs) {
            if (dir == null) continue;
            File f = new File(dir, "ffmpeg.exe");
            if (f.isFile()) return f.getAbsolutePath();
        }
        if (runLines(List.of("ffmpeg", "-version"), line -> {
        }) == 0) return "ffmpeg";
        return null;
    }

    static Path desktop() {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "[Environment]::GetFolderPath('Desktop')").start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!out.isEmpty() && Files.isDirectory(Paths.get(out))) return Paths.get(out);
        } catch (Exception ignored) {
        }
        return Paths.get(System.getProperty("user.home"), "Desktop");
    }

    void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + msg + "\n";
        SwingUtilities.invokeLater(() -> {
            log.append(line);
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> status.setText(msg));
    }

    void setBadge(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            badge.setText(text);
            badge.setBackground(color);
        });
    }

    /** permil de 0 a 1000; -1 limpia la barra. */
    void setProgress(int permil) {
        SwingUtilities.invokeLater(() -> {
            progress.setValue(Math.max(permil, 0));
            progress.setString(permil < 0 ? "" : (permil / 10) + "%");
        });
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
