package dev.codex.whatsappsrv;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

/** Handles commands received from the configured WhatsApp chat on the server thread. */
final class InboundCommandHandler {
    private static final String CONFIG_ROOT = "whatsapp-commands";
    private static final Pattern MINECRAFT_USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final WhatsAppSRVPlugin plugin;
    private final Responder responder;
    private final Map<String, Long> lastCommandMillis = new HashMap<>();

    interface Responder {
        void reply(BridgeClient.InboundMessage source, String text, boolean success);

        void status(BridgeClient.InboundMessage source);
    }

    InboundCommandHandler(WhatsAppSRVPlugin plugin, Responder responder) {
        this.plugin = plugin;
        this.responder = responder;
    }

    void handle(BridgeClient.InboundMessage message) {
        String commandLine = cleanCommandLine(message.text);
        if (commandLine == null) return;

        if (!plugin.getConfig().getBoolean(CONFIG_ROOT + ".enabled", true)) {
            reply(message, "WhatsApp commands are disabled on this server.", false);
            return;
        }

        int maxLength = Math.max(32, plugin.getConfig().getInt(CONFIG_ROOT + ".max-command-length", 256));
        if (commandLine.length() > maxLength) {
            reply(message, "That command is too long.", false);
            return;
        }

        String commandName = firstWord(commandLine).toLowerCase(Locale.ROOT);

        long cooldownSeconds = Math.min(300L, Math.max(0L,
                plugin.getConfig().getLong(CONFIG_ROOT + ".cooldown-seconds", 2L)));
        long cooldownMillis = cooldownSeconds * 1000L;
        String senderKey = cleanSingleLine(message.senderId).toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long previous = lastCommandMillis.get(senderKey);
        if (previous != null && now - previous < cooldownMillis) {
            long remaining = Math.max(1L, (cooldownMillis - (now - previous) + 999L) / 1000L);
            reply(message, "Please wait " + remaining + "s before sending another command.", false);
            return;
        }
        lastCommandMillis.put(senderKey, now);

        if (!senderAllowed(message.senderId, plugin.getConfig().getStringList(CONFIG_ROOT + ".allowed-sender-ids"), true)) {
            plugin.getLogger().warning("Blocked WhatsApp command from sender "
                    + cleanSingleLine(message.senderId) + ": /" + commandName);
            reply(message, "You are not allowed to run Minecraft commands from WhatsApp.", false);
            return;
        }

        switch (commandName) {
            case "help":
            case "commands":
                sendHelp(message);
                return;
            case "players":
            case "list":
            case "online":
                sendPlayers(message);
                return;
            case "status":
                responder.status(message);
                return;
            case "tps":
                sendTps(message);
                return;
            case "version":
                reply(message, "Minecraft server version: " + Bukkit.getVersion(), true);
                return;
            case "ping":
                reply(message, "Pong! The Minecraft server is online with " + Bukkit.getOnlinePlayers().size()
                        + "/" + Bukkit.getMaxPlayers() + " players.", true);
                return;
            case "about":
            case "credits":
                reply(message, "*WhatsAppSRV v" + plugin.getDescription().getVersion()
                        + "*\nMinecraft <-> WhatsApp Bridge\nMade by *DevRock*\n"
                        + "github.com/devrock07/WhatsAppSRV", true);
                return;
            case "whitelist":
                handleWhitelist(message, commandLine);
                return;
            default:
                runAllowlistedConsoleCommand(message, commandLine);
        }
    }

    private void sendHelp(BridgeClient.InboundMessage message) {
        StringBuilder help = new StringBuilder("Minecraft commands:\n")
                .append("/players - online count and player names\n")
                .append("/status - graphical server status card\n")
                .append("/tps - Paper server tick rate\n")
                .append("/version - Minecraft server version\n")
                .append("/ping - check whether the bridge is responding\n")
                .append("/about - WhatsAppSRV version and credits\n")
                .append("/help - show this help");

        if (hasAdminAccess(message)) {
            help.append("\n\nAdmin whitelist:\n")
                    .append("/whitelist add <username>\n")
                    .append("/whitelist remove <username>\n")
                    .append("/whitelist list");
        }

        if (canUseConsoleCommands(message)) {
            List<String> allowed = configuredConsoleCommands();
            if (!allowed.isEmpty()) {
                help.append("\nAdmin allowlist: /").append(String.join(", /", allowed));
            }
        }
        reply(message, help.toString(), true);
    }

    private void sendPlayers(BridgeClient.InboundMessage message) {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<String> names = new ArrayList<>();
        for (Player player : online) names.add(cleanSingleLine(player.getName()));
        names.sort(String.CASE_INSENSITIVE_ORDER);

        String heading = "🎮 *Players online — " + names.size() + "/" + Bukkit.getMaxPlayers() + "*";
        if (names.isEmpty()) {
            reply(message, heading + "\nNobody is online right now.", true);
            return;
        }

        int maximum = Math.max(1, plugin.getConfig().getInt(CONFIG_ROOT + ".max-player-names", 30));
        StringBuilder response = new StringBuilder(heading);
        int shown = Math.min(maximum, names.size());
        for (int index = 0; index < shown; index++) response.append("\n• ").append(names.get(index));
        if (shown < names.size()) response.append("\n…and ").append(names.size() - shown).append(" more");
        reply(message, response.toString(), true);
    }

    private void sendTps(BridgeClient.InboundMessage message) {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getTPS");
            double[] tps = (double[]) method.invoke(Bukkit.getServer());
            if (tps == null || tps.length < 3) throw new IllegalStateException("TPS values unavailable");
            reply(message, String.format(Locale.US,
                    "📈 *Server TPS*\n1m: %.2f\n5m: %.2f\n15m: %.2f",
                    Math.min(20.0, tps[0]), Math.min(20.0, tps[1]), Math.min(20.0, tps[2])), true);
        } catch (Exception error) {
            reply(message, "TPS is available on Paper servers; this server implementation did not expose it.", false);
        }
    }

    private void handleWhitelist(BridgeClient.InboundMessage message, String commandLine) {
        if (!hasAdminAccess(message)) {
            plugin.getLogger().warning("Blocked WhatsApp whitelist command from sender "
                    + cleanSingleLine(message.senderId));
            reply(message, "Whitelist management is limited to WhatsApp group admins. "
                    + "For a DM, add your sender ID to whatsapp-commands.admin-sender-ids.", false);
            return;
        }

        String[] parts = commandLine.split(" ");
        if (parts.length == 2 && parts[1].equalsIgnoreCase("list")) {
            sendWhitelist(message);
            return;
        }
        if (parts.length != 3
                || (!parts[1].equalsIgnoreCase("add") && !parts[1].equalsIgnoreCase("remove"))) {
            reply(message, "Usage:\n/whitelist add <username>\n/whitelist remove <username>\n/whitelist list", false);
            return;
        }

        String username = parts[2];
        if (!MINECRAFT_USERNAME.matcher(username).matches()) {
            reply(message, "Invalid Minecraft username. Use 3-16 letters, numbers, or underscores.", false);
            return;
        }

        OfflinePlayer player = findWhitelistedPlayer(username);
        if (player == null) player = Bukkit.getOfflinePlayer(username);
        boolean add = parts[1].equalsIgnoreCase("add");
        if (player.isWhitelisted() == add) {
            reply(message, "*" + cleanSingleLine(username) + "* is already "
                    + (add ? "on" : "off") + " the whitelist.", true);
            return;
        }

        try {
            player.setWhitelisted(add);
            plugin.getLogger().info("WhatsApp group admin " + cleanSingleLine(message.senderId)
                    + (add ? " added " : " removed ") + username + (add ? " to" : " from") + " the whitelist.");
            reply(message, (add ? "✅ Added *" : "✅ Removed *") + cleanSingleLine(username)
                    + (add ? "* to the whitelist." : "* from the whitelist."), true);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not update whitelist for " + username, error);
            reply(message, "The whitelist could not be updated. Check the Minecraft server console.", false);
        }
    }

    private void sendWhitelist(BridgeClient.InboundMessage message) {
        List<String> names = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getWhitelistedPlayers()) {
            String name = cleanSingleLine(player.getName());
            if (!name.isEmpty()) names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) {
            reply(message, "📋 *Whitelist*\nThe whitelist is empty.", true);
            return;
        }
        int maximum = Math.max(1, plugin.getConfig().getInt(CONFIG_ROOT + ".max-player-names", 30));
        StringBuilder response = new StringBuilder("📋 *Whitelist — ").append(names.size()).append(" players*");
        int shown = Math.min(maximum, names.size());
        for (int index = 0; index < shown; index++) response.append("\n• ").append(names.get(index));
        if (shown < names.size()) response.append("\n…and ").append(names.size() - shown).append(" more");
        reply(message, response.toString(), true);
    }

    private OfflinePlayer findWhitelistedPlayer(String username) {
        for (OfflinePlayer player : Bukkit.getWhitelistedPlayers()) {
            String existingName = cleanSingleLine(player.getName());
            if (existingName.equalsIgnoreCase(username)) return player;
        }
        return null;
    }

    private void runAllowlistedConsoleCommand(BridgeClient.InboundMessage message, String commandLine) {
        if (!plugin.getConfig().getBoolean(CONFIG_ROOT + ".console.enabled", false)) {
            unknownCommand(message);
            return;
        }

        if (!hasAdminAccess(message)) {
            plugin.getLogger().warning("Blocked remote console command from non-admin sender "
                    + cleanSingleLine(message.senderId) + ": /" + firstWord(commandLine));
            reply(message, "This console command requires WhatsApp group-admin access.", false);
            return;
        }

        List<String> consoleSenders = plugin.getConfig().getStringList(CONFIG_ROOT + ".console.allowed-sender-ids");
        if (!senderAllowed(message.senderId, consoleSenders, false)) {
            plugin.getLogger().warning("Blocked remote console command from sender "
                    + cleanSingleLine(message.senderId) + ": /" + firstWord(commandLine));
            reply(message, "This command requires an explicitly allowlisted admin sender ID.", false);
            return;
        }

        String normalized = normalizeCommand(commandLine);
        boolean allowlisted = false;
        for (String configured : configuredConsoleCommands()) {
            if (normalizeCommand(configured).equals(normalized)) {
                allowlisted = true;
                break;
            }
        }
        if (!allowlisted) {
            reply(message, "That console command is not allowlisted. Send /help for available commands.", false);
            return;
        }

        CapturedConsole captured = new CapturedConsole(Bukkit.getConsoleSender());
        try {
            boolean dispatched = Bukkit.dispatchCommand(captured.sender(), commandLine);
            String output = captured.output();
            if (!output.isEmpty()) {
                reply(message, output, dispatched);
            } else if (dispatched) {
                reply(message, "Command executed successfully: /" + commandLine, true);
            } else {
                reply(message, "The server could not execute /" + commandLine + ".", false);
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Allowlisted WhatsApp command failed: /" + commandLine, error);
            reply(message, "The command failed. Check the Minecraft server console for details.", false);
        }
    }

    private void unknownCommand(BridgeClient.InboundMessage message) {
        reply(message, "Unknown command. Send /help for available Minecraft commands.", false);
    }

    private boolean hasAdminAccess(BridgeClient.InboundMessage message) {
        if (message.isGroup && (message.isAdmin || message.isSuperAdmin)) return true;
        return senderAllowed(message.senderId,
                plugin.getConfig().getStringList(CONFIG_ROOT + ".admin-sender-ids"), false);
    }

    private boolean canUseConsoleCommands(BridgeClient.InboundMessage message) {
        return plugin.getConfig().getBoolean(CONFIG_ROOT + ".console.enabled", false)
                && hasAdminAccess(message)
                && senderAllowed(message.senderId,
                plugin.getConfig().getStringList(CONFIG_ROOT + ".console.allowed-sender-ids"), false);
    }

    private List<String> configuredConsoleCommands() {
        List<String> result = new ArrayList<>();
        for (String entry : plugin.getConfig().getStringList(CONFIG_ROOT + ".console.allowlist")) {
            String cleaned = cleanCommandLine(entry.startsWith("/") ? entry : "/" + entry);
            if (cleaned != null && !cleaned.isEmpty()) result.add(cleaned);
        }
        return result;
    }

    private boolean senderAllowed(String actualId, List<String> configuredIds, boolean emptyAllowsAll) {
        if (configuredIds == null || configuredIds.isEmpty()) return emptyAllowsAll;
        String actual = cleanSingleLine(actualId).toLowerCase(Locale.ROOT);
        String actualUser = actual.contains("@") ? actual.substring(0, actual.indexOf('@')) : actual;
        String actualDigits = digitsOnly(actualUser);
        for (String configuredId : configuredIds) {
            String configured = cleanSingleLine(configuredId).toLowerCase(Locale.ROOT);
            if (configured.equals(actual)) return true;
            if (!configured.contains("@") && !actualDigits.isEmpty() && digitsOnly(configured).equals(actualDigits)) return true;
        }
        return false;
    }

    private void reply(BridgeClient.InboundMessage source, String message, boolean success) {
        String cleaned = cleanResponse(message);
        int limit = Math.max(200, Math.min(10000,
                plugin.getConfig().getInt(CONFIG_ROOT + ".max-response-characters", 2000)));
        if (cleaned.length() > limit) cleaned = cleaned.substring(0, limit - 18) + "\n...output shortened";
        String format = plugin.getConfig().getString(CONFIG_ROOT + ".reply-format", "🤖 *Minecraft:*\n%message%");
        responder.reply(source, format.replace("%message%", cleaned), success);
    }

    private String cleanCommandLine(String value) {
        String cleaned = cleanSingleLine(value);
        if (!cleaned.startsWith("/")) return null;
        cleaned = cleaned.substring(1).trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? "help" : cleaned;
    }

    private String firstWord(String value) {
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private String normalizeCommand(String value) {
        String cleaned = cleanSingleLine(value);
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        // Preserve argument case: some plugins treat tokens or identifiers as
        // case-sensitive, so changing case must not pass an "exact" allowlist.
        return cleaned.trim().replaceAll("\\s+", " ");
    }

    private String cleanSingleLine(String value) {
        if (value == null) return "";
        String stripped = ChatColor.stripColor(value);
        return (stripped == null ? "" : stripped).replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String cleanResponse(String value) {
        if (value == null) return "";
        String stripped = ChatColor.stripColor(value);
        if (stripped == null) return "";
        return stripped.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    /** Best-effort console output capture; commands that log directly may still only appear in the server console. */
    private static final class CapturedConsole {
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private final ConsoleCommandSender sender;

        CapturedConsole(ConsoleCommandSender delegate) {
            this.sender = (ConsoleCommandSender) Proxy.newProxyInstance(
                    ConsoleCommandSender.class.getClassLoader(),
                    new Class<?>[]{ConsoleCommandSender.class},
                    (proxy, method, args) -> invoke(delegate, method, args));
        }

        ConsoleCommandSender sender() {
            return sender;
        }

        String output() {
            synchronized (lines) {
                return cleanCapturedLines(lines);
            }
        }

        private Object invoke(ConsoleCommandSender delegate, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("sendMessage")) {
                captureMessageArguments(args);
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }

        private void captureMessageArguments(Object[] args) {
            if (args == null) return;
            for (Object argument : args) {
                if (argument instanceof String) {
                    addLine((String) argument);
                } else if (argument instanceof String[]) {
                    for (String line : (String[]) argument) addLine(line);
                }
            }
        }

        private void addLine(String value) {
            if (value == null || value.isEmpty()) return;
            String stripped = ChatColor.stripColor(value);
            if (stripped == null) return;
            for (String line : stripped.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                if (!line.trim().isEmpty()) lines.add(line.trim());
            }
        }

        private String cleanCapturedLines(List<String> captured) {
            return String.join("\n", captured);
        }
    }
}
