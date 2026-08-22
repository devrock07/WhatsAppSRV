package dev.codex.whatsappsrv;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConsoleBrandingTest {
    @Test
    void startupBannerIsFixedWidthAsciiAndIncludesCredits() {
        List<ConsoleBranding.Line> lines = ConsoleBranding.startupLines("1.0.1");
        StringBuilder combined = new StringBuilder();

        for (ConsoleBranding.Line line : lines) {
            assertEquals(ConsoleBranding.WIDTH, line.text.length(), line.text);
            line.text.codePoints().forEach(codePoint ->
                    assertTrue(codePoint >= 32 && codePoint <= 126,
                            "Banner must remain printable ASCII: " + codePoint));
            combined.append(line.text).append('\n');
        }

        assertTrue(combined.toString().contains("MADE BY DEVROCK"));
        assertTrue(combined.toString().contains("VERSION 1.0.1"));
        assertTrue(lines.stream().anyMatch(line -> line.style == ConsoleBranding.Style.LOGO));
        assertEquals(ConsoleBranding.Style.BORDER, lines.get(0).style);
        assertEquals(ConsoleBranding.Style.BORDER, lines.get(lines.size() - 1).style);
    }
}
