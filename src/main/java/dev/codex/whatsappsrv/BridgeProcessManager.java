package dev.codex.whatsappsrv;

import com.google.gson.Gson;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class BridgeProcessManager {
    private final WhatsAppSRVPlugin plugin;
    private volatile Process process;
    private volatile String state = "stopped";

    BridgeProcessManager(WhatsAppSRVPlugin plugin) {
        this.plugin = plugin;
    }

    String state() {
        return state;
    }

    synchronized void start() {
        if (process != null && process.isAlive()) return;
        try {
            state = "preparing runtime";
            File bridgeDirectory = new File(plugin.getDataFolder(), "bridge");
            if (!bridgeDirectory.isDirectory() && !bridgeDirectory.mkdirs()) {
                throw new IOException("Could not create " + bridgeDirectory);
            }
            extractResource("embedded-bridge/index.js", new File(bridgeDirectory, "index.js"));
            extractResource("embedded-bridge/package.json", new File(bridgeDirectory, "package.json"));
            extractResource("embedded-bridge/package-lock.json", new File(bridgeDirectory, "package-lock.json"));
            extractResource("embedded-bridge/.puppeteerrc.cjs", new File(bridgeDirectory, ".puppeteerrc.cjs"));
            writeBridgeConfig(bridgeDirectory);

            File node = resolveNodeRuntime();
            verifyNodeRuntime(node);
            ensureDependencies(node, bridgeDirectory);

            state = "starting";
            ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), "index.js");
            builder.directory(bridgeDirectory);
            addNodeToPath(builder, node);
            setBrowserWorkDirectory(builder);
            builder.environment().put("PUPPETEER_CACHE_DIR", new File(plugin.getDataFolder(), "browser-cache").getAbsolutePath());
            process = builder.start();
            pipe(process.getInputStream(), false);
            pipe(process.getErrorStream(), true);
            state = "running";
            plugin.getLogger().info("Embedded WhatsApp bridge started. Waiting for login/QR...");
        } catch (Exception error) {
            state = "failed: " + error.getMessage();
            plugin.getLogger().log(Level.SEVERE, "Could not start the embedded WhatsApp bridge", error);
        }
    }

    synchronized void stop() {
        Process current = process;
        process = null;
        if (current == null) return;
        current.destroy();
        try {
            if (!current.waitFor(8, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        state = "stopped";
    }

    private void writeBridgeConfig(File bridgeDirectory) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("listenHost", "127.0.0.1");
        config.put("listenPort", bridgePort());
        config.put("apiToken", plugin.getConfig().getString("api-token"));
        config.put("targetChatId", plugin.getConfig().getString("target-chat-id", ""));
        boolean receiveFromTarget = plugin.getConfig().getBoolean("forward.whatsapp-to-minecraft", true)
                || plugin.getConfig().getBoolean("whatsapp-commands.enabled", true);
        config.put("receiveFromTarget", receiveFromTarget);
        config.put("sessionPath", new File(plugin.getDataFolder(), "session").getAbsolutePath());
        config.put("maxQueuedMessages", 500);
        File destination = new File(bridgeDirectory, "config.json");
        try (OutputStream output = new FileOutputStream(destination)) {
            output.write(new Gson().toJson(config).getBytes(StandardCharsets.UTF_8));
        }
    }

    private int bridgePort() {
        String value = plugin.getConfig().getString("bridge-url", "http://127.0.0.1:3210");
        try {
            return new URL(value).getPort() > 0 ? new URL(value).getPort() : 3210;
        } catch (Exception ignored) {
            return 3210;
        }
    }

    private File resolveNodeRuntime() throws IOException, InterruptedException {
        String configured = plugin.getConfig().getString("runtime.node-executable", "auto");
        if (configured != null && !configured.equalsIgnoreCase("auto")) {
            File file = new File(configured);
            if (!file.isFile()) throw new IOException("Configured Node executable does not exist: " + file);
            return file;
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            throw new IOException("Automatic runtime currently supports Linux Pterodactyl hosts only");
        }
        String machine = System.getProperty("os.arch", "").toLowerCase();
        String architecture;
        if (machine.contains("aarch64") || machine.contains("arm64")) {
            architecture = "arm64";
        } else if (machine.contains("amd64") || machine.contains("x86_64") || machine.equals("x64")) {
            architecture = "x64";
        } else {
            throw new IOException("Unsupported Linux architecture: " + machine + " (expected x64 or ARM64)");
        }
        String version = plugin.getConfig().getString("runtime.node-version", "24.15.0");
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Invalid Node version");
        if (Integer.parseInt(version.substring(0, version.indexOf('.'))) < 24) {
            throw new IOException("WhatsAppSRV requires Node.js 24 or newer");
        }

        File runtimeDirectory = new File(plugin.getDataFolder(), "runtime");
        File nodeHome = new File(runtimeDirectory, "node-v" + version + "-linux-" + architecture);
        File node = new File(nodeHome, "bin/node");
        if (node.isFile()) return node;
        if (!runtimeDirectory.isDirectory() && !runtimeDirectory.mkdirs()) throw new IOException("Could not create runtime directory");

        File archive = new File(runtimeDirectory, "node-v" + version + "-linux-" + architecture + ".tar.xz");
        String address = "https://nodejs.org/dist/v" + version + "/" + archive.getName();
        plugin.getLogger().info("Downloading private Node.js runtime from " + address);
        download(address, archive);
        verifyNodeChecksum(version, archive);
        unpackTarXz(archive, runtimeDirectory);
        if (!node.isFile()) throw new IOException("Node runtime archive did not contain the expected executable");
        node.setExecutable(true, true);
        return node;
    }

    private void verifyNodeRuntime(File node) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), "--version");
        builder.redirectErrorStream(true);
        Process check = builder.start();
        if (!check.waitFor(10, TimeUnit.SECONDS)) {
            check.destroyForcibly();
            throw new IOException("Node.js version check timed out");
        }
        String version;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(check.getInputStream(), StandardCharsets.UTF_8))) {
            version = reader.readLine();
        }
        if (check.exitValue() != 0 || version == null || !version.matches("v[0-9]+\\..*")) {
            throw new IOException("Configured Node executable did not return a valid version");
        }
        int major = Integer.parseInt(version.substring(1, version.indexOf('.')));
        if (major < 24) throw new IOException("WhatsAppSRV requires Node.js 24 or newer (found " + version + ")");
    }

    private void ensureDependencies(File node, File bridgeDirectory) throws IOException, InterruptedException {
        File packageFile = new File(bridgeDirectory, "node_modules/whatsapp-web.js/package.json");
        File puppeteerPackage = new File(bridgeDirectory, "node_modules/puppeteer/package.json");
        File chromiumPackage = new File(bridgeDirectory, "node_modules/@sparticuz/chromium-min/package.json");
        // Version this marker whenever the embedded dependency lock changes so
        // an upgraded JAR cannot silently keep stale/vulnerable node_modules.
        File completionMarker = new File(bridgeDirectory, ".install-complete-1.0.0-puppeteer25-chromium149");
        if (completionMarker.isFile() && packageFile.isFile()
                && puppeteerPackage.isFile() && chromiumPackage.isFile()) return;
        state = "installing whatsapp-web.js and Chromium";
        plugin.getLogger().info("First start: installing whatsapp-web.js and Chromium. This can take several minutes...");
        File npmCli = new File(node.getParentFile().getParentFile(), "lib/node_modules/npm/bin/npm-cli.js");
        if (!npmCli.isFile()) throw new IOException("npm was not found in the downloaded Node runtime");
        // RemoteAuth-only optional packages are unnecessary: WhatsAppSRV uses
        // LocalAuth. Omitting them reduces install size and attack surface.
        ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), npmCli.getAbsolutePath(),
                "ci", "--omit=dev", "--omit=optional", "--no-audit", "--no-fund");
        builder.directory(bridgeDirectory);
        addNodeToPath(builder, node);
        setBrowserWorkDirectory(builder);
        builder.environment().put("PUPPETEER_CACHE_DIR", new File(plugin.getDataFolder(), "browser-cache").getAbsolutePath());
        String machine = System.getProperty("os.arch", "").toLowerCase();
        if (machine.contains("aarch64") || machine.contains("arm64")) {
            builder.environment().put("PUPPETEER_SKIP_DOWNLOAD", "true");
        } else {
            // The bridge launches full managed Chrome on x64; downloading a
            // second chrome-headless-shell copy only wastes container space.
            builder.environment().put("PUPPETEER_SKIP_CHROME_HEADLESS_SHELL_DOWNLOAD", "true");
        }
        Process installer = builder.start();
        pipe(installer.getInputStream(), false);
        pipe(installer.getErrorStream(), true);
        long minutes = Math.max(2, plugin.getConfig().getLong("runtime.install-timeout-minutes", 15));
        if (!installer.waitFor(minutes, TimeUnit.MINUTES)) {
            installer.destroyForcibly();
            throw new IOException("npm install timed out after " + minutes + " minutes");
        }
        if (installer.exitValue() != 0 || !packageFile.isFile()
                || !puppeteerPackage.isFile() || !chromiumPackage.isFile()) {
            throw new IOException("npm install failed with exit code " + installer.exitValue());
        }
        if (!completionMarker.createNewFile() && !completionMarker.isFile()) throw new IOException("Could not create dependency installation marker");
    }

    private void addNodeToPath(ProcessBuilder builder, File node) {
        Map<String, String> environment = builder.environment();
        String pathKey = environment.containsKey("PATH") ? "PATH" : "Path";
        String existing = environment.get(pathKey);
        environment.put(pathKey, node.getParentFile().getAbsolutePath() + File.pathSeparator + (existing == null ? "" : existing));
    }

    private void setBrowserWorkDirectory(ProcessBuilder builder) throws IOException {
        File workDirectory = new File(plugin.getDataFolder(), "browser-work-v149-paired");
        if (!workDirectory.isDirectory() && !workDirectory.mkdirs()) {
            throw new IOException("Could not create browser work directory " + workDirectory);
        }
        String path = workDirectory.getAbsolutePath();
        builder.environment().put("TMPDIR", path);
        builder.environment().put("TMP", path);
        builder.environment().put("TEMP", path);
        String machine = System.getProperty("os.arch", "").toLowerCase();
        if (machine.contains("aarch64") || machine.contains("arm64")) {
            // Makes @sparticuz/chromium-min unpack its bundled AL2023 shared
            // libraries (including libnspr4.so) and add them to LD_LIBRARY_PATH.
            builder.environment().put("AWS_EXECUTION_ENV", "AWS_Lambda_nodejs24.x");
        }
    }

    private void extractResource(String resource, File destination) throws IOException {
        InputStream source = plugin.getResource(resource);
        if (source == null) throw new IOException("Missing embedded resource " + resource);
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (InputStream input = source) {
            Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void download(String address, File destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "WhatsAppSRV/1.0");
        if (connection.getResponseCode() / 100 != 2) throw new IOException("Download returned HTTP " + connection.getResponseCode());
        try (InputStream input = new BufferedInputStream(connection.getInputStream()); OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
    }

    private void verifyNodeChecksum(String version, File archive) throws IOException {
        String checksums = readUrl("https://nodejs.org/dist/v" + version + "/SHASUMS256.txt");
        String expected = null;
        for (String line : checksums.split("\\r?\\n")) {
            if (line.endsWith("  " + archive.getName())) {
                expected = line.substring(0, line.indexOf(' '));
                break;
            }
        }
        if (expected == null) throw new IOException("Node checksum list did not contain " + archive.getName());
        String actual = sha256(archive);
        if (!expected.equalsIgnoreCase(actual)) throw new IOException("Downloaded Node runtime failed SHA-256 verification");
        plugin.getLogger().info("Verified downloaded Node.js runtime SHA-256.");
    }

    private String readUrl(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "WhatsAppSRV/1.0");
        if (connection.getResponseCode() / 100 != 2) throw new IOException("Checksum request returned HTTP " + connection.getResponseCode());
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append('\n');
        }
        return content.toString();
    }

    private String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private void unpackTarXz(File archive, File destinationRoot) throws IOException {
        String rootPath = destinationRoot.getCanonicalPath() + File.separator;
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             XZCompressorInputStream xz = new XZCompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = tar.getNextTarEntry()) != null) {
                File destination = new File(destinationRoot, entry.getName());
                if (!destination.getCanonicalPath().startsWith(rootPath)) throw new IOException("Unsafe path in Node archive");
                if (entry.isDirectory()) {
                    if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Could not create " + destination);
                    continue;
                }
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
                try (OutputStream output = new FileOutputStream(destination)) {
                    int read;
                    while ((read = tar.read(buffer)) >= 0) output.write(buffer, 0, read);
                }
                if ((entry.getMode() & 0100) != 0) destination.setExecutable(true, true);
            }
        }
    }

    private void pipe(InputStream stream, boolean warning) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (warning) plugin.getLogger().warning("[bridge] " + line);
                    else plugin.getLogger().info("[bridge] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "WhatsAppSRV-bridge-output");
        thread.setDaemon(true);
        thread.start();
    }
}
