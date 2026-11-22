import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Silent self-updater backed by GitHub Releases — no installer, no prompts.
 *
 * End-to-end flow:
 *   1. backgroundCheck() queries the repo's latest release shortly after
 *      launch and again every 6 h (anonymous — the repo is public).
 *   2. If the release tag is a newer dotted version than AppVersion.VERSION
 *      and we are running from a jar, the release's StrikeScope-*.jar asset
 *      is downloaded into <app dir>/updates/ and validated as a real jar.
 *   3. A running jar cannot replace itself on disk, so a tiny generated
 *      helper script waits for this JVM to exit, moves the new jar over the
 *      old one and relaunches the app. Updates found shortly after launch
 *      are applied immediately (one invisible restart); later in a session
 *      they are staged in updates/pending-install.txt and applied at the
 *      next start, before the UI comes up.
 *
 * Every failure is logged to strikescope-update.log and swallowed — the app
 * always keeps working, with or without network.
 */
final class UpdateManager {

    /** GitHub repo the app and its releases live in. */
    static final String REPO = "leekayden/strikescope";

    private static final String LATEST_API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long RECHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    /** Updates found this soon after launch restart the app right away. */
    private static final long AUTO_APPLY_WINDOW_MS = 90_000;
    private static final long STARTED = System.currentTimeMillis();

    private static final Path UPDATES_DIR = updatesDir();
    private static final Path PENDING = UPDATES_DIR.resolve("pending-install.txt");

    /** Set while a long task (import, backtest) runs — defers auto-restart. */
    static volatile boolean appBusy;

    /** Where update progress lines go; MainFrame wires this to its status bar. */
    static Consumer<String> statusSink = msg -> { };

    private record Info(String tag, String url, String fileName) { }

    private UpdateManager() { }

    /** Called once at startup: applies any staged swap, then polls releases. */
    static void backgroundCheck() {
        Thread t = new Thread(() -> {
            check();
            while (true) {
                try {
                    Thread.sleep(RECHECK_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                check();
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Called before any UI: if an update was staged earlier, swap + relaunch. */
    static boolean applyPendingBeforeUi() {
        try {
            if (!Files.exists(PENDING)) {
                return false;
            }
            String fileName = Files.readString(PENDING).trim();
            deletePending();
            Path jar = runningJar();
            Path newJar = UPDATES_DIR.resolve(fileName);
            if (jar == null || !Files.exists(newJar)
                    || compareSemver(fileName, AppVersion.VERSION) <= 0) {
                log("staged update " + fileName + " not applied (stale or dev run)");
                return false;
            }
            restartWith(newJar);            // never returns on success
            return true;
        } catch (Exception e) {
            log("pending apply failed: " + e);
            return false;
        }
    }

    private static void check() {
        try {
            Info info = fetchLatest();
            if (info == null || compareSemver(info.tag(), AppVersion.VERSION) <= 0) {
                return;                     // no release, or current/older: stay silent
            }
            if (runningJar() == null) {
                status("update " + info.tag() + " available (dev build — not applied)");
                return;
            }
            Path newJar = download(info);
            boolean canRestartNow = !appBusy
                    && System.currentTimeMillis() - STARTED < AUTO_APPLY_WINDOW_MS;
            if (canRestartNow) {
                try {
                    restartWith(newJar);
                } catch (Exception e) {
                    log("swap failed: " + e);
                }
            }
            if (!canRestartNow) {
                writePending(info.fileName());
                status(info.tag() + " downloaded — installed at next start");
            }
        } catch (Exception e) {
            log("update check failed: " + e);
        }
    }

    /** Latest release from GitHub (anonymous); null when none / no jar asset. */
    static Info fetchLatest() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_API))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "StrikeScope-updater")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log("release check: HTTP " + resp.statusCode());
            return null;
        }
        String body = resp.body();
        String tag = stringField(body, "tag_name");
        if (tag == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        while (m.find()) {
            String url = m.group(1);
            String name = url.substring(url.lastIndexOf('/') + 1).toLowerCase();
            if (name.startsWith("strikescope-") && name.endsWith(".jar")) {
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                log("latest release " + tag + " -> " + fileName);
                return new Info(tag, url, fileName);
            }
        }
        log("release " + tag + " has no StrikeScope-*.jar asset");
        return null;
    }

    /** Downloads the release asset into updates/ and validates it is a jar. */
    private static Path download(Info info) throws Exception {
        Files.createDirectories(UPDATES_DIR);
        Path tmp = UPDATES_DIR.resolve(info.fileName() + ".part");
        status("downloading update " + info.tag() + "…");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(info.url()))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "StrikeScope-updater")
                .GET()
                .build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("asset download: HTTP " + resp.statusCode());
        }
        try (JarFile jar = new JarFile(tmp.toFile())) {
            jar.getManifest();          // throws when the file is truncated / not a jar
        }
        Path dst = UPDATES_DIR.resolve(info.fileName());
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        log("downloaded " + info.fileName() + " (" + Files.size(dst) + " bytes)");
        return dst;
    }

    /**
     * Hands the swap to a detached helper script and exits; the helper waits
     * for this JVM to release the jar, moves the new one over and relaunches.
     */
    private static void restartWith(Path newJar) throws Exception {
        Path jar = runningJar();
        if (jar == null) {
            return;
        }
        Files.createDirectories(UPDATES_DIR);
        Path script = writeSwapScript(newJar.toAbsolutePath(), jar.toAbsolutePath());
        deletePending();
        log("applying update: restarting into " + newJar.getFileName());
        String newName = newJar.getFileName().toString()
                .replaceFirst("(?i)^strikescope-", "v").replaceFirst("(?i)\\.jar$", "");
        status("installing update — restarting as " + newName + "…");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.toString()).start();
        } else {
            new ProcessBuilder("/bin/sh", "-c",
                    "nohup sh '" + script + "' >/dev/null 2>&1 &").start();
        }
        System.exit(0);
    }

    /** apply-update script: wait for app exit, swap jar, relaunch, self-delete. */
    private static Path writeSwapScript(Path newJar, Path targetJar) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = UPDATES_DIR.resolve(windows ? "apply-update.bat" : "apply-update.sh");
        String t = targetJar.toString();
        String n = newJar.toString();
        if (windows) {
            String nl = "\r\n";         // CRLF: batch labels misparse with LF-only files
            Files.writeString(script,
                    "@echo off" + nl
                    + "rem generated by StrikeScope's updater — swaps in the downloaded update" + nl
                    + "set \"TARGET=" + t + "\"" + nl
                    + "set \"NEW=" + n + "\"" + nl
                    + "set /a N=0" + nl
                    + ":retry" + nl
                    + "move /y \"%NEW%\" \"%TARGET%\" >nul 2>nul && goto launch" + nl
                    + "set /a N+=1" + nl
                    + "if %N% GEQ 30 exit /b 1" + nl
                    + "ping -n 2 127.0.0.1 >nul" + nl
                    + "goto retry" + nl
                    + ":launch" + nl
                    + "where javaw >nul 2>nul"
                    + " && (start \"\" javaw -jar \"%TARGET%\")"
                    + " || (start \"\" java -jar \"%TARGET%\")" + nl
                    + "del \"%~f0\" >nul 2>nul" + nl);
        } else {
            Files.writeString(script,
                    "#!/bin/sh\n"
                    + "# generated by StrikeScope's updater — swaps in the downloaded update\n"
                    + "n=0\n"
                    + "while ! mv -f '" + n + "' '" + t + "'; do\n"
                    + "  n=$((n+1)); [ \"$n\" -ge 30 ] && exit 1\n"
                    + "  sleep 1\n"
                    + "done\n"
                    + "(java -jar '" + t + "' >/dev/null 2>&1 &)\n"
                    + "rm -f \"$0\"\n");
        }
        return script;
    }

    private static void writePending(String fileName) {
        try {
            Files.createDirectories(UPDATES_DIR);
            Files.writeString(PENDING, fileName);
        } catch (Exception e) {
            log("could not stage update: " + e);
        }
    }

    private static void deletePending() {
        try {
            Files.deleteIfExists(PENDING);
        } catch (Exception ignore) { }
    }

    private static void status(String msg) {
        log(msg);
        statusSink.accept(msg);
    }

    private static void log(String msg) {
        try {
            Files.writeString(Path.of("strikescope-update.log"),
                    LocalDateTime.now() + "  " + msg + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) { }
    }

    /** Extracts a JSON string field ("key": "value") without a JSON library. */
    private static String stringField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    /** -1/0/+1 for dotted numeric versions ("v0.0.2" vs "0.0.10"). */
    static int compareSemver(String a, String b) {
        long[] pa = numericParts(a);
        long[] pb = numericParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return pa[i] < pb[i] ? -1 : 1;
            }
        }
        return 0;
    }

    /** First three digit groups of a version-ish string: "v2.10-beta" -> 2 10 0. */
    private static long[] numericParts(String v) {
        long[] r = new long[3];
        if (v == null) {
            return r;
        }
        Matcher m = Pattern.compile("(\\d+)").matcher(v.replaceFirst("^[vV]", ""));
        for (int i = 0; i < 3 && m.find(); i++) {
            r[i] = Long.parseLong(m.group(1));
        }
        return r;
    }

    /** The jar this class was loaded from, or null in dev mode (classes dir). */
    static Path runningJar() {
        try {
            Path p = Path.of(UpdateManager.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            return p.toString().toLowerCase().endsWith(".jar") ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Download/staging dir, next to the running jar (cwd in dev mode). */
    private static Path updatesDir() {
        Path jar = runningJar();
        return (jar != null ? jar.getParent() : Path.of(".")).resolve("updates");
    }
}
