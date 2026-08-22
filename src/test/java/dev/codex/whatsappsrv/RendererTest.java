package dev.codex.whatsappsrv;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RendererTest {
    @Test
    void statusCardProducesBoundedWhatsAppReadyPng() throws Exception {
        BufferedImage head = ImageIO.read(new ByteArrayInputStream(
                PlayerHeadRenderer.renderPng("devrock14", null)));
        byte[] png = StatusCardRenderer.renderPng(StatusCardRenderer.Snapshot.builder()
                .serverName("Kala Billa SMP")
                .motd("A long-lived survival world")
                .serverVersion("Paper test build")
                .uptime("2d 4h")
                .playersOnline(2, 30)
                .tps(19.95, 19.90, 19.85)
                .memory(2L * 1024L * 1024L * 1024L, 6L * 1024L * 1024L * 1024L)
                .players(Arrays.asList(
                        new StatusCardRenderer.PlayerEntry("devrock14", head),
                        new StatusCardRenderer.PlayerEntry("Offline_Player", null)))
                .build());

        assertTrue(png.length > 8);
        assertTrue(png.length < 2 * 1024 * 1024, "WhatsApp bridge image limit");
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded);
        assertEquals(StatusCardRenderer.WIDTH, decoded.getWidth());
        assertEquals(StatusCardRenderer.HEIGHT, decoded.getHeight());
    }

    @Test
    void rendererHandlesUnavailableMetricsAndEmptyPlayers() throws Exception {
        byte[] png = StatusCardRenderer.renderPng(StatusCardRenderer.Snapshot.builder()
                .serverName(null)
                .motd("§aFormatting is removed")
                .tps(Double.NaN, Double.NaN, Double.NaN)
                .memory(-1L, -1L)
                .players(null)
                .build());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(png)));
    }

    @Test
    void bridgeRejectsInvalidMessageReferencesBeforeNetworkAccess() {
        BridgeClient client = new BridgeClient("http://127.0.0.1:1", "test-token-abcdefghijklmnopqrstuvwxyz");
        assertThrows(IllegalArgumentException.class, () -> client.reply(0L, "no", "❌"));
        assertThrows(IllegalArgumentException.class,
                () -> client.replyImage(-1L, "no", new byte[]{1}, "x.png", "❌"));
    }
}
