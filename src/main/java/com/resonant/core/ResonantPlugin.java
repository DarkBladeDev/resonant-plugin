package com.resonant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import com.resonant.commands.VoiceCommand;
import com.resonant.mechanics.ModService;
import com.resonant.models.PlayerSnapshot;
import com.resonant.storage.DatabaseProvider;
import com.resonant.storage.ModerationRepository;
import com.resonant.utils.RateLimiter;
import com.resonant.utils.RoleHierarchy;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
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
    private VoiceCommand voiceCommand;
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
            if (voiceCommand == null) {
                voiceCommand = new VoiceCommand(this);
            }
            voiceCommand.updateDependencies(moderationMessages, modCommand);
            getCommand("voice").setExecutor(voiceCommand);
            getCommand("voice").setTabCompleter(voiceCommand);
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
        String permissionPrefix = getConfig().getString("moderation.roles.permissionPrefix", "resonant.role.");
        roleHierarchy = new RoleHierarchy(levels, permissionPrefix);
        int maxActions = getConfig().getInt("moderation.rateLimit.maxActions", 20);
        long windowSeconds = getConfig().getLong("moderation.rateLimit.windowSeconds", 60);
        rateLimiter = new RateLimiter(Clock.systemUTC(), maxActions, windowSeconds);
        modService = new ModService(this, voiceCoreClient, moderationRepository, moderationMessages, roleHierarchy, rateLimiter, serverId, this::isVoiceConnected, this::getVoicePing);
        modCommand = new ModCommand(modService, moderationMessages);
        if (voiceCommand != null) {
            voiceCommand.updateDependencies(moderationMessages, modCommand);
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            sendRoles(online);
        }
        startSessionSync();
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

    public void sendVoiceLink(Player player, int range) {
        String code = tokenService.createPlayerCode();
        voiceCoreClient.registerPlayerCode(player.getUniqueId(), player.getName(), serverId, code, tokenService.getTtlSeconds());
        String separator = webClientBaseUrl.contains("?") ? "&" : "?";
        String url = webClientBaseUrl + separator + "token=" + code + "&range=" + range;
        String displayUrl = url.replace("&", "＆");
        Component message = Component.text(moderationMessages.get("commands.voice.linkLabel"), NamedTextColor.AQUA)
                .append(Component.text(displayUrl, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(url))
                        .decorate(TextDecoration.UNDERLINED));
        player.sendMessage(message);
    }

    public void sendVoiceCode(Player player) {
        String code = tokenService.createPlayerCode();
        voiceCoreClient.registerPlayerCode(player.getUniqueId(), player.getName(), serverId, code, tokenService.getTtlSeconds());
        Component message = Component.text(moderationMessages.get("commands.voice.codeLabel"), NamedTextColor.AQUA)
                .append(Component.text(code, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .decorate(TextDecoration.UNDERLINED));
        player.sendMessage(message);
    }

    public void sendVoiceStatus(Player player) {
        player.sendMessage(moderationMessages.get("commands.status.request"));
        voiceCoreClient.fetchHealth(snapshot -> {
            String resultLine = moderationMessages.get("commands.status.result")
                    .replace("%result%", "HTTP " + snapshot.statusCode())
                    .replace("%latency%", String.valueOf(snapshot.latencyMs()));
            String statusKey = snapshot.ok() ? "status.voice.online" : "status.voice.offline";
            String statusLine = moderationMessages.get("commands.status.apparent")
                    .replace("%status%", moderationMessages.get(statusKey));
            Bukkit.getScheduler().runTask(this, () -> {
                player.sendMessage(resultLine);
                player.sendMessage(statusLine);
            });
        }, error -> {
            String reason = error.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = "unknown";
            }
            String resultLine = moderationMessages.get("commands.status.error")
                    .replace("%error%", reason);
            String statusLine = moderationMessages.get("commands.status.apparent")
                    .replace("%status%", moderationMessages.get("status.voice.offline"));
            Bukkit.getScheduler().runTask(this, () -> {
                player.sendMessage(resultLine);
                player.sendMessage(statusLine);
            });
        });
    }

    public int getPlayerRange(UUID uuid) {
        return playerRanges.getOrDefault(uuid, maxDistance);
    }

    public void setPlayerRange(UUID uuid, int range) {
        playerRanges.put(uuid, range);
    }

    public boolean toggleMute(Player player) {
        boolean nowMuted = muted.add(player.getUniqueId());
        if (!nowMuted) {
            muted.remove(player.getUniqueId());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("muted", muted.contains(player.getUniqueId()));
        sendEvent("mute", player, payload);
        return muted.contains(player.getUniqueId());
    }

    public void reloadBridgeCommand() {
        reloadBridge();
    }

    private void sendRoles(Player player) {
        ConfigurationSection rolesSection = getConfig().getConfigurationSection("moderation.roles.levels");
        if (rolesSection == null) {
            return;
        }
        String permissionPrefix = getConfig().getString("moderation.roles.permissionPrefix", "resonant.role.");
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
        }, error -> getLogger().warning("Error al sincronizar sesiones activas: " + error.getMessage()));
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
        }, error -> getLogger().warning("Failed to sync session updates: " + error.getMessage()));
    }
}
