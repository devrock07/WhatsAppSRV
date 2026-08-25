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
    void advancementCardIncludesAvatarAndFitsBridgeLimit() throws Exception {
        BufferedImage head = ImageIO.read(new ByteArrayInputStream(
                PlayerHeadRenderer.renderPng("potatorkuja", null)));
        byte[] png = EventCardRenderer.renderAdvancement(new EventCardRenderer.AdvancementCard(
                "potatorkuja",
                "Bring Home the Beacon",
                "Completed the nether progression challenge.",
                "Nether",
                head));
        assertWhatsAppCard(png);
    }

    @Test
    void deathCardRendersRealTerrainSnapshotAndCoordinates() throws Exception {
        String[][] terrain = new String[11][11];
        for (int z = 0; z < terrain.length; z++) {
            for (int x = 0; x < terrain[z].length; x++) {
                terrain[z][x] = x < 4 ? "GRASS_BLOCK" : (z > 7 ? "WATER" : "STONE");
            }
        }
        BufferedImage head = ImageIO.read(new ByteArrayInputStream(
                PlayerHeadRenderer.renderPng("Kai_ymr", null)));
        byte[] png = EventCardRenderer.renderDeath(new EventCardRenderer.DeathCard(
                "Kai_ymr",
                "Kai_ymr was slain by a Zombie",
                "survival",
                "plains",
                "normal",
                -142, 64, 387,
                terrain,
                head));
        assertWhatsAppCard(png);
    }

    private static void assertWhatsAppCard(byte[] png) throws Exception {
        assertTrue(png.length > 8);
        assertTrue(png.length < 2 * 1024 * 1024, "WhatsApp bridge image limit");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded);
        assertEquals(EventCardRenderer.WIDTH, decoded.getWidth());
        assertEquals(EventCardRenderer.HEIGHT, decoded.getHeight());
    }

    @Test
    void bridgeRejectsInvalidMessageReferencesBeforeNetworkAccess() {
        BridgeClient client = new BridgeClient("http://127.0.0.1:1", "test-token-abcdefghijklmnopqrstuvwxyz");
        assertThrows(IllegalArgumentException.class, () -> client.reply(0L, "no", "❌"));
        assertThrows(IllegalArgumentException.class,
                () -> client.replyImage(-1L, "no", new byte[]{1}, "x.png", "❌"));
    }
}
