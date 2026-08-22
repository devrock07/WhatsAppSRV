package dev.codex.whatsappsrv;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BridgeClient {
    private final String baseUrl;
    private final String token;
    private final Gson gson = new Gson();

    BridgeClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    void send(String text) throws IOException {
        sendPayload(Collections.<String, Object>singletonMap("text", text), 5000);
    }

    void sendImageUrl(String caption, String imageUrl) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", caption == null ? "" : caption);
        payload.put("mediaUrl", imageUrl);
        // Downloading the head and uploading it through WhatsApp takes longer
        // than a normal text message on a small Pterodactyl container.
        sendPayload(payload, 30000);
    }

    void sendImage(String caption, byte[] imageBytes, String mimeType, String filename) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) throw new IllegalArgumentException("imageBytes is empty");
        if (imageBytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("imageBytes exceeds 2 MiB");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", caption == null ? "" : caption);
        payload.put("mediaData", Base64.getEncoder().encodeToString(imageBytes));
        payload.put("mediaMimeType", mimeType == null ? "image/png" : mimeType);
        payload.put("mediaFilename", filename == null ? "minecraft-head.png" : filename);
        sendPayload(payload, 30000);
    }

    void reply(long referenceId, String text, String reaction) throws IOException {
        validateReferenceId(referenceId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceId", referenceId);
        payload.put("text", text == null ? "" : text);
        if (reaction != null) payload.put("reaction", reaction);
        postPayload("/reply", payload, 10000);
    }

    void replyImage(long referenceId, String caption, byte[] imageBytes, String filename, String reaction) throws IOException {
        validateReferenceId(referenceId);
        if (imageBytes == null || imageBytes.length == 0) throw new IllegalArgumentException("imageBytes is empty");
        if (imageBytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("imageBytes exceeds 2 MiB");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceId", referenceId);
        payload.put("text", caption == null ? "" : caption);
        payload.put("mediaData", Base64.getEncoder().encodeToString(imageBytes));
        payload.put("mediaMimeType", "image/png");
        payload.put("mediaFilename", filename == null ? "minecraft-status.png" : filename);
        if (reaction != null) payload.put("reaction", reaction);
        postPayload("/reply", payload, 30000);
    }

    private void validateReferenceId(long referenceId) {
        if (referenceId <= 0) throw new IllegalArgumentException("referenceId must be positive");
    }

    private void sendPayload(Map<String, Object> body, int readTimeoutMillis) throws IOException {
        postPayload("/send", body, readTimeoutMillis);
    }

    private void postPayload(String path, Map<String, Object> body, int readTimeoutMillis) throws IOException {
        HttpURLConnection connection = open(path, "POST");
        connection.setDoOutput(true);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] payload = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }
        requireSuccess(connection);
    }

    List<InboundMessage> messagesAfter(long id) throws IOException {
        String query = "/messages?after=" + URLEncoder.encode(Long.toString(id), "UTF-8");
        HttpURLConnection connection = open(query, "GET");
        requireSuccess(connection);
        try (InputStream input = connection.getInputStream(); InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            List<InboundMessage> messages = gson.fromJson(reader, new TypeToken<List<InboundMessage>>() {}.getType());
            return messages == null ? Collections.emptyList() : messages;
        }
    }

    Map<String, Object> health() throws IOException {
        HttpURLConnection connection = open("/health", "GET");
        requireSuccess(connection);
        try (InputStream input = connection.getInputStream(); InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
        }
    }

    List<ChatTarget> chats() throws IOException {
        HttpURLConnection connection = open("/chats", "GET");
        requireSuccess(connection);
        try (InputStream input = connection.getInputStream(); InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            List<ChatTarget> chats = gson.fromJson(reader, new TypeToken<List<ChatTarget>>() {}.getType());
            return chats == null ? Collections.emptyList() : chats;
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setUseCaches(false);
        return connection;
    }

    private void requireSuccess(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) return;
        InputStream errorStream = connection.getErrorStream();
        String detail = errorStream == null ? "" : read(errorStream);
        throw new IOException("Bridge returned HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
    }

    private String read(InputStream stream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    static final class InboundMessage {
        long id;
        long referenceId;
        String sender;
        String senderId;
        String text;
        String type;
        boolean isGroup;
        boolean isAdmin;
        boolean isSuperAdmin;
        boolean hasMedia;
        String mediaMimeType;
        String mediaFilename;
        long timestamp;
    }

    static final class ChatTarget {
        String id;
        String name;
        boolean isGroup;
    }
}
