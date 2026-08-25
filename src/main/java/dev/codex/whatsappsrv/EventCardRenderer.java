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
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Renders WhatsApp-ready advancement and death event cards without touching Bukkit state. */
final class EventCardRenderer {
    static final int WIDTH = 960;
    static final int HEIGHT = 540;

    private static final Color TEXT = new Color(244, 248, 246);
    private static final Color MUTED = new Color(173, 188, 184);
    private static final Color DARK = new Color(7, 14, 18);
    private static final Color PANEL = new Color(17, 29, 34, 238);
    private static final Color GOLD = new Color(255, 194, 71);
    private static final Color RED = new Color(255, 91, 91);

    private EventCardRenderer() {
    }

    static byte[] renderAdvancement(AdvancementCard card) throws IOException {
        if (card == null) throw new IllegalArgumentException("card cannot be null");
        BufferedImage image = canvas(new Color(31, 20, 8), new Color(9, 25, 25), GOLD);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            paintBlockPattern(graphics, GOLD);
            panel(graphics, 36, 32, 888, 476, 28);
            paintTopBar(graphics, "ADVANCEMENT UNLOCKED", GOLD, "ACHIEVEMENT GET!");
            paintAvatar(graphics, card.head, 70, 170, 184, GOLD);

            graphics.setFont(pixelFont(Font.BOLD, 20));
            graphics.setColor(GOLD);
            graphics.drawString(safe(card.playerName, "Player").toUpperCase(Locale.ROOT), 290, 190);

            drawWrapped(graphics, safe(card.title, "Minecraft Advancement"),
                    pixelFont(Font.BOLD, 39), TEXT, 290, 230, 570, 2, 47);

            String detail = safe(card.description, "A new Minecraft challenge has been completed.");
            drawWrapped(graphics, detail, pixelFont(Font.PLAIN, 19), MUTED,
                    290, 342, 565, 3, 28);

            String category = safe(card.category, "Minecraft").toUpperCase(Locale.ROOT);
            badge(graphics, 290, 430, Math.min(270, 44 + textWidth(graphics, pixelFont(Font.BOLD, 15), category)),
                    42, category, GOLD);
            drawTrophy(graphics, 842, 73, 38, GOLD);
            footer(graphics, "WHATSAPPSRV  •  MINECRAFT ADVANCEMENT", GOLD);
        } finally {
            graphics.dispose();
        }
        return png(image);
    }

    static byte[] renderDeath(DeathCard card) throws IOException {
        if (card == null) throw new IllegalArgumentException("card cannot be null");
        BufferedImage image = canvas(new Color(35, 8, 12), new Color(9, 17, 23), RED);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            paintBlockPattern(graphics, RED);
            panel(graphics, 36, 32, 888, 476, 28);
            paintTopBar(graphics, "PLAYER DEATH", RED, "LOCATION RECORDED");
            paintDeathIdentity(graphics, card);
            paintTerrainMap(graphics, card);
            footer(graphics, "WHATSAPPSRV  •  DEATH LOCATION", RED);
        } finally {
            graphics.dispose();
        }
        return png(image);
    }

    private static BufferedImage canvas(Color top, Color bottom, Color glow) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            graphics.setPaint(new GradientPaint(0, 0, top, WIDTH, HEIGHT, bottom));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            Composite old = graphics.getComposite();
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
            graphics.setColor(glow);
            graphics.fillOval(-160, 310, 390, 390);
            graphics.fillOval(720, -190, 420, 420);
            graphics.setComposite(old);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    private static void paintBlockPattern(Graphics2D graphics, Color accent) {
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 9));
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 14; column++) {
                if ((row * 2 + column) % 4 == 0) {
                    graphics.fillRect(column * 76 - 30, row * 76 - 22, 38, 38);
                }
            }
        }
    }

    private static void panel(Graphics2D graphics, int x, int y, int width, int height, int radius) {
        graphics.setColor(PANEL);
        graphics.fill(new RoundRectangle2D.Double(x, y, width, height, radius, radius));
        graphics.setStroke(new BasicStroke(1.5f));
        graphics.setColor(new Color(255, 255, 255, 16));
        graphics.draw(new RoundRectangle2D.Double(x + 1, y + 1, width - 2, height - 2, radius, radius));
    }

    private static void paintTopBar(Graphics2D graphics, String title, Color accent, String right) {
        graphics.setColor(accent);
        graphics.fillRoundRect(36, 32, 9, 476, 9, 9);
        graphics.setFont(pixelFont(Font.BOLD, 18));
        graphics.drawString(title, 70, 83);
        graphics.setFont(pixelFont(Font.BOLD, 12));
        graphics.setColor(MUTED);
        drawRight(graphics, right, 886, 81);
        graphics.setColor(new Color(255, 255, 255, 16));
        graphics.fillRect(70, 105, 816, 2);
    }

    private static void paintAvatar(Graphics2D graphics, BufferedImage head, int x, int y, int size, Color accent) {
        graphics.setColor(new Color(4, 10, 13));
        graphics.fillRoundRect(x - 10, y - 10, size + 20, size + 20, 28, 28);
        Shape oldClip = graphics.getClip();
        graphics.clip(new RoundRectangle2D.Double(x, y, size, size, 18, 18));
        Object interpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (head != null) {
            graphics.drawImage(head, x, y, size, size, null);
        } else {
            graphics.setColor(new Color(95, 108, 112));
            graphics.fillRect(x, y, size, size);
            graphics.setColor(new Color(65, 76, 80));
            graphics.fillRect(x + size / 5, y + size / 5, size * 3 / 5, size * 3 / 5);
        }
        if (interpolation != null) graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        graphics.setClip(oldClip);
        graphics.setStroke(new BasicStroke(4f));
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 185));
        graphics.drawRoundRect(x - 2, y - 2, size + 3, size + 3, 21, 21);
    }

    private static void paintDeathIdentity(Graphics2D graphics, DeathCard card) {
        paintAvatar(graphics, card.head, 70, 138, 116, RED);
        graphics.setFont(pixelFont(Font.BOLD, 21));
        graphics.setColor(RED);
        graphics.drawString(safe(card.playerName, "Player").toUpperCase(Locale.ROOT), 215, 163);
        drawWrapped(graphics, safe(card.deathMessage, "Player died"), pixelFont(Font.BOLD, 25),
                TEXT, 215, 199, 310, 3, 33);

        graphics.setFont(pixelFont(Font.BOLD, 13));
        graphics.setColor(MUTED);
        graphics.drawString("EXACT COORDINATES", 70, 307);
        graphics.setFont(pixelFont(Font.BOLD, 27));
        graphics.setColor(TEXT);
        graphics.drawString("X " + card.x + "   Y " + card.y + "   Z " + card.z, 70, 342);

        infoLine(graphics, "WORLD", safe(card.world, "world"), 70, 387, 210);
        infoLine(graphics, "BIOME", titleCase(safe(card.biome, "unknown")), 70, 431, 210);
        infoLine(graphics, "DIMENSION", titleCase(safe(card.environment, "normal")), 70, 475, 210);
    }

    private static void infoLine(Graphics2D graphics, String label, String value, int x, int y, int valueX) {
        graphics.setFont(pixelFont(Font.BOLD, 12));
        graphics.setColor(MUTED);
        graphics.drawString(label, x, y);
        graphics.setFont(pixelFont(Font.BOLD, 16));
        graphics.setColor(TEXT);
        drawEllipsized(graphics, value, valueX, y, 290);
    }

    private static void paintTerrainMap(Graphics2D graphics, DeathCard card) {
        int mapX = 548;
        int mapY = 134;
        int mapWidth = 338;
        int mapHeight = 338;
        graphics.setColor(new Color(3, 8, 11));
        graphics.fillRoundRect(mapX - 8, mapY - 8, mapWidth + 16, mapHeight + 16, 20, 20);

        int size = card.terrain == null ? 0 : card.terrain.length;
        int cell = size <= 0 ? mapWidth : Math.max(1, mapWidth / size);
        int used = cell * Math.max(1, size);
        int startX = mapX + (mapWidth - used) / 2;
        int startY = mapY + (mapHeight - used) / 2;
        if (size <= 0) {
            graphics.setColor(new Color(36, 48, 52));
            graphics.fillRoundRect(mapX, mapY, mapWidth, mapHeight, 14, 14);
        } else {
            Shape oldClip = graphics.getClip();
            graphics.clip(new RoundRectangle2D.Double(mapX, mapY, mapWidth, mapHeight, 14, 14));
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < card.terrain[z].length; x++) {
                    Color base = materialColor(card.terrain[z][x]);
                    graphics.setColor(base);
                    graphics.fillRect(startX + x * cell, startY + z * cell, cell + 1, cell + 1);
                    graphics.setColor(new Color(255, 255, 255, ((x + z) & 1) == 0 ? 7 : 2));
                    graphics.fillRect(startX + x * cell, startY + z * cell, cell + 1, Math.max(1, cell / 8));
                }
            }
            graphics.setClip(oldClip);
        }

        int centerX = mapX + mapWidth / 2;
        int centerY = mapY + mapHeight / 2;
        graphics.setColor(new Color(0, 0, 0, 130));
        graphics.fillOval(centerX - 18, centerY + 15, 36, 12);
        drawLocationPin(graphics, centerX, centerY, RED);

        graphics.setFont(pixelFont(Font.BOLD, 11));
        graphics.setColor(new Color(255, 255, 255, 220));
        graphics.fillRoundRect(mapX + 12, mapY + 12, 138, 27, 12, 12);
        graphics.setColor(DARK);
        graphics.drawString("REAL BLOCK MAP", mapX + 25, mapY + 31);
        graphics.setFont(pixelFont(Font.BOLD, 11));
        graphics.setColor(MUTED);
        drawRight(graphics, "N ↑", mapX + mapWidth - 13, mapY + 30);
    }

    private static void drawLocationPin(Graphics2D graphics, int x, int y, Color color) {
        Path2D pin = new Path2D.Double();
        pin.moveTo(x, y + 31);
        pin.curveTo(x - 7, y + 18, x - 20, y + 7, x - 20, y - 5);
        pin.curveTo(x - 20, y - 18, x - 11, y - 27, x, y - 27);
        pin.curveTo(x + 11, y - 27, x + 20, y - 18, x + 20, y - 5);
        pin.curveTo(x + 20, y + 7, x + 7, y + 18, x, y + 31);
        graphics.setColor(new Color(0, 0, 0, 110));
        graphics.translate(2, 3);
        graphics.fill(pin);
        graphics.translate(-2, -3);
        graphics.setColor(color);
        graphics.fill(pin);
        graphics.setColor(TEXT);
        graphics.fillOval(x - 7, y - 12, 14, 14);
    }

    private static Color materialColor(String material) {
        String name = safe(material, "AIR").toUpperCase(Locale.ROOT);
        if (name.contains("WATER")) return new Color(40, 102, 190);
        if (name.contains("LAVA")) return new Color(237, 92, 23);
        if (name.contains("GRASS") || name.contains("MOSS") || name.contains("LEAVES")) return new Color(82, 137, 60);
        if (name.contains("SAND")) return new Color(207, 191, 129);
        if (name.contains("SNOW") || name.contains("ICE")) return new Color(205, 226, 231);
        if (name.contains("NETHERRACK") || name.contains("NETHER_WART")) return new Color(117, 48, 45);
        if (name.contains("SOUL")) return new Color(89, 72, 57);
        if (name.contains("END_STONE")) return new Color(205, 209, 145);
        if (name.contains("PURPUR")) return new Color(169, 116, 169);
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANK")) return new Color(130, 94, 53);
        if (name.contains("DIRT") || name.contains("PODZOL") || name.contains("MUD")) return new Color(111, 78, 51);
        if (name.contains("DEEPSLATE") || name.contains("BLACKSTONE")) return new Color(54, 57, 61);
        if (name.contains("STONE") || name.contains("ORE") || name.contains("COBBLE")) return new Color(108, 111, 111);
        if (name.contains("BEDROCK")) return new Color(43, 43, 43);
        if (name.contains("AIR") || name.contains("VOID")) return new Color(27, 39, 45);
        int hash = name.hashCode();
        return new Color(70 + (hash >>> 16 & 47), 70 + (hash >>> 8 & 47), 70 + (hash & 47));
    }

    private static void drawTrophy(Graphics2D graphics, int x, int y, int size, Color color) {
        graphics.setColor(color);
        graphics.fillRoundRect(x - size / 3, y - size / 2, size * 2 / 3, size * 3 / 5, 7, 7);
        graphics.setStroke(new BasicStroke(5f));
        graphics.drawArc(x - size / 2, y - size / 2 + 3, size / 2, size / 2, 90, 180);
        graphics.drawArc(x, y - size / 2 + 3, size / 2, size / 2, -90, 180);
        graphics.fillRect(x - 4, y + 2, 8, 17);
        graphics.fillRoundRect(x - 16, y + 16, 32, 8, 5, 5);
    }

    private static void badge(Graphics2D graphics, int x, int y, int width, int height, String text, Color accent) {
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30));
        graphics.fillRoundRect(x, y, width, height, 20, 20);
        graphics.setFont(pixelFont(Font.BOLD, 15));
        graphics.setColor(accent);
        graphics.drawString(text, x + 20, y + 27);
    }

    private static void footer(Graphics2D graphics, String text, Color accent) {
        graphics.setFont(pixelFont(Font.BOLD, 10));
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 185));
        drawRight(graphics, text, 886, 493);
    }

    private static Font pixelFont(int style, int size) {
        return new Font(Font.MONOSPACED, style, size);
    }

    private static void drawWrapped(Graphics2D graphics, String value, Font font, Color color,
                                    int x, int y, int width, int maxLines, int lineHeight) {
        graphics.setFont(font);
        graphics.setColor(color);
        FontMetrics metrics = graphics.getFontMetrics();
        String remaining = safe(value, "");
        int line = 0;
        while (!remaining.isEmpty() && line < maxLines) {
            int split = remaining.length();
            while (split > 1 && metrics.stringWidth(remaining.substring(0, split)) > width) {
                int previousSpace = remaining.lastIndexOf(' ', split - 1);
                split = previousSpace > 0 ? previousSpace : split - 1;
            }
            String part = remaining.substring(0, split).trim();
            remaining = remaining.substring(split).trim();
            if (line == maxLines - 1 && !remaining.isEmpty()) {
                while (!part.isEmpty() && metrics.stringWidth(part + "…") > width) {
                    part = part.substring(0, part.length() - 1);
                }
                part += "…";
                remaining = "";
            }
            graphics.drawString(part, x, y + line * lineHeight);
            line++;
        }
    }

    private static void drawEllipsized(Graphics2D graphics, String value, int x, int y, int width) {
        FontMetrics metrics = graphics.getFontMetrics();
        String text = safe(value, "");
        while (!text.isEmpty() && metrics.stringWidth(text + "…") > width) {
            text = text.substring(0, text.length() - 1);
        }
        graphics.drawString(text.equals(value) ? text : text + "…", x, y);
    }

    private static int textWidth(Graphics2D graphics, Font font, String text) {
        Font old = graphics.getFont();
        graphics.setFont(font);
        int width = graphics.getFontMetrics().stringWidth(text);
        graphics.setFont(old);
        return width;
    }

    private static void drawRight(Graphics2D graphics, String text, int rightX, int y) {
        graphics.drawString(text, rightX - graphics.getFontMetrics().stringWidth(text), y);
    }

    private static String titleCase(String value) {
        String normalized = value.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean upper = true;
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            result.append(upper ? Character.toUpperCase(character) : character);
            upper = Character.isWhitespace(character);
        }
        return result.toString();
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(96 * 1024);
        if (!ImageIO.write(image, "png", output)) throw new IOException("No PNG image writer is available");
        return output.toByteArray();
    }

    static final class AdvancementCard {
        final String playerName;
        final String title;
        final String description;
        final String category;
        final BufferedImage head;

        AdvancementCard(String playerName, String title, String description, String category, BufferedImage head) {
            this.playerName = playerName;
            this.title = title;
            this.description = description;
            this.category = category;
            this.head = head;
        }
    }

    static final class DeathCard {
        final String playerName;
        final String deathMessage;
        final String world;
        final String biome;
        final String environment;
        final int x;
        final int y;
        final int z;
        final String[][] terrain;
        final BufferedImage head;

        DeathCard(String playerName, String deathMessage, String world, String biome, String environment,
                  int x, int y, int z, String[][] terrain, BufferedImage head) {
            this.playerName = playerName;
            this.deathMessage = deathMessage;
            this.world = world;
            this.biome = biome;
            this.environment = environment;
            this.x = x;
            this.y = y;
            this.z = z;
            this.terrain = terrain;
            this.head = head;
        }
    }
}
