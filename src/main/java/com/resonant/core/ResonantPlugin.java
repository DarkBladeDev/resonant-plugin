package com.resonant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import com.resonant.commands.ModCommand;
import com.resonant.mechanics.ModService;
import com.resonant.models.PlayerSnapshot;
import com.resonant.storage.DatabaseProvider;
import com.resonant.storage.ModerationRepository;
import com.resonant.utils.RateLimiter;
import com.resonant.utils.RoleHierarchy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ResonantPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerSnapshot> pendingMoves = new ConcurrentHashMap<>();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> playerRanges = new ConcurrentHashMap<>();
    private final Set<UUID> voiceConnected = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> voicePingMs = new ConcurrentHashMap<>();
    private VoiceCoreClient voiceCoreClient;
    private TokenService tokenService;
    private DatabaseProvider databaseProvider;
    private ModerationRepository moderationRepository;
    private ModerationMessages moderationMessages;
    private RoleHierarchy roleHierarchy;
    private RateLimiter rateLimiter;
    private ModService modService;
    private ModCommand modCommand;
    private String serverId;
    private String webClientBaseUrl;
    private int moveIntervalTicks;
    private int maxDistance;
    private BukkitTask sessionSyncTask;
    private volatile long sessionSyncSince;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadBridge();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("voice") != null) {
            getCommand("voice").setExecutor(this);
            getCommand("voice").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::flushMoveEvents, moveIntervalTicks, moveIntervalTicks);
    }

    @Override
    public void onDisable() {
        stopSessionSync();
        if (databaseProvider != null) {
            databaseProvider.stop();
        }
    }

    private void reloadBridge() {
        reloadConfig();
        stopSessionSync();
        String httpUrl = getConfig().getString("voiceCore.httpUrl", "http://127.0.0.1:3000");
        String secret = getConfig().getString("auth.secret", "cambia-este-secreto");
        int ttlSeconds = getConfig().getInt("auth.ttlSeconds", 120);
        serverId = getConfig().getString("voiceCore.serverId", "lobby-1");
        webClientBaseUrl = getConfig().getString("webClient.baseUrl", "http://localhost:5173");
        moveIntervalTicks = getConfig().getInt("events.moveIntervalTicks", 10);
        maxDistance = getConfig().getInt("proximity.maxDistance", 48);
        voiceCoreClient = new VoiceCoreClient(httpUrl, secret);
        tokenService = new TokenService(secret, ttlSeconds);
        if (databaseProvider != null) {
            databaseProvider.stop();
        }
        databaseProvider = new DatabaseProvider(this);
        databaseProvider.start();
        moderationRepository = new ModerationRepository(databaseProvider.get());
        moderationRepository.init(this);
        moderationMessages = new ModerationMessages(this, getConfig());
        ConfigurationSection rolesSection = getConfig().getConfigurationSection("moderation.roles.levels");
        Map<String, Integer> levels = new HashMap<>();
        if (rolesSection != null) {
            for (String key : rolesSection.getKeys(false)) {
                levels.put(key, rolesSection.getInt(key));
            }
        }
        String permissionPrefix = getConfig().getString("moderation.roles.permissionPrefix", "voicebridge.role.");
        roleHierarchy = new RoleHierarchy(levels, permissionPrefix);
        int maxActions = getConfig().getInt("moderation.rateLimit.maxActions", 20);
        long windowSeconds = getConfig().getLong("moderation.rateLimit.windowSeconds", 60);
        rateLimiter = new RateLimiter(Clock.systemUTC(), maxActions, windowSeconds);
        modService = new ModService(this, voiceCoreClient, moderationRepository, moderationMessages, roleHierarchy, rateLimiter, serverId, this::isVoiceConnected, this::getVoicePing);
        modCommand = new ModCommand(modService, moderationMessages);
        for (Player online : Bukkit.getOnlinePlayers()) {
            sendRoles(online);
        }
        startSessionSync();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(moderationMessages.get("errors.onlyPlayers"));
            return true;
        }
        if (!player.hasPermission("voicebridge.use")) {
            player.sendMessage(moderationMessages.get("errors.noPermission"));
            return true;
        }
        if (args.length == 0) {
            int range = playerRanges.getOrDefault(player.getUniqueId(), maxDistance);
            sendVoiceLink(player, range);
            return true;
        }
        if (args[0].equalsIgnoreCase("mod")) {
            if (!player.hasPermission("voicebridge.mod.use")) {
                player.sendMessage(moderationMessages.get("errors.noPermission"));
                return true;
            }
            if (modCommand == null) {
                player.sendMessage(moderationMessages.get("errors.noPermission"));
                return true;
            }
            String[] subArgs = new String[Math.max(0, args.length - 1)];
            if (args.length > 1) {
                System.arraycopy(args, 1, subArgs, 0, args.length - 1);
            }
            return modCommand.onCommand(player, command, label, subArgs);
        }
        if (args[0].equalsIgnoreCase("mute")) {
            boolean nowMuted = muted.add(player.getUniqueId());
            if (!nowMuted) {
                muted.remove(player.getUniqueId());
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("muted", muted.contains(player.getUniqueId()));
            sendEvent("mute", player, payload);
            player.sendMessage(muted.contains(player.getUniqueId())
                    ? moderationMessages.get("commands.mute.enabled")
                    : moderationMessages.get("commands.mute.disabled"));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("voicebridge.reload")) {
                player.sendMessage(moderationMessages.get("errors.noPermission"));
                return true;
            }
            reloadBridge();
            player.sendMessage(moderationMessages.get("commands.reload.success"));
            return true;
        }
        if (args[0].equalsIgnoreCase("range")) {
            if (!player.hasPermission("voicebridge.range")) {
                player.sendMessage(moderationMessages.get("errors.noPermission"));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(moderationMessages.get("commands.range.usage"));
                return true;
            }
            int value;
            try {
                value = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                player.sendMessage(moderationMessages.get("commands.range.invalidNumber"));
                return true;
            }
            if (value < 4 || value > 128) {
                player.sendMessage(moderationMessages.get("commands.range.outOfBounds"));
                return true;
            }
            playerRanges.put(player.getUniqueId(), value);
            player.sendMessage(moderationMessages.get("commands.range.set").replace("%range%", String.valueOf(value)));
            sendVoiceLink(player, value);
            return true;
        }
        if (args[0].equalsIgnoreCase("code")) {
            sendVoiceCode(player);
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        if (!player.hasPermission("voicebridge.use")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toLowerCase();
            if ("code".startsWith(input)) {
                suggestions.add("code");
            }
            if ("mute".startsWith(input)) {
                suggestions.add("mute");
            }
            if (player.hasPermission("voicebridge.mod.use") && "mod".startsWith(input)) {
                suggestions.add("mod");
            }
            if (player.hasPermission("voicebridge.reload") && "reload".startsWith(input)) {
                suggestions.add("reload");
            }
            if (player.hasPermission("voicebridge.range") && "range".startsWith(input)) {
                suggestions.add("range");
            }
            return suggestions;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("mod") && modCommand != null) {
            String[] subArgs = new String[Math.max(0, args.length - 1)];
            if (args.length > 1) {
                System.arraycopy(args, 1, subArgs, 0, args.length - 1);
            }
            return modCommand.onTabComplete(sender, command, label, subArgs);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("range")) {
            if (!player.hasPermission("voicebridge.range")) {
                return Collections.emptyList();
            }
            String input = args[1].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            for (int value : new int[]{4, 8, 16, 24, 32, 48, 64, 96, 128}) {
                String candidate = String.valueOf(value);
                if (candidate.startsWith(input)) {
                    suggestions.add(candidate);
                }
            }
            return suggestions;
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sendEvent("join", event.getPlayer(), null);
        sendRoles(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sendEvent("quit", event.getPlayer(), null);
        muted.remove(event.getPlayer().getUniqueId());
        pendingMoves.remove(event.getPlayer().getUniqueId());
        playerRanges.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        sendEvent("teleport", event.getPlayer(), snapshotLocation(event.getTo()));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getWorld() == null || to.getWorld() == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld().equals(to.getWorld())) {
            return;
        }
        pendingMoves.put(event.getPlayer().getUniqueId(), PlayerSnapshot.from(event.getPlayer(), to));
    }

    private void flushMoveEvents() {
        if (pendingMoves.isEmpty()) {
            return;
        }
        for (PlayerSnapshot snapshot : pendingMoves.values()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("world", snapshot.world());
            payload.addProperty("dimension", snapshot.dimension());
            payload.addProperty("x", snapshot.x());
            payload.addProperty("y", snapshot.y());
            payload.addProperty("z", snapshot.z());
            payload.addProperty("timestamp", Instant.now().toEpochMilli());
            voiceCoreClient.sendEvent(snapshot.uuid(), snapshot.name(), serverId, "move", payload);
        }
        pendingMoves.clear();
    }

    private void sendEvent(String type, Player player, JsonObject extra) {
        JsonObject payload = new JsonObject();
        if (extra != null) {
            extra.asMap().forEach(payload::add);
        }
        JsonObject loc = snapshotLocation(player.getLocation());
        loc.asMap().forEach(payload::add);
        voiceCoreClient.sendEvent(player.getUniqueId(), player.getName(), serverId, type, payload);
    }

    private JsonObject snapshotLocation(Location location) {
        JsonObject payload = new JsonObject();
        if (location == null || location.getWorld() == null) {
            return payload;
        }
        payload.addProperty("world", location.getWorld().getName());
        payload.addProperty("dimension", location.getWorld().getEnvironment().name());
        payload.addProperty("x", location.getX());
        payload.addProperty("y", location.getY());
        payload.addProperty("z", location.getZ());
        return payload;
    }

    private void sendVoiceLink(Player player, int range) {
        String code = tokenService.createPlayerCode();
        voiceCoreClient.registerPlayerCode(player.getUniqueId(), player.getName(), serverId, code, tokenService.getTtlSeconds());
        String url = webClientBaseUrl + "?token=" + code + "&range=" + range;
        String displayUrl = url.replace("&", "＆");
        Component message = Component.text(moderationMessages.get("commands.voice.linkLabel"), NamedTextColor.AQUA)
                .append(Component.text(displayUrl, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(url))
                        .decorate(TextDecoration.UNDERLINED));
        player.sendMessage(message);
    }

    private void sendVoiceCode(Player player) {
        String code = tokenService.createPlayerCode();
        voiceCoreClient.registerPlayerCode(player.getUniqueId(), player.getName(), serverId, code, tokenService.getTtlSeconds());
        Component message = Component.text(moderationMessages.get("commands.voice.codeLabel"), NamedTextColor.AQUA)
                .append(Component.text(code, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .decorate(TextDecoration.UNDERLINED));
        player.sendMessage(message);
    }

    private void sendRoles(Player player) {
        ConfigurationSection rolesSection = getConfig().getConfigurationSection("moderation.roles.levels");
        if (rolesSection == null) {
            return;
        }
        String permissionPrefix = getConfig().getString("moderation.roles.permissionPrefix", "voicebridge.role.");
        JsonArray roles = new JsonArray();
        for (String role : rolesSection.getKeys(false)) {
            if (player.hasPermission(permissionPrefix + role)) {
                roles.add(role);
            }
        }
        JsonObject payload = new JsonObject();
        payload.add("roles", roles);
        voiceCoreClient.sendEvent(player.getUniqueId(), player.getName(), serverId, "roles", payload);
    }

    private boolean isVoiceConnected(UUID uuid) {
        return voiceConnected.contains(uuid);
    }

    private Long getVoicePing(UUID uuid) {
        return voicePingMs.get(uuid);
    }

    private void startSessionSync() {
        voiceConnected.clear();
        voicePingMs.clear();
        voiceCoreClient.fetchActiveSessions(serverId, snapshot -> {
            voiceConnected.clear();
            voicePingMs.clear();
            for (VoiceCoreClient.SessionInfo session : snapshot.sessions()) {
                voiceConnected.add(session.uuid());
                if (session.pingMs() != null) {
                    voicePingMs.put(session.uuid(), session.pingMs());
                }
            }
            sessionSyncSince = snapshot.now();
        }, error -> getLogger().warning("No se pudo sincronizar sesiones activas: " + error.getMessage()));
        sessionSyncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollSessionUpdates, 20L, 20L);
    }

    private void stopSessionSync() {
        if (sessionSyncTask != null) {
            sessionSyncTask.cancel();
            sessionSyncTask = null;
        }
        voiceConnected.clear();
        voicePingMs.clear();
    }

    private void pollSessionUpdates() {
        long since = sessionSyncSince;
        voiceCoreClient.fetchSessionUpdates(serverId, since, snapshot -> {
            for (VoiceCoreClient.SessionUpdate update : snapshot.updates()) {
                switch (update.type()) {
                    case "session_started", "session_refreshed" -> {
                        voiceConnected.add(update.uuid());
                        if (update.pingMs() != null) {
                            voicePingMs.put(update.uuid(), update.pingMs());
                        } else {
                            voicePingMs.remove(update.uuid());
                        }
                    }
                    case "session_ended" -> {
                        voiceConnected.remove(update.uuid());
                        voicePingMs.remove(update.uuid());
                    }
                    case "session_ping" -> {
                        if (update.pingMs() != null) {
                            voicePingMs.put(update.uuid(), update.pingMs());
                        }
                    }
                    default -> {}
                }
                sessionSyncSince = Math.max(sessionSyncSince, update.ts());
            }
            sessionSyncSince = Math.max(sessionSyncSince, snapshot.now());
        }, error -> getLogger().warning("No se pudo sincronizar sesiones: " + error.getMessage()));
    }
}
