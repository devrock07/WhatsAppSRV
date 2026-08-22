package dev.codex.whatsappsrv;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders a WhatsApp-sized avatar from the player's real skin when available. */
final class PlayerHeadRenderer {
    private static final int OUTPUT_SIZE = 128;
    private static final int MAX_SKIN_BYTES = 1024 * 1024;
    private static final long CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final Pattern SKIN_URL = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"(https?[^\\\"]+)\\\"");
    private static final Map<String, CachedHead> CACHE = new ConcurrentHashMap<>();

    private PlayerHeadRenderer() {
    }

    /** Captures Bukkit profile data on the server thread. */
    static String findBukkitSkinUrl(Player player) {
        String modern = findModernBukkitSkin(player);
        if (modern != null) return modern;
        return findLegacyGameProfileSkin(player);
    }

    /** Captures only the optional plugin class loader on the server thread. */
    static ClassLoader findSkinsRestorerLoader(Player player) {
        Plugin plugin = player.getServer().getPluginManager().getPlugin("SkinsRestorer");
        return plugin != null && plugin.isEnabled() ? plugin.getClass().getClassLoader() : null;
    }

    static byte[] renderPng(String playerName, String skinUrl) throws IOException {
        String cacheKey = skinUrl == null || skinUrl.trim().isEmpty()
                ? "offline:" + String.valueOf(playerName).toLowerCase()
                : "skin:" + skinUrl;
        long now = System.currentTimeMillis();
        CachedHead cached = CACHE.get(cacheKey);
        if (cached != null && now - cached.createdAt < CACHE_MILLIS) return cached.png;

        BufferedImage head = null;
        if (skinUrl != null && !skinUrl.trim().isEmpty()) {
            try {
                head = cropHead(downloadSkin(skinUrl));
                if (head == null) throw new IOException("Skin image has unsupported dimensions");
            } catch (Exception error) {
                throw new IOException("Could not load the selected player skin", error);
            }
        }
        if (head == null) head = defaultHead(playerName);

        BufferedImage scaled = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.drawImage(head, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "png", output)) throw new IOException("PNG encoder is unavailable");
        byte[] png = output.toByteArray();
        if (CACHE.size() > 256) CACHE.clear();
        CACHE.put(cacheKey, new CachedHead(now, png));
        return png;
    }

    private static String findModernBukkitSkin(Player player) {
        try {
            Object profile = invokeNoArgs(player, "getPlayerProfile");
            Object textures = invokeNoArgs(profile, "getTextures");
            Object skin = invokeNoArgs(textures, "getSkin");
            return skin == null ? null : normalizeSkinUrl(String.valueOf(skin));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Uses the public SkinsRestorer v15 API without linking against its jar.
     * Loading classes through the SkinsRestorer plugin class loader also works
     * on servers that isolate plugin class paths more strictly.
     */
    static String findSkinsRestorerSkin(ClassLoader loader, UUID uuid, String playerName,
                                         boolean onlineMode) {
        if (loader == null) return null;
        try {
            Class<?> providerType = Class.forName(
                    "net.skinsrestorer.api.SkinsRestorerProvider", false, loader);
            Object api = providerType.getMethod("get").invoke(null);
            Class<?> apiType = Class.forName(
                    "net.skinsrestorer.api.SkinsRestorer", false, loader);
            Object storage = apiType.getMethod("getPlayerStorage").invoke(api);
            if (storage == null) return null;

            Object result = getSkinsRestorerProperty(storage, loader, uuid, playerName, onlineMode);
            Object property = unwrapOptional(result);
            if (property == null) return null;

            String fromUtility = null;
            try {
                fromUtility = getSkinsRestorerTextureUrl(property, loader);
            } catch (Exception utilityUnavailable) {
                // Continue with SkinProperty#getValue below.
            } catch (LinkageError utilityUnavailable) {
                // Continue with SkinProperty#getValue below.
            }
            if (fromUtility != null) return fromUtility;

            // Older v15 builds still expose the signed Base64 value even when
            // PropertyUtils is absent or its overload changed.
            Object encoded;
            try {
                encoded = invokeNoArgs(property, "getValue");
            } catch (Exception oldAccessorMissing) {
                encoded = invokeNoArgs(property, "value");
            }
            return encoded == null ? null : skinUrlFromBase64(String.valueOf(encoded));
        } catch (Exception ignored) {
            // Missing/incompatible API or a failed data request: use Bukkit's
            // already-applied profile instead of breaking the join event.
            return null;
        } catch (LinkageError ignored) {
            return null;
        }
    }

    private static Object getSkinsRestorerProperty(Object storage, ClassLoader loader,
                                                    UUID uuid, String name,
                                                    boolean onlineMode) throws Exception {
        Class<?> storageType = Class.forName(
                "net.skinsrestorer.api.storage.PlayerStorage", false, loader);
        // Prefer the already-linked property: it is exactly what
        // SkinsRestorer applied and does not need a data refresh.
        try {
            Method storedMethod = storageType.getMethod("getSkinOfPlayer", UUID.class);
            Object stored = storedMethod.invoke(storage, uuid);
            if (unwrapOptional(stored) != null) return stored;
        } catch (NoSuchMethodException olderApi) {
            // Continue with getSkinForPlayer below.
        }
        try {
            Method method = storageType.getMethod(
                    "getSkinForPlayer", UUID.class, String.class, boolean.class);
            return method.invoke(storage, uuid, name, onlineMode);
        } catch (NoSuchMethodException missingThreeArgumentApi) {
            Method method = storageType.getMethod("getSkinForPlayer", UUID.class, String.class);
            return method.invoke(storage, uuid, name);
        }
    }

    private static Object unwrapOptional(Object result) {
        if (result instanceof Optional) return ((Optional<?>) result).orElse(null);
        // Defensive compatibility with API implementations that return the
        // property directly rather than java.util.Optional.
        return result;
    }

    private static String getSkinsRestorerTextureUrl(Object property,
                                                     ClassLoader loader) throws Exception {
        Class<?> utilityType = Class.forName(
                "net.skinsrestorer.api.PropertyUtils", false, loader);
        for (Method method : utilityType.getMethods()) {
            if (!method.getName().equals("getSkinTextureUrl")
                    || method.getParameterTypes().length != 1
                    || !method.getParameterTypes()[0].isInstance(property)) continue;
            Object value = method.invoke(null, property);
            return value == null ? null : normalizeSkinUrl(String.valueOf(value));
        }
        return null;
    }

    private static String findLegacyGameProfileSkin(Player player) {
        try {
            Object profile = invokeNoArgs(player, "getProfile");
            Object properties = invokeNoArgs(profile, "getProperties");
            Method getter = null;
            for (Method method : properties.getClass().getMethods()) {
                if (method.getName().equals("get") && method.getParameterTypes().length == 1) {
                    getter = method;
                    break;
                }
            }
            if (getter == null) return null;
            Object textureProperties = getter.invoke(properties, "textures");
            if (!(textureProperties instanceof Iterable)) return null;
            for (Object property : (Iterable<?>) textureProperties) {
                Object encoded;
                try {
                    encoded = invokeNoArgs(property, "getValue");
                } catch (Exception oldAccessorMissing) {
                    encoded = invokeNoArgs(property, "value");
                }
                if (encoded == null) continue;
                String skinUrl = skinUrlFromBase64(String.valueOf(encoded));
                if (skinUrl != null) return skinUrl;
            }
        } catch (Exception ignored) {
            // Offline-mode players often have no signed textures property.
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        if (target == null) throw new NoSuchMethodException(name);
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static String skinUrlFromBase64(String encoded) {
        try {
            String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            Matcher matcher = SKIN_URL.matcher(json);
            return matcher.find()
                    ? normalizeSkinUrl(matcher.group(1).replace("\\/", "/"))
                    : null;
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }
    }

    private static String normalizeSkinUrl(String address) {
        if (address == null || address.trim().isEmpty()) return null;
        try {
            URL url = new URL(address.trim().replace("\\/", "/"));
            if (!url.getHost().equalsIgnoreCase("textures.minecraft.net")) return null;
            if (!url.getProtocol().equalsIgnoreCase("http")
                    && !url.getProtocol().equalsIgnoreCase("https")) return null;
            // Some signed texture properties still contain an http URL. The
            // Mojang texture CDN supports HTTPS, which the downloader requires.
            return new URL("https", "textures.minecraft.net", url.getFile()).toString();
        } catch (Exception invalidUrl) {
            return null;
        }
    }

    private static BufferedImage downloadSkin(String address) throws IOException {
        String normalized = normalizeSkinUrl(address);
        if (normalized == null) throw new IOException("Skin URL is not trusted");
        URL url = new URL(normalized);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "WhatsAppSRV-player-head/1.0");
        int status = connection.getResponseCode();
        if (status / 100 != 2) throw new IOException("Skin request returned HTTP " + status);

        byte[] data;
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_SKIN_BYTES) throw new IOException("Skin image is too large");
                output.write(buffer, 0, read);
            }
            data = output.toByteArray();
        } finally {
            connection.disconnect();
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) throw new IOException("Skin response is not an image");
        return image;
    }

    private static BufferedImage cropHead(BufferedImage skin) {
        if (skin.getWidth() < 64 || skin.getHeight() < 32) return null;
        int scale = Math.max(1, skin.getWidth() / 64);
        int faceSize = 8 * scale;
        if (skin.getHeight() < 16 * scale || skin.getWidth() < 48 * scale) return null;

        BufferedImage result = new BufferedImage(faceSize, faceSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(skin,
                    0, 0, faceSize, faceSize,
                    8 * scale, 8 * scale, 16 * scale, 16 * scale,
                    null);
            // The second layer contains hats, hair, helmets and other overlays.
            graphics.drawImage(skin,
                    0, 0, faceSize, faceSize,
                    40 * scale, 8 * scale, 48 * scale, 16 * scale,
                    null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** Uses a recognizable Steve/Alex head when no real texture is available. */
    private static BufferedImage defaultHead(String playerName) {
        boolean alex = (playerName == null ? 0 : playerName.toLowerCase().hashCode() & 1) != 0;
        Color face = new Color(alex ? 0xE7A47C : 0xB98565);
        Color faceShade = new Color(alex ? 0xC98262 : 0x9E684D);
        Color hair = new Color(alex ? 0xA84D28 : 0x342017);
        Color hairLight = new Color(alex ? 0xC96532 : 0x4A2E20);
        Color eye = new Color(alex ? 0x3B8A6B : 0x3B5FC0);
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(face);
            graphics.fillRect(0, 0, 8, 8);
            graphics.setColor(hair);
            graphics.fillRect(0, 0, 8, 2);
            graphics.fillRect(0, 2, 1, alex ? 6 : 3);
            graphics.fillRect(7, 2, 1, alex ? 6 : 3);
            graphics.setColor(hairLight);
            graphics.fillRect(alex ? 1 : 5, 1, 2, 1);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(1, 3, 2, 1);
            graphics.fillRect(5, 3, 2, 1);
            graphics.setColor(eye);
            graphics.fillRect(2, 3, 1, 1);
            graphics.fillRect(5, 3, 1, 1);
            graphics.setColor(faceShade);
            graphics.fillRect(3, 5, 2, 1);
            if (!alex) {
                // Steve's beard distinguishes the classic default head.
                graphics.setColor(hairLight);
                graphics.fillRect(1, 6, 1, 2);
                graphics.fillRect(6, 6, 1, 2);
                graphics.fillRect(2, 7, 4, 1);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static final class CachedHead {
        final long createdAt;
        final byte[] png;

        CachedHead(long createdAt, byte[] png) {
            this.createdAt = createdAt;
            this.png = png;
        }
    }
}
