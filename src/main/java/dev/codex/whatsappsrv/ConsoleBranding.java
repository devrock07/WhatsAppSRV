package dev.codex.whatsappsrv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds fixed-width, ASCII-only branding that remains readable in server consoles. */
final class ConsoleBranding {
    static final int WIDTH = 80;

    private static final int INNER_WIDTH = WIDTH - 4;
    private static final String[] LOGO = {
            " __        ___           _          _               ____  ______     __",
            " \\ \\      / / |__   __ _| |_ ___   / \\   _ __  _ __/ ___||  _ \\ \\   / /",
            "  \\ \\ /\\ / /| '_ \\ / _` | __/ __| / _ \\ | '_ \\| '_ \\___ \\| |_) \\ \\ / /",
            "   \\ V  V / | | | | (_| | |_\\__ \\/ ___ \\| |_) | |_) |__) |  _ < \\ V /",
            "    \\_/\\_/  |_| |_|\\__,_|\\__|___/_/   \\_\\ .__/| .__/____/|_| \\_\\ \\_/",
            "                                        |_|   |_|"
    };

    private ConsoleBranding() {
    }

    static List<Line> startupLines(String version) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(border(), Style.BORDER));
        lines.add(new Line(box(""), Style.PLAIN));
        for (String logoLine : LOGO) lines.add(new Line(box(logoLine), Style.LOGO));
        lines.add(new Line(box(""), Style.PLAIN));
        lines.add(new Line(box(center("MINECRAFT <-> WHATSAPP BRIDGE")), Style.TAGLINE));
        lines.add(new Line(box(center("MADE BY DEVROCK")), Style.CREDIT));
        lines.add(new Line(box(center("VERSION " + cleanVersion(version))), Style.VERSION));
        lines.add(new Line(box(""), Style.PLAIN));
        lines.add(new Line(border(), Style.BORDER));
        return Collections.unmodifiableList(lines);
    }

    private static String border() {
        return "+" + repeat('-', WIDTH - 2) + "+";
    }

    private static String box(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() > INNER_WIDTH) safe = safe.substring(0, INNER_WIDTH);
        return "| " + safe + repeat(' ', INNER_WIDTH - safe.length()) + " |";
    }

    private static String center(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() >= INNER_WIDTH) return safe;
        int left = (INNER_WIDTH - safe.length()) / 2;
        return repeat(' ', left) + safe;
    }

    private static String cleanVersion(String version) {
        if (version == null || version.trim().isEmpty()) return "UNKNOWN";
        return version.replaceAll("[^A-Za-z0-9._+-]", "");
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(Math.max(0, count));
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    enum Style {
        BORDER,
        LOGO,
        TAGLINE,
        CREDIT,
        VERSION,
        PLAIN
    }

    static final class Line {
        final String text;
        final Style style;

        Line(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }
}
