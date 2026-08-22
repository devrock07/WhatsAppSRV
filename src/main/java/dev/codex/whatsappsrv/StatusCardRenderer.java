package dev.codex.whatsappsrv;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Renders a self-contained WhatsApp-friendly Minecraft server status card.
 *
 * <p>The renderer uses only Java AWT and is safe to call from an asynchronous
 * task once the Bukkit state represented by {@link Snapshot} has been captured
 * on the server thread.</p>
 */
public final class StatusCardRenderer {
    public static final int WIDTH = 960;
    public static final int HEIGHT = 540;

    private static final Color BACKGROUND_TOP = new Color(7, 18, 24);
    private static final Color BACKGROUND_BOTTOM = new Color(12, 30, 35);
    private static final Color PANEL = new Color(17, 35, 42);
    private static final Color PANEL_LIGHT = new Color(24, 46, 53);
    private static final Color ACCENT = new Color(37, 211, 102);
    private static final Color TEXT = new Color(239, 247, 244);
    private static final Color MUTED = new Color(157, 177, 176);
    private static final Color WARNING = new Color(255, 184, 77);
    private static final Color DANGER = new Color(255, 93, 93);

    private StatusCardRenderer() {
    }

    /**
     * Renders the supplied snapshot as a PNG byte array.
     *
     * @param snapshot already-captured server state
     * @return encoded PNG bytes
     * @throws IOException if the JVM cannot encode PNG images
     */
    public static byte[] renderPng(Snapshot snapshot) throws IOException {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot cannot be null");
        }

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configureGraphics(graphics);
            paintBackground(graphics);
            paintHeader(graphics, snapshot);
            paintMetrics(graphics, snapshot);
            paintPlayers(graphics, snapshot);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG image writer is available");
        }
        return output.toByteArray();
    }

    private static void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    private static void paintBackground(Graphics2D graphics) {
        graphics.setPaint(new GradientPaint(0, 0, BACKGROUND_TOP, WIDTH, HEIGHT, BACKGROUND_BOTTOM));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);

        Composite originalComposite = graphics.getComposite();
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.07f));
        graphics.setColor(ACCENT);
        graphics.fillOval(710, -205, 430, 430);
        graphics.fillOval(-180, 370, 360, 360);
        graphics.setComposite(originalComposite);

        // A restrained block pattern gives the card a Minecraft feel without
        // competing with the data.
        graphics.setColor(new Color(255, 255, 255, 7));
        for (int row = 0; row < 7; row++) {
            for (int column = 0; column < 12; column++) {
                if ((row + column) % 3 == 0) {
                    graphics.fillRect(column * 84 - 18, row * 84 - 24, 42, 42);
                }
            }
        }
    }

    private static void paintHeader(Graphics2D graphics, Snapshot snapshot) {
        final int x = 32;
        final int y = 28;
        final int width = WIDTH - 64;
        final int height = 102;
        fillRoundRect(graphics, x, y, width, height, 24, PANEL);

        graphics.setColor(new Color(37, 211, 102, 20));
        graphics.fill(new RoundRectangle2D.Double(x, y, 9, height, 9, 9));

        drawServerIcon(graphics, snapshot.serverIcon, 50, 43, 72);

        graphics.setFont(font(Font.BOLD, 28));
        graphics.setColor(TEXT);
        drawEllipsized(graphics, defaultText(snapshot.serverName, "Minecraft Server"), 140, 68, 555);

        graphics.setFont(font(Font.PLAIN, 16));
        graphics.setColor(MUTED);
        drawEllipsized(graphics, stripControlCharacters(defaultText(snapshot.motd, "Server status")), 140, 97, 610);

        drawOnlineBadge(graphics, 754, 51);

        graphics.setFont(font(Font.PLAIN, 13));
        graphics.setColor(MUTED);
        drawEllipsizedRightAligned(
                graphics,
                defaultText(snapshot.serverVersion, "Minecraft"),
                900,
                105,
                175
        );
    }

    private static void drawServerIcon(Graphics2D graphics, BufferedImage icon, int x, int y, int size) {
        fillRoundRect(graphics, x, y, size, size, 18, new Color(10, 25, 30));
        Shape oldClip = graphics.getClip();
        graphics.clip(new RoundRectangle2D.Double(x + 4, y + 4, size - 8, size - 8, 13, 13));

        if (icon != null && icon.getWidth() > 0 && icon.getHeight() > 0) {
            Object oldInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            drawCoverImage(graphics, icon, x + 4, y + 4, size - 8, size - 8);
            if (oldInterpolation != null) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
            }
        } else {
            paintFallbackServerIcon(graphics, x + 4, y + 4, size - 8);
        }

        graphics.setClip(oldClip);
        graphics.setStroke(new BasicStroke(2f));
        graphics.setColor(new Color(37, 211, 102, 105));
        graphics.draw(new RoundRectangle2D.Double(x + 1, y + 1, size - 2, size - 2, 18, 18));
    }

    private static void paintFallbackServerIcon(Graphics2D graphics, int x, int y, int size) {
        graphics.setColor(new Color(78, 131, 53));
        graphics.fillRect(x, y, size, size / 3);
        graphics.setColor(new Color(113, 78, 47));
        graphics.fillRect(x, y + size / 3, size, size - size / 3);
        graphics.setColor(new Color(91, 63, 39));
        graphics.fillRect(x + size / 8, y + size / 2, size / 4, size / 5);
        graphics.fillRect(x + size * 5 / 8, y + size * 2 / 3, size / 4, size / 5);
        graphics.setColor(new Color(126, 163, 66));
        graphics.fillRect(x, y + size / 4, size, size / 8);
    }

    private static void drawOnlineBadge(Graphics2D graphics, int x, int y) {
        fillRoundRect(graphics, x, y, 146, 34, 17, new Color(37, 211, 102, 28));
        graphics.setColor(ACCENT);
        graphics.fillOval(x + 15, y + 12, 10, 10);
        graphics.setFont(font(Font.BOLD, 14));
        graphics.drawString("SERVER ONLINE", x + 34, y + 22);
    }

    private static void paintMetrics(Graphics2D graphics, Snapshot snapshot) {
        final int y = 148;
        final int width = 213;
        final int height = 108;
        final int gap = 12;

        paintPlayersMetric(graphics, 32, y, width, height, snapshot);
        paintTpsMetric(graphics, 32 + width + gap, y, width, height, snapshot);
        paintMemoryMetric(graphics, 32 + (width + gap) * 2, y, width, height, snapshot);
        paintUptimeMetric(graphics, 32 + (width + gap) * 3, y, width, height, snapshot);
    }

    private static void paintMetricShell(Graphics2D graphics, int x, int y, int width, int height, String label) {
        fillRoundRect(graphics, x, y, width, height, 18, PANEL);
        graphics.setFont(font(Font.BOLD, 12));
        graphics.setColor(MUTED);
        graphics.drawString(label.toUpperCase(Locale.ROOT), x + 17, y + 25);
    }

    private static void paintPlayersMetric(Graphics2D graphics, int x, int y, int width, int height, Snapshot snapshot) {
        paintMetricShell(graphics, x, y, width, height, "Players");
        graphics.setFont(font(Font.BOLD, 27));
        graphics.setColor(TEXT);
        graphics.drawString(snapshot.onlinePlayers + " / " + snapshot.maxPlayers, x + 17, y + 61);

        double ratio = snapshot.maxPlayers <= 0 ? 0d : snapshot.onlinePlayers / (double) snapshot.maxPlayers;
        drawProgressBar(graphics, x + 17, y + 78, width - 34, 9, ratio, ACCENT);
        graphics.setFont(font(Font.PLAIN, 11));
        graphics.setColor(MUTED);
        graphics.drawString(snapshot.onlinePlayers == 1 ? "1 player connected" : snapshot.onlinePlayers + " players connected", x + 17, y + 101);
    }

    private static void paintTpsMetric(Graphics2D graphics, int x, int y, int width, int height, Snapshot snapshot) {
        paintMetricShell(graphics, x, y, width, height, "TPS");
        double primaryTps = sanitizedTps(snapshot.tpsOneMinute);
        graphics.setFont(font(Font.BOLD, 27));
        graphics.setColor(tpsColor(primaryTps));
        graphics.drawString(formatTps(primaryTps), x + 17, y + 61);

        graphics.setFont(font(Font.PLAIN, 12));
        graphics.setColor(MUTED);
        String history = "5m " + formatTps(snapshot.tpsFiveMinutes) + "   15m " + formatTps(snapshot.tpsFifteenMinutes);
        drawEllipsized(graphics, history, x + 17, y + 92, width - 34);
    }

    private static void paintMemoryMetric(Graphics2D graphics, int x, int y, int width, int height, Snapshot snapshot) {
        paintMetricShell(graphics, x, y, width, height, "Memory");
        graphics.setFont(font(Font.BOLD, 21));
        graphics.setColor(TEXT);
        drawEllipsized(
                graphics,
                formatMemory(snapshot.usedMemoryBytes) + " / " + formatMemory(snapshot.maxMemoryBytes),
                x + 17,
                y + 59,
                width - 34
        );

        double ratio = snapshot.maxMemoryBytes <= 0L
                ? 0d
                : snapshot.usedMemoryBytes / (double) snapshot.maxMemoryBytes;
        drawProgressBar(graphics, x + 17, y + 78, width - 34, 9, ratio, ratio > 0.9d ? DANGER : ACCENT);

        graphics.setFont(font(Font.PLAIN, 11));
        graphics.setColor(MUTED);
        int percentage = (int) Math.round(clamp(ratio, 0d, 1d) * 100d);
        graphics.drawString(percentage + "% of allocated heap", x + 17, y + 101);
    }

    private static void paintUptimeMetric(Graphics2D graphics, int x, int y, int width, int height, Snapshot snapshot) {
        paintMetricShell(graphics, x, y, width, height, "Uptime");
        graphics.setFont(font(Font.BOLD, 24));
        graphics.setColor(TEXT);
        drawEllipsized(graphics, defaultText(snapshot.uptime, "Just started"), x + 17, y + 61, width - 34);

        graphics.setFont(font(Font.PLAIN, 12));
        graphics.setColor(MUTED);
        graphics.drawString("Since the last restart", x + 17, y + 92);
    }

    private static void drawProgressBar(
            Graphics2D graphics,
            int x,
            int y,
            int width,
            int height,
            double ratio,
            Color color
    ) {
        fillRoundRect(graphics, x, y, width, height, height, new Color(7, 20, 24));
        int filledWidth = (int) Math.round(width * clamp(ratio, 0d, 1d));
        if (filledWidth > 0) {
            fillRoundRect(graphics, x, y, Math.max(height, filledWidth), height, height, color);
        }
    }

    private static void paintPlayers(Graphics2D graphics, Snapshot snapshot) {
        final int x = 32;
        final int y = 274;
        final int width = WIDTH - 64;
        final int height = 234;
        fillRoundRect(graphics, x, y, width, height, 22, PANEL);

        graphics.setFont(font(Font.BOLD, 18));
        graphics.setColor(TEXT);
        graphics.drawString("Players online", x + 20, y + 31);

        int represented = Math.min(10, snapshot.players.size());
        graphics.setFont(font(Font.PLAIN, 12));
        graphics.setColor(MUTED);
        String summary;
        if (snapshot.onlinePlayers <= 0) {
            summary = "No one is connected right now";
        } else if (snapshot.onlinePlayers > represented) {
            summary = "Showing " + represented + " of " + snapshot.onlinePlayers;
        } else {
            summary = snapshot.onlinePlayers == 1 ? "1 connected player" : snapshot.onlinePlayers + " connected players";
        }
        drawEllipsizedRightAligned(graphics, summary, x + width - 20, y + 29, 300);

        if (represented == 0) {
            paintEmptyPlayers(graphics, x, y, width, height);
            return;
        }

        final int cellWidth = 163;
        final int cellHeight = 70;
        final int gapX = 10;
        final int gapY = 10;
        final int startX = x + 20;
        final int startY = y + 50;

        for (int index = 0; index < represented; index++) {
            int column = index % 5;
            int row = index / 5;
            int cellX = startX + column * (cellWidth + gapX);
            int cellY = startY + row * (cellHeight + gapY);
            paintPlayerCell(graphics, snapshot.players.get(index), cellX, cellY, cellWidth, cellHeight);
        }

        if (snapshot.onlinePlayers > represented) {
            graphics.setFont(font(Font.BOLD, 12));
            graphics.setColor(ACCENT);
            graphics.drawString("+" + (snapshot.onlinePlayers - represented) + " more online", x + 20, y + height - 14);
        }
    }

    private static void paintEmptyPlayers(Graphics2D graphics, int x, int y, int width, int height) {
        graphics.setColor(new Color(37, 211, 102, 28));
        graphics.fillOval(x + 25, y + 77, 68, 68);
        graphics.setColor(new Color(37, 211, 102, 90));
        graphics.fillOval(x + 48, y + 93, 22, 22);
        graphics.fillRoundRect(x + 39, y + 119, 40, 22, 12, 12);

        graphics.setFont(font(Font.BOLD, 20));
        graphics.setColor(TEXT);
        graphics.drawString("The server is quiet", x + 115, y + 108);
        graphics.setFont(font(Font.PLAIN, 14));
        graphics.setColor(MUTED);
        graphics.drawString("Player heads will appear here as soon as someone joins.", x + 115, y + 135);
    }

    private static void paintPlayerCell(
            Graphics2D graphics,
            PlayerEntry player,
            int x,
            int y,
            int width,
            int height
    ) {
        fillRoundRect(graphics, x, y, width, height, 15, PANEL_LIGHT);
        drawPlayerHead(graphics, player == null ? null : player.head, x + 9, y + 9, 52,
                player == null ? "?" : player.name);

        graphics.setFont(font(Font.BOLD, 14));
        graphics.setColor(TEXT);
        drawEllipsized(graphics, defaultText(player == null ? null : player.name, "Player"), x + 70, y + 32, width - 79);

        graphics.setFont(font(Font.PLAIN, 11));
        graphics.setColor(ACCENT);
        graphics.fillOval(x + 70, y + 44, 7, 7);
        graphics.drawString("online", x + 82, y + 52);
    }

    private static void drawPlayerHead(Graphics2D graphics, BufferedImage head, int x, int y, int size, String playerName) {
        Shape oldClip = graphics.getClip();
        graphics.clip(new RoundRectangle2D.Double(x, y, size, size, 11, 11));
        if (head != null && head.getWidth() > 0 && head.getHeight() > 0) {
            Object oldInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            drawCoverImage(graphics, head, x, y, size, size);
            if (oldInterpolation != null) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
            }
        } else {
            int hash = playerName == null ? 0 : playerName.hashCode();
            Color base = new Color(66 + Math.abs(hash % 80), 87 + Math.abs((hash / 7) % 70), 101 + Math.abs((hash / 17) % 70));
            graphics.setColor(base);
            graphics.fillRect(x, y, size, size);
            graphics.setColor(new Color(255, 255, 255, 38));
            graphics.fillRect(x, y, size, size / 3);
            graphics.setFont(font(Font.BOLD, 22));
            graphics.setColor(TEXT);
            String initial = firstInitial(playerName);
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2, y + 34);
        }
        graphics.setClip(oldClip);

        graphics.setStroke(new BasicStroke(1f));
        graphics.setColor(new Color(255, 255, 255, 28));
        graphics.draw(new RoundRectangle2D.Double(x + 0.5d, y + 0.5d, size - 1d, size - 1d, 11, 11));
    }

    private static void drawCoverImage(Graphics2D graphics, BufferedImage image, int x, int y, int width, int height) {
        double scale = Math.max(width / (double) image.getWidth(), height / (double) image.getHeight());
        int drawWidth = Math.max(1, (int) Math.ceil(image.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.ceil(image.getHeight() * scale));
        int drawX = x + (width - drawWidth) / 2;
        int drawY = y + (height - drawHeight) / 2;
        graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
    }

    private static void fillRoundRect(
            Graphics2D graphics,
            int x,
            int y,
            int width,
            int height,
            int arc,
            Color color
    ) {
        graphics.setColor(color);
        graphics.fillRoundRect(x, y, width, height, arc, arc);
    }

    private static void drawEllipsized(Graphics2D graphics, String value, int x, int baseline, int maximumWidth) {
        graphics.drawString(ellipsize(graphics.getFontMetrics(), value, maximumWidth), x, baseline);
    }

    private static void drawEllipsizedRightAligned(
            Graphics2D graphics,
            String value,
            int rightX,
            int baseline,
            int maximumWidth
    ) {
        String clipped = ellipsize(graphics.getFontMetrics(), value, maximumWidth);
        graphics.drawString(clipped, rightX - graphics.getFontMetrics().stringWidth(clipped), baseline);
    }

    private static String ellipsize(FontMetrics metrics, String value, int maximumWidth) {
        String normalized = defaultText(stripControlCharacters(value), "-");
        if (maximumWidth <= 0 || metrics.stringWidth(normalized) <= maximumWidth) {
            return normalized;
        }

        final String suffix = "...";
        int available = maximumWidth - metrics.stringWidth(suffix);
        if (available <= 0) {
            return suffix;
        }

        int low = 0;
        int high = normalized.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metrics.stringWidth(normalized.substring(0, middle)) <= available) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return normalized.substring(0, low).trim() + suffix;
    }

    private static Font font(int style, int size) {
        return new Font(Font.SANS_SERIF, style, size);
    }

    private static String firstInitial(String value) {
        String normalized = defaultText(value, "?").trim();
        if (normalized.isEmpty()) {
            return "?";
        }
        int end = normalized.offsetByCodePoints(0, 1);
        return normalized.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static String stripControlCharacters(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder(value.length());
        boolean previousWasSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\u00a7' && index + 1 < value.length()) {
                index++;
                continue;
            }
            boolean whitespace = Character.isWhitespace(character);
            if (Character.isISOControl(character) && !whitespace) {
                continue;
            }
            if (whitespace) {
                if (!previousWasSpace) {
                    clean.append(' ');
                }
                previousWasSpace = true;
            } else {
                clean.append(character);
                previousWasSpace = false;
            }
        }
        return clean.toString().trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static double sanitizedTps(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0d) {
            return Double.NaN;
        }
        return value;
    }

    private static String formatTps(double value) {
        double sanitized = sanitizedTps(value);
        return Double.isNaN(sanitized) ? "N/A" : String.format(Locale.ROOT, "%.1f", sanitized);
    }

    private static Color tpsColor(double tps) {
        if (Double.isNaN(sanitizedTps(tps))) {
            return MUTED;
        }
        if (tps >= 18d) {
            return ACCENT;
        }
        if (tps >= 15d) {
            return WARNING;
        }
        return DANGER;
    }

    private static String formatMemory(long bytes) {
        long safeBytes = Math.max(0L, bytes);
        final long gibibyte = 1024L * 1024L * 1024L;
        final long mebibyte = 1024L * 1024L;
        if (safeBytes >= gibibyte) {
            return String.format(Locale.ROOT, "%.1f GB", safeBytes / (double) gibibyte);
        }
        return Math.round(safeBytes / (double) mebibyte) + " MB";
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** One player displayed on the status card. */
    public static final class PlayerEntry {
        private final String name;
        private final BufferedImage head;

        public PlayerEntry(String name, BufferedImage head) {
            this.name = defaultText(name, "Player");
            this.head = head;
        }

        public String getName() {
            return name;
        }

        public BufferedImage getHead() {
            return head;
        }
    }

    /** Immutable snapshot of all data needed to render a status card. */
    public static final class Snapshot {
        private final String serverName;
        private final String motd;
        private final String serverVersion;
        private final String uptime;
        private final int onlinePlayers;
        private final int maxPlayers;
        private final double tpsOneMinute;
        private final double tpsFiveMinutes;
        private final double tpsFifteenMinutes;
        private final long usedMemoryBytes;
        private final long maxMemoryBytes;
        private final BufferedImage serverIcon;
        private final List<PlayerEntry> players;

        public Snapshot(
                String serverName,
                String motd,
                String serverVersion,
                String uptime,
                int onlinePlayers,
                int maxPlayers,
                double tpsOneMinute,
                double tpsFiveMinutes,
                double tpsFifteenMinutes,
                long usedMemoryBytes,
                long maxMemoryBytes,
                BufferedImage serverIcon,
                List<PlayerEntry> players
        ) {
            this.serverName = defaultText(serverName, "Minecraft Server");
            this.motd = defaultText(motd, "Server status");
            this.serverVersion = defaultText(serverVersion, "Minecraft");
            this.uptime = defaultText(uptime, "Just started");
            this.onlinePlayers = Math.max(0, onlinePlayers);
            this.maxPlayers = Math.max(0, maxPlayers);
            this.tpsOneMinute = sanitizedTps(tpsOneMinute);
            this.tpsFiveMinutes = sanitizedTps(tpsFiveMinutes);
            this.tpsFifteenMinutes = sanitizedTps(tpsFifteenMinutes);
            this.usedMemoryBytes = Math.max(0L, usedMemoryBytes);
            this.maxMemoryBytes = Math.max(0L, maxMemoryBytes);
            this.serverIcon = serverIcon;
            List<PlayerEntry> safePlayers = players == null
                    ? Collections.<PlayerEntry>emptyList()
                    : new ArrayList<PlayerEntry>(players);
            this.players = Collections.unmodifiableList(safePlayers);
        }

        public static Builder builder() {
            return new Builder();
        }

        public String getServerName() {
            return serverName;
        }

        public String getMotd() {
            return motd;
        }

        public String getServerVersion() {
            return serverVersion;
        }

        public String getUptime() {
            return uptime;
        }

        public int getOnlinePlayers() {
            return onlinePlayers;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public double getTpsOneMinute() {
            return tpsOneMinute;
        }

        public double getTpsFiveMinutes() {
            return tpsFiveMinutes;
        }

        public double getTpsFifteenMinutes() {
            return tpsFifteenMinutes;
        }

        public long getUsedMemoryBytes() {
            return usedMemoryBytes;
        }

        public long getMaxMemoryBytes() {
            return maxMemoryBytes;
        }

        public BufferedImage getServerIcon() {
            return serverIcon;
        }

        public List<PlayerEntry> getPlayers() {
            return players;
        }

        /** Fluent convenience builder; omitted values receive sensible defaults. */
        public static final class Builder {
            private String serverName;
            private String motd;
            private String serverVersion;
            private String uptime;
            private int onlinePlayers;
            private int maxPlayers;
            private double tpsOneMinute;
            private double tpsFiveMinutes;
            private double tpsFifteenMinutes;
            private long usedMemoryBytes;
            private long maxMemoryBytes;
            private BufferedImage serverIcon;
            private List<PlayerEntry> players = Collections.emptyList();

            private Builder() {
            }

            public Builder serverName(String value) {
                serverName = value;
                return this;
            }

            public Builder motd(String value) {
                motd = value;
                return this;
            }

            public Builder serverVersion(String value) {
                serverVersion = value;
                return this;
            }

            public Builder uptime(String value) {
                uptime = value;
                return this;
            }

            public Builder playersOnline(int online, int maximum) {
                onlinePlayers = online;
                maxPlayers = maximum;
                return this;
            }

            public Builder tps(double oneMinute, double fiveMinutes, double fifteenMinutes) {
                tpsOneMinute = oneMinute;
                tpsFiveMinutes = fiveMinutes;
                tpsFifteenMinutes = fifteenMinutes;
                return this;
            }

            public Builder memory(long usedBytes, long maximumBytes) {
                usedMemoryBytes = usedBytes;
                maxMemoryBytes = maximumBytes;
                return this;
            }

            public Builder serverIcon(BufferedImage value) {
                serverIcon = value;
                return this;
            }

            public Builder players(List<PlayerEntry> value) {
                players = value;
                return this;
            }

            public Snapshot build() {
                return new Snapshot(
                        serverName,
                        motd,
                        serverVersion,
                        uptime,
                        onlinePlayers,
                        maxPlayers,
                        tpsOneMinute,
                        tpsFiveMinutes,
                        tpsFifteenMinutes,
                        usedMemoryBytes,
                        maxMemoryBytes,
                        serverIcon,
                        players
                );
            }
        }
    }
}
