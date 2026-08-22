package dev.codex.whatsappsrv;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class WhatsAppSRVPlugin extends JavaPlugin implements Listener {
    private volatile BridgeClient bridge;
    private BridgeProcessManager processManager;
    private InboundCommandHandler inboundCommands;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final Map<String, String> recentWhatsAppSenders = new ConcurrentHashMap<>();
    private volatile List<BridgeClient.ChatTarget> lastListedChats = Collections.emptyList();
    private volatile long lastInboundId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateOlderConfig();
        ensureApiToken();
        rebuildClient();
        inboundCommands = new InboundCommandHandler(this, new InboundCommandHandler.Responder() {
            @Override
            public void reply(BridgeClient.InboundMessage source, String text, boolean success) {
                sendCommandReplyAsync(source, text, success);
            }

            @Override
            public void status(BridgeClient.InboundMessage source) {
                sendStatusCard(source);
            }
        });
        processManager = new BridgeProcessManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().isPluginEnabled("SkinsRestorer")) {
            getLogger().info("SkinsRestorer detected; join/leave heads will use its selected player skins.");
        }

        long period = Math.max(1L, getConfig().getLong("poll-interval-seconds", 2L)) * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::pollMessages, period, period);

        if (getConfig().getBoolean("runtime.auto-start", true)) {
            getServer().getScheduler().runTaskAsynchronously(this, processManager::start);
        }
        if (enabled("forward.server-status")) {
            getServer().getScheduler().runTaskLater(this, () -> sendConfigured("formats.server-start"), 20L * 45L);
        }
        printStartupBanner();
        getLogger().info("WhatsAppSRV enabled. Use /whatsappsrv status to test the local bridge.");
    }

    @Override
    public void onDisable() {
        if (enabled("forward.server-status")) {
            try {
                bridge.send(format("formats.server-stop"));
            } catch (Exception error) {
                getLogger().log(Level.WARNING, "Could not send the shutdown message", error);
            }
        }
        if (processManager != null) processManager.stop();
    }

    private void ensureApiToken() {
        String token = getConfig().getString("api-token", "");
        if (!token.isEmpty() && !token.equals("GENERATE_AUTOMATICALLY") && !token.contains("replace-with")) return;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        getConfig().set("api-token", Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        saveConfig();
        getLogger().info("Generated a private bridge API token in config.yml.");
    }

    private void migrateOlderConfig() {
        boolean changed = false;
        if ("🟢 *%player% joined the server*".equals(getConfig().getString("formats.join"))) {
            getConfig().set("formats.join", "🟢 *%player% joined the server*\n👥 Online: *%online%/%max_players%*");
            changed = true;
        }
        if ("🔴 *%player% left the server*".equals(getConfig().getString("formats.leave"))) {
            getConfig().set("formats.leave", "🔴 *%player% left the server*\n👥 Online: *%online%/%max_players%*");
            changed = true;
        }
        if (changed) saveConfig();
    }

    private void rebuildClient() {
        reloadConfig();
        String url = getConfig().getString("bridge-url", "http://127.0.0.1:3210");
        String token = getConfig().getString("api-token", "");
        bridge = new BridgeClient(url, token);
        if (token.length() < 24 || token.contains("replace-with") || token.equals("GENERATE_AUTOMATICALLY")) {
            getLogger().severe("Set the same private api-token (24+ characters) in plugin config.yml and bridge/config.json.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!enabled("forward.minecraft-chat")) return;
        String message = format("formats.minecraft-chat")
                .replace("%player%", safe(event.getPlayer().getDisplayName()))
                .replace("%message%", safe(event.getMessage()));
        sendAsync(message);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (enabled("forward.joins")) {
            sendPlayerFormat("formats.join", event.getPlayer(), Bukkit.getOnlinePlayers().size(), "player-heads.joins");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (enabled("forward.leaves")) {
            // PlayerQuitEvent normally fires while the departing player is
            // still in Bukkit's online collection.
            int remaining = Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
            sendPlayerFormat("formats.leave", event.getPlayer(), remaining, "player-heads.leaves");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled("forward.deaths") || event.getDeathMessage() == null) return;
        sendAsync(format("formats.death").replace("%message%", safe(event.getDeathMessage())));
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!enabled("forward.advancements")) return;
        Advancement advancement = event.getAdvancement();
        String key = advancement.getKey().getKey();
        if (key.startsWith("recipes/")) return;
        sendAsync(format("formats.advancement")
                .replace("%player%", safe(event.getPlayer().getDisplayName()))
                .replace("%advancement%", safe(key.replace('/', ' '))));
    }

    private void sendPlayerFormat(String path, Player player, int onlineCount, String avatarToggle) {
        String playerName = safe(player.getDisplayName());
        String caption = format(path)
                .replace("%player%", playerName)
                .replace("%online%", Integer.toString(onlineCount))
                .replace("%max_players%", Integer.toString(Bukkit.getMaxPlayers()));
        if (!getConfig().getBoolean("player-heads.enabled", true)
                || !getConfig().getBoolean(avatarToggle, true)) {
            sendAsync(caption);
            return;
        }

        // Capture Bukkit-only state now. SkinsRestorer storage access can
        // refresh data, so that lookup runs asynchronously with the image work.
        String bukkitSkinUrl = PlayerHeadRenderer.findBukkitSkinUrl(player);
        ClassLoader skinsRestorerLoader = PlayerHeadRenderer.findSkinsRestorerLoader(player);
        UUID playerId = player.getUniqueId();
        String accountName = player.getName();
        boolean onlineMode = Bukkit.getOnlineMode();
        sendPlayerHeadAsync(caption, playerName, bukkitSkinUrl, skinsRestorerLoader,
                playerId, accountName, onlineMode);
    }

    private void sendConfigured(String path) {
        sendAsync(format(path));
    }

    private void sendAsync(String text) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                bridge.send(text);
            } catch (IOException error) {
                getLogger().warning("WhatsApp send failed: " + error.getMessage());
            }
        });
    }

    private void sendCommandReplyAsync(BridgeClient.InboundMessage source, String text, boolean success) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                bridge.reply(source.referenceId, text, success ? "✅" : "❌");
            } catch (Exception replyError) {
                // A reference can expire after a bridge restart; do not lose a
                // useful command response just because it can no longer quote.
                getLogger().fine("Could not reply to the original WhatsApp message: " + replyError.getMessage());
                try {
                    bridge.send(text);
                } catch (IOException sendError) {
                    getLogger().warning("WhatsApp command reply failed: " + sendError.getMessage());
                }
            }
        });
    }

    /** Captures Bukkit state on the server thread, then performs image/network work asynchronously. */
    private void sendStatusCard(BridgeClient.InboundMessage source) {
        int headLimit = Math.max(0, Math.min(10, getConfig().getInt("status-card.max-player-heads", 10)));
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        onlinePlayers.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        List<StatusPlayer> statusPlayers = new ArrayList<>();
        for (int index = 0; index < Math.min(headLimit, onlinePlayers.size()); index++) {
            Player player = onlinePlayers.get(index);
            statusPlayers.add(new StatusPlayer(
                    safe(player.getName()),
                    PlayerHeadRenderer.findBukkitSkinUrl(player),
                    PlayerHeadRenderer.findSkinsRestorerLoader(player),
                    player.getUniqueId(),
                    player.getName()
            ));
        }

        String configuredName = safe(getConfig().getString("status-card.server-name", ""));
        String serverMotd = safe(Bukkit.getMotd());
        String serverName = configuredName.isEmpty()
                ? (serverMotd.isEmpty() ? "Minecraft Server" : serverMotd)
                : configuredName;
        String subtitle = configuredName.isEmpty() || serverMotd.isEmpty()
                ? "Live Minecraft server overview"
                : serverMotd;
        double[] tps = currentTps();
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        long maxMemory = Math.max(0L, runtime.maxMemory());
        long uptimeMillis = Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime());
        File serverIcon = new File(Bukkit.getWorldContainer(), "server-icon.png").getAbsoluteFile();
        StatusCapture capture = new StatusCapture(
                serverName,
                subtitle,
                safe(Bukkit.getVersion()),
                formatDuration(uptimeMillis),
                onlinePlayers.size(),
                Bukkit.getMaxPlayers(),
                tps,
                usedMemory,
                maxMemory,
                getConfig().getBoolean("status-card.show-server-icon", true) ? serverIcon : null,
                statusPlayers,
                Bukkit.getOnlineMode()
        );

        getServer().getScheduler().runTaskAsynchronously(this, () -> renderAndReplyStatus(source, capture));
    }

    private void renderAndReplyStatus(BridgeClient.InboundMessage source, StatusCapture capture) {
        try {
            BufferedImage serverIcon = null;
            if (capture.serverIcon != null && capture.serverIcon.isFile()
                    && capture.serverIcon.length() <= 2L * 1024L * 1024L) {
                serverIcon = ImageIO.read(capture.serverIcon);
            }

            List<StatusCardRenderer.PlayerEntry> entries = new ArrayList<>();
            for (StatusPlayer player : capture.players) {
                BufferedImage head = null;
                try {
                    String restoredUrl = PlayerHeadRenderer.findSkinsRestorerSkin(
                            player.skinsRestorerLoader, player.uuid, player.accountName, capture.onlineMode);
                    String skinUrl = restoredUrl == null ? player.bukkitSkinUrl : restoredUrl;
                    byte[] headPng;
                    try {
                        headPng = PlayerHeadRenderer.renderPng(player.name, skinUrl);
                    } catch (IOException selectedSkinError) {
                        headPng = PlayerHeadRenderer.renderPng(player.name, null);
                    }
                    head = ImageIO.read(new ByteArrayInputStream(headPng));
                } catch (Exception headError) {
                    getLogger().fine("Could not render status head for " + player.name + ": " + headError.getMessage());
                }
                entries.add(new StatusCardRenderer.PlayerEntry(player.name, head));
            }

            byte[] png = StatusCardRenderer.renderPng(StatusCardRenderer.Snapshot.builder()
                    .serverName(capture.serverName)
                    .motd(capture.motd)
                    .serverVersion(capture.serverVersion)
                    .uptime(capture.uptime)
                    .playersOnline(capture.onlinePlayers, capture.maxPlayers)
                    .tps(capture.tps[0], capture.tps[1], capture.tps[2])
                    .memory(capture.usedMemory, capture.maxMemory)
                    .serverIcon(serverIcon)
                    .players(entries)
                    .build());
            String caption = "🟢 *" + capture.serverName + "* — live server status";
            try {
                bridge.replyImage(source.referenceId, caption, png, "whatsappsrv-status.png", "✅");
            } catch (Exception replyError) {
                getLogger().fine("Could not attach the status card to its command message: " + replyError.getMessage());
                bridge.sendImage(caption, png, "image/png", "whatsappsrv-status.png");
            }
        } catch (Exception error) {
            getLogger().log(Level.WARNING, "Could not render the WhatsApp status card", error);
            sendCommandReplyAsync(source,
                    "🤖 *Minecraft:*\nThe server is online, but its status card could not be rendered. Try /players or /tps.",
                    false);
        }
    }

    private double[] currentTps() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getTPS");
            double[] values = (double[]) method.invoke(Bukkit.getServer());
            if (values != null && values.length >= 3) {
                return new double[]{Math.min(20.0, values[0]), Math.min(20.0, values[1]), Math.min(20.0, values[2])};
            }
        } catch (Exception ignored) {
            // Spigot does not expose TPS. Paper servers do.
        }
        return new double[]{Double.NaN, Double.NaN, Double.NaN};
    }

    private String formatDuration(long millis) {
        long totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes / 60L) % 24L;
        long minutes = totalMinutes % 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1L, minutes) + "m";
    }

    private void sendPlayerHeadAsync(String caption, String playerName, String bukkitSkinUrl,
                                     ClassLoader skinsRestorerLoader, UUID playerId,
                                     String accountName, boolean onlineMode) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String restoredSkinUrl = PlayerHeadRenderer.findSkinsRestorerSkin(
                        skinsRestorerLoader, playerId, accountName, onlineMode);
                String skinUrl = restoredSkinUrl == null ? bukkitSkinUrl : restoredSkinUrl;
                byte[] head;
                try {
                    head = PlayerHeadRenderer.renderPng(playerName, skinUrl);
                } catch (IOException skinError) {
                    getLogger().warning("Could not download the selected skin for " + playerName
                            + "; using a Steve/Alex head: " + skinError.getMessage());
                    head = PlayerHeadRenderer.renderPng(playerName, null);
                }
                String filename = playerName.replaceAll("[^A-Za-z0-9._-]", "_") + "-head.png";
                bridge.sendImage(caption, head, "image/png", filename);
            } catch (Exception imageError) {
                getLogger().warning("Could not send the player-head image for " + playerName
                        + "; sending text instead: " + imageError.getMessage());
                try {
                    bridge.send(caption);
                } catch (IOException textError) {
                    getLogger().warning("WhatsApp fallback send failed: " + textError.getMessage());
                }
            }
        });
    }

    private void pollMessages() {
        boolean relayEnabled = enabled("forward.whatsapp-to-minecraft");
        boolean commandsEnabled = getConfig().getBoolean("whatsapp-commands.enabled", true);
        if ((!relayEnabled && !commandsEnabled) || !polling.compareAndSet(false, true)) return;
        try {
            List<BridgeClient.InboundMessage> messages = bridge.messagesAfter(lastInboundId);
            for (BridgeClient.InboundMessage message : messages) {
                lastInboundId = Math.max(lastInboundId, message.id);
                String senderId = safe(message.senderId);
                if (!senderId.isEmpty()) recentWhatsAppSenders.put(senderId, safe(message.sender));
                String inboundText = truncateCodePoints(safe(message.text), Math.max(32,
                        getConfig().getInt("incoming.max-message-codepoints", 512)));
                if (inboundText.startsWith("/")) {
                    Bukkit.getScheduler().runTask(this, () -> inboundCommands.handle(message));
                    continue;
                }
                if (!relayEnabled) continue;
                // Apply color codes only to the trusted format. A WhatsApp
                // sender typing "&c" must not inject Minecraft formatting.
                String rendered = color(format("formats.whatsapp-chat"))
                        .replace("%sender%", safe(message.sender))
                        .replace("%message%", inboundText);
                Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcastMessage(rendered));
            }
        } catch (IOException error) {
            getLogger().fine("WhatsApp poll failed: " + error.getMessage());
        } finally {
            polling.set(false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&eUsage: /" + label + " <status|chats|select|senders|test|reload|credits>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("credits") || args[0].equalsIgnoreCase("about")) {
            sendBranding(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("senders")) {
            if (recentWhatsAppSenders.isEmpty()) {
                sender.sendMessage(color("&eNo WhatsApp senders recorded yet. Ask the admin to send a message, then try again."));
                return true;
            }
            sender.sendMessage(color("&eRecent WhatsApp senders (use these IDs only in the private server config):"));
            recentWhatsAppSenders.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                    .limit(25)
                    .forEach(entry -> sender.sendMessage(color("&f" + entry.getValue() + " &7- " + entry.getKey())));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            // Pick up manual file edits before validating/generating secrets.
            reloadConfig();
            ensureApiToken();
            rebuildClient();
            if (processManager != null && getConfig().getBoolean("runtime.auto-start", true)) {
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    processManager.stop();
                    processManager.start();
                });
            }
            sender.sendMessage(color("&aWhatsAppSRV configuration reloaded; bridge restarting."));
            return true;
        }

        if (args[0].equalsIgnoreCase("test")) {
            sendAsync("✅ WhatsAppSRV test from " + safe(sender.getName()));
            sender.sendMessage(color("&aTest message queued."));
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(color("&eRuntime: &f" + (processManager == null ? "external" : processManager.state())));
            sender.sendMessage(color("&eChecking WhatsApp connection..."));
            String configuredName = safe(getConfig().getString("target-chat-name", ""));
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    Map<String, Object> health = bridge.health();
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(color("&aBridge reachable. WhatsApp ready: &f" + health.get("ready")
                                + "&a, target configured: &f" + health.get("targetConfigured")));
                        if (!configuredName.isEmpty()) {
                            sender.sendMessage(color("&aSelected WhatsApp chat: &f" + configuredName));
                        }
                        Object diagnostic = health.get("diagnostic");
                        if (diagnostic != null) sender.sendMessage(color("&7Bridge detail: " + diagnostic));
                    });
                } catch (IOException error) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(color("&cBridge error: " + error.getMessage())));
                }
            });
            return true;
        }

        if (args[0].equalsIgnoreCase("chats")) {
            sender.sendMessage(color("&eLoading WhatsApp groups and DMs..."));
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    List<BridgeClient.ChatTarget> targets = new ArrayList<>(bridge.chats());
                    targets.sort((left, right) -> {
                        if (left.isGroup != right.isGroup) return left.isGroup ? -1 : 1;
                        return chatName(left).compareToIgnoreCase(chatName(right));
                    });
                    if (targets.size() > 50) targets = new ArrayList<>(targets.subList(0, 50));
                    lastListedChats = Collections.unmodifiableList(new ArrayList<>(targets));
                    List<String> lines = new ArrayList<>();
                    for (int index = 0; index < targets.size(); index++) {
                        BridgeClient.ChatTarget target = targets.get(index);
                        lines.add(color("&f" + (index + 1) + ". "
                                + (target.isGroup ? "&aGROUP: &f" : "&bDM: &f")
                                + chatName(target)));
                    }
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (lines.isEmpty()) sender.sendMessage(color("&cNo chats are available yet."));
                        else lines.forEach(sender::sendMessage);
                        if (!lines.isEmpty()) {
                            sender.sendMessage(color("&eChoose by number: &f/wasrv select <number> &7(example: /wasrv select 1)"));
                        }
                    });
                } catch (IOException error) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(color("&cCould not list chats: " + error.getMessage())));
                }
            });
            return true;
        }

        if (args[0].equalsIgnoreCase("select")) {
            if (args.length < 2) {
                sender.sendMessage(color("&eFirst run &f/wasrv chats&e, then use &f/wasrv select <number>&e."));
                return true;
            }

            final int selectedIndex;
            try {
                selectedIndex = Integer.parseInt(args[1]) - 1;
            } catch (NumberFormatException error) {
                sender.sendMessage(color("&cThat is not a valid number. Example: /wasrv select 1"));
                return true;
            }

            List<BridgeClient.ChatTarget> listed = lastListedChats;
            if (selectedIndex < 0 || selectedIndex >= listed.size()) {
                sender.sendMessage(color("&cThat number is not in the latest list. Run /wasrv chats again."));
                return true;
            }

            BridgeClient.ChatTarget selected = listed.get(selectedIndex);
            String selectedName = chatName(selected);
            getConfig().set("target-chat-id", selected.id);
            getConfig().set("target-chat-name", selectedName);
            saveConfig();
            sender.sendMessage(color("&aSelected " + (selected.isGroup ? "group" : "DM") + ": &f" + selectedName));
            sender.sendMessage(color("&eRestarting the WhatsApp bridge to apply it..."));
            if (processManager != null) {
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    processManager.stop();
                    processManager.start();
                });
            }
            return true;
        }

        sender.sendMessage(color("&cUnknown subcommand."));
        return true;
    }

    private void printStartupBanner() {
        if (!getConfig().getBoolean("console-banner.enabled", true)) return;
        sendBranding(Bukkit.getConsoleSender());
    }

    private void sendBranding(CommandSender sender) {
        for (ConsoleBranding.Line line : ConsoleBranding.startupLines(getDescription().getVersion())) {
            ChatColor color;
            switch (line.style) {
                case BORDER:
                    color = ChatColor.DARK_GREEN;
                    break;
                case LOGO:
                    color = ChatColor.GREEN;
                    break;
                case TAGLINE:
                    color = ChatColor.AQUA;
                    break;
                case CREDIT:
                    color = ChatColor.GOLD;
                    break;
                case VERSION:
                    color = ChatColor.YELLOW;
                    break;
                default:
                    color = ChatColor.DARK_GRAY;
            }
            String emphasis = line.style == ConsoleBranding.Style.LOGO
                    || line.style == ConsoleBranding.Style.CREDIT ? ChatColor.BOLD.toString() : "";
            sender.sendMessage(color + emphasis + line.text);
        }
    }

    private boolean enabled(String path) {
        return getConfig().getBoolean(path, true);
    }

    private String format(String path) {
        return getConfig().getString(path, "");
    }

    private String safe(String text) {
        if (text == null) return "";
        String stripped = ChatColor.stripColor(text);
        return stripped == null ? "" : stripped.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String chatName(BridgeClient.ChatTarget target) {
        String name = safe(target.name);
        if (!name.isEmpty() && !name.equals(target.id)) return name;
        return target.isGroup ? "Unnamed WhatsApp group" : "Unknown contact";
    }

    private String truncateCodePoints(String value, int maximum) {
        if (value == null || value.isEmpty()) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maximum) return value;
        int end = value.offsetByCodePoints(0, maximum);
        return value.substring(0, end) + "…";
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static final class StatusPlayer {
        final String name;
        final String bukkitSkinUrl;
        final ClassLoader skinsRestorerLoader;
        final UUID uuid;
        final String accountName;

        StatusPlayer(String name, String bukkitSkinUrl, ClassLoader skinsRestorerLoader,
                     UUID uuid, String accountName) {
            this.name = name;
            this.bukkitSkinUrl = bukkitSkinUrl;
            this.skinsRestorerLoader = skinsRestorerLoader;
            this.uuid = uuid;
            this.accountName = accountName;
        }
    }

    private static final class StatusCapture {
        final String serverName;
        final String motd;
        final String serverVersion;
        final String uptime;
        final int onlinePlayers;
        final int maxPlayers;
        final double[] tps;
        final long usedMemory;
        final long maxMemory;
        final File serverIcon;
        final List<StatusPlayer> players;
        final boolean onlineMode;

        StatusCapture(String serverName, String motd, String serverVersion, String uptime,
                      int onlinePlayers, int maxPlayers, double[] tps,
                      long usedMemory, long maxMemory, File serverIcon,
                      List<StatusPlayer> players, boolean onlineMode) {
            this.serverName = serverName;
            this.motd = motd;
            this.serverVersion = serverVersion;
            this.uptime = uptime;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.tps = tps;
            this.usedMemory = usedMemory;
            this.maxMemory = maxMemory;
            this.serverIcon = serverIcon;
            this.players = players;
            this.onlineMode = onlineMode;
        }
    }
}
