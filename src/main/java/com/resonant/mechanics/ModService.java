package com.resonant.mechanics;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.resonant.core.ModerationMessages;
import com.resonant.core.VoiceCoreClient;
import com.resonant.models.ModerationAction;
import com.resonant.models.ModerationActionType;
import com.resonant.models.ModerationScope;
import com.resonant.storage.ModerationRepository;
import com.resonant.storage.ModerationStateCache;
import com.resonant.utils.DurationParser;
import com.resonant.utils.TimeFormatter;
import com.resonant.utils.RateLimiter;
import com.resonant.utils.RoleHierarchy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Function;

public class ModService {

    private final JavaPlugin plugin;
    private final VoiceCoreClient voiceCore;
    private final ModerationRepository repo;
    private final ModerationMessages messages;
    private final RoleHierarchy hierarchy;
    private final ModerationStateCache stateCache = new ModerationStateCache();
    private final RateLimiter limiter;
    private final SecureRandom random = new SecureRandom();
    private final String serverId;
    private final Predicate<UUID> voiceConnected;
    private final Function<UUID, Long> pingProvider;
    private final Map<UUID, PendingConfirmation> pending = new HashMap<>();

    public ModService(JavaPlugin plugin, VoiceCoreClient voiceCore, ModerationRepository repo, ModerationMessages messages, RoleHierarchy hierarchy, RateLimiter limiter, String serverId, Predicate<UUID> voiceConnected, Function<UUID, Long> pingProvider) {
        this.plugin = plugin;
        this.voiceCore = voiceCore;
        this.repo = repo;
        this.messages = messages;
        this.hierarchy = hierarchy;
        this.limiter = limiter;
        this.serverId = serverId;
        this.voiceConnected = voiceConnected != null ? voiceConnected : (uuid) -> true;
        this.pingProvider = pingProvider != null ? pingProvider : (uuid) -> null;
    }

    public record Result(boolean ok, String message) {}

    public Result mute(CommandContext ctx, String durationArg, String reason) {
        if (!ctx.sender().hasPermission("voicebridge.mod.mute")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!checkTarget(ctx)) {
            return new Result(false, messages.get("errors.roleHierarchy"));
        }
        if (!isTargetInVoice(ctx)) {
            return new Result(false, messages.get("errors.notInVoice"));
        }
        if (reason == null || reason.isBlank()) {
            return new Result(false, messages.get("errors.missingReason"));
        }
        Long dur = parseDuration(durationArg, plugin.getConfig().getConfigurationSection("moderation.presets"));
        if (dur == null) {
            return new Result(false, messages.get("errors.invalidDuration"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        long now = System.currentTimeMillis();
        Long expiresAt = dur == 0 ? 0L : now + dur * 1000;
        stateCache.setMute(ctx.targetUuid(), expiresAt);

        JsonObject payload = new JsonObject();
        payload.addProperty("muted", true);
        payload.addProperty("expiresAt", expiresAt);
        voiceCore.sendEvent(ctx.targetUuid(), ctx.targetName(), serverId, "mute", payload);

        ModerationAction action = new ModerationAction(
                UUID.randomUUID().toString(),
                ctx.targetUuid(),
                ctx.targetName(),
                ctx.moderator().getUniqueId(),
                ctx.moderator().getName(),
                ModerationActionType.MUTE,
                reason,
                now,
                expiresAt,
                dur,
                ModerationScope.SERVER,
                serverId,
                null,
                null
        );
        repo.insertAction(action);
        notifyTarget(ctx, messages.get("mod.mute.notify").replace("%duration%", formatDuration(dur)).replace("%reason%", reason));
        return new Result(true, messages.get("mod.mute.success").replace("%player%", ctx.targetName()).replace("%duration%", formatDuration(dur)).replace("%reason%", reason));
    }

    public Result unmute(CommandContext ctx) {
        if (!ctx.sender().hasPermission("voicebridge.mod.unmute")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!stateCache.isMuted(ctx.targetUuid())) {
            return new Result(false, messages.get("errors.notMuted"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        stateCache.setMute(ctx.targetUuid(), null);
        JsonObject payload = new JsonObject();
        payload.addProperty("muted", false);
        voiceCore.sendEvent(ctx.targetUuid(), ctx.targetName(), serverId, "mute", payload);
        repo.insertAction(new ModerationAction(UUID.randomUUID().toString(), ctx.targetUuid(), ctx.targetName(), ctx.moderator().getUniqueId(), ctx.moderator().getName(), ModerationActionType.UNMUTE, "unmute", System.currentTimeMillis(), null, null, ModerationScope.SERVER, serverId, null, null));
        notifyTarget(ctx, messages.get("mod.unmute.notify"));
        return new Result(true, messages.get("mod.unmute.success").replace("%player%", ctx.targetName()));
    }

    public Result deafen(CommandContext ctx, String reason) {
        if (!ctx.sender().hasPermission("voicebridge.mod.deafen")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!checkTarget(ctx)) {
            return new Result(false, messages.get("errors.roleHierarchy"));
        }
        if (!isTargetInVoice(ctx)) {
            return new Result(false, messages.get("errors.notInVoice"));
        }
        if (reason == null || reason.isBlank()) {
            return new Result(false, messages.get("errors.missingReason"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        stateCache.setDeafen(ctx.targetUuid(), 0L);
        JsonObject payload = new JsonObject();
        payload.addProperty("deafened", true);
        voiceCore.sendEvent(ctx.targetUuid(), ctx.targetName(), serverId, "deafen", payload);
        repo.insertAction(new ModerationAction(UUID.randomUUID().toString(), ctx.targetUuid(), ctx.targetName(), ctx.moderator().getUniqueId(), ctx.moderator().getName(), ModerationActionType.DEAFEN, reason, System.currentTimeMillis(), 0L, 0L, ModerationScope.SERVER, serverId, null, null));
        notifyTarget(ctx, messages.get("mod.deafen.notify"));
        return new Result(true, messages.get("mod.deafen.success").replace("%player%", ctx.targetName()));
    }

    public Result undeafen(CommandContext ctx) {
        if (!ctx.sender().hasPermission("voicebridge.mod.undeafen")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!stateCache.isDeafened(ctx.targetUuid())) {
            return new Result(false, messages.get("errors.notDeafened"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        stateCache.setDeafen(ctx.targetUuid(), null);
        JsonObject payload = new JsonObject();
        payload.addProperty("deafened", false);
        voiceCore.sendEvent(ctx.targetUuid(), ctx.targetName(), serverId, "undeafen", payload);
        repo.insertAction(new ModerationAction(UUID.randomUUID().toString(), ctx.targetUuid(), ctx.targetName(), ctx.moderator().getUniqueId(), ctx.moderator().getName(), ModerationActionType.UNDEAFEN, "undeafen", System.currentTimeMillis(), null, null, ModerationScope.SERVER, serverId, null, null));
        notifyTarget(ctx, messages.get("mod.undeafen.notify"));
        return new Result(true, messages.get("mod.undeafen.success").replace("%player%", ctx.targetName()));
    }

    public Result kick(CommandContext ctx, String reason, long cooldownSeconds) {
        if (!ctx.sender().hasPermission("voicebridge.mod.kick")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!checkTarget(ctx)) {
            return new Result(false, messages.get("errors.roleHierarchy"));
        }
        if (!isTargetInVoice(ctx)) {
            return new Result(false, messages.get("errors.notInVoice"));
        }
        if (reason == null || reason.isBlank()) {
            return new Result(false, messages.get("errors.missingReason"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        String code = createConfirmCode();
        pending.put(ctx.moderator().getUniqueId(), new PendingConfirmation(code, ModerationActionType.KICK, ctx, reason, cooldownSeconds, System.currentTimeMillis() + 60_000));
        return new Result(false, messages.get("confirm.request").replace("%code%", code));
    }

    public Result ban(CommandContext ctx, String durationArg, String reason, ModerationScope scope) {
        if (!ctx.sender().hasPermission("voicebridge.mod.ban")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!checkTarget(ctx)) {
            return new Result(false, messages.get("errors.roleHierarchy"));
        }
        Long dur = parseDuration(durationArg, plugin.getConfig().getConfigurationSection("moderation.presets"));
        if (dur == null) {
            return new Result(false, messages.get("errors.invalidDuration"));
        }
        if (reason == null || reason.isBlank()) {
            return new Result(false, messages.get("errors.missingReason"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        String code = createConfirmCode();
        pending.put(ctx.moderator().getUniqueId(), new PendingConfirmation(code, ModerationActionType.BAN, ctx, reason, dur != null ? dur : 0L, System.currentTimeMillis() + 60_000, scope));
        return new Result(false, messages.get("confirm.request").replace("%code%", code));
    }

    public Result unban(CommandContext ctx) {
        if (!ctx.sender().hasPermission("voicebridge.mod.unban")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!stateCache.isBanned(ctx.targetUuid())) {
            return new Result(false, messages.get("errors.notBanned"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        stateCache.setBan(ctx.targetUuid(), null);
        JsonObject payload = new JsonObject();
        payload.addProperty("banned", false);
        voiceCore.sendEvent(ctx.targetUuid(), ctx.targetName(), serverId, "unban", payload);
        repo.insertAction(new ModerationAction(UUID.randomUUID().toString(), ctx.targetUuid(), ctx.targetName(), ctx.moderator().getUniqueId(), ctx.moderator().getName(), ModerationActionType.UNBAN, "unban", System.currentTimeMillis(), null, null, ModerationScope.SERVER, serverId, null, null));
        notifyTarget(ctx, messages.get("mod.unban.notify"));
        return new Result(true, messages.get("mod.unban.success").replace("%player%", ctx.targetName()));
    }

    public Result warn(CommandContext ctx, String reason) {
        if (!ctx.sender().hasPermission("voicebridge.mod.warn")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        if (!checkTarget(ctx)) {
            return new Result(false, messages.get("errors.roleHierarchy"));
        }
        if (reason == null || reason.isBlank()) {
            return new Result(false, messages.get("errors.missingReason"));
        }
        if (!limiter.tryConsume(ctx.moderator().getUniqueId())) {
            return new Result(false, messages.get("errors.rateLimited"));
        }
        long now = System.currentTimeMillis();
        repo.insertAction(new ModerationAction(UUID.randomUUID().toString(), ctx.targetUuid(), ctx.targetName(), ctx.moderator().getUniqueId(), ctx.moderator().getName(), ModerationActionType.WARN, reason, now, null, null, ModerationScope.SERVER, serverId, null, null));
        notifyTarget(ctx, messages.get("mod.warn.notify").replace("%reason%", reason));
        return new Result(true, messages.get("mod.warn.success").replace("%player%", ctx.targetName()));
    }

    public Result info(CommandContext ctx) {
        if (!ctx.sender().hasPermission("voicebridge.mod.info")) {
            return new Result(false, messages.get("errors.noPermission"));
        }
        String clientStatus = voiceConnected.test(ctx.targetUuid())
                ? messages.get("status.client.connected")
                : messages.get("status.client.disconnected");
        String muteStatus = stateCache.isMuted(ctx.targetUuid())
                ? messages.get("status.mic.muted")
                : messages.get("status.mic.unmuted");
        String deafenStatus = stateCache.isDeafened(ctx.targetUuid())
                ? messages.get("status.mic.deafened")
                : messages.get("status.mic.undeafened");
        String micStatus = muteStatus + " / " + deafenStatus;
        Long pingMs = pingProvider.apply(ctx.targetUuid());
        String pingValue = pingMs != null ? String.valueOf(pingMs) : messages.get("status.ping.unknown");
        StringBuilder sb = new StringBuilder();
        sb.append(messages.get("mod.info.header").replace("%player%", ctx.targetName()));
        sb.append("\n").append(messages.get("mod.info.client").replace("%client%", clientStatus));
        sb.append("\n").append(messages.get("mod.info.mic").replace("%mic%", micStatus));
        sb.append("\n").append(messages.get("mod.info.ping").replace("%ping%", pingValue));
        return new Result(true, sb.toString());
    }

    private boolean isTargetInVoice(CommandContext ctx) {
        return voiceConnected.test(ctx.targetUuid());
    }

    public Result history(UUID targetUuid, String type, Long since, Long until) {
        if (type != null && !type.isBlank()) {
            try {
                ModerationActionType.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return new Result(false, messages.get("errors.invalidFilter"));
            }
        }
        List<ModerationAction> actions = repo.findHistory(targetUuid, type, since, until);
        return new Result(true, formatHistory(actions));
    }

    public Result confirm(Player moderator, String code) {
        PendingConfirmation pc = pending.get(moderator.getUniqueId());
        if (pc == null || pc.expiresAt < System.currentTimeMillis()) {
            pending.remove(moderator.getUniqueId());
            return new Result(false, messages.get("errors.confirmExpired"));
        }
        if (!Objects.equals(pc.code, code)) {
            return new Result(false, messages.get("errors.mustConfirm"));
        }
        pending.remove(moderator.getUniqueId());
        if (pc.type == ModerationActionType.KICK) {
            long until = System.currentTimeMillis() + Math.max(0, pc.cooldownSeconds) * 1000L;
            stateCache.setKickCooldown(pc.ctx.targetUuid(), until);
            JsonObject payload = new JsonObject();
            payload.addProperty("kickUntil", until);
            voiceCore.sendEvent(pc.ctx.targetUuid(), pc.ctx.targetName(), serverId, "kick", payload);
            repo.insertAction(new ModerationAction(UUID.randomUUID().toString(), pc.ctx.targetUuid(), pc.ctx.targetName(), pc.ctx.moderator.getUniqueId(), pc.ctx.moderator.getName(), ModerationActionType.KICK, pc.reason, System.currentTimeMillis(), until, pc.cooldownSeconds, ModerationScope.SERVER, serverId, null, null));
            notifyTarget(pc.ctx, messages.get("mod.kick.notify"));
            return new Result(true, messages.get("mod.kick.success").replace("%player%", pc.ctx.targetName()));
        }
        if (pc.type == ModerationActionType.BAN) {
            long now = System.currentTimeMillis();
            Long expiresAt = pc.cooldownSeconds == 0 ? 0L : now + pc.cooldownSeconds * 1000L;
            stateCache.setBan(pc.ctx.targetUuid(), expiresAt);
            JsonObject payload = new JsonObject();
            payload.addProperty("banned", true);
            payload.addProperty("scope", pc.scope != null ? pc.scope.name() : ModerationScope.SERVER.name());
            payload.addProperty("expiresAt", expiresAt);
            voiceCore.sendEvent(pc.ctx.targetUuid(), pc.ctx.targetName(), serverId, "ban", payload);
            repo.insertAction(new ModerationAction(UUID.randomUUID().toString(), pc.ctx.targetUuid(), pc.ctx.targetName(), pc.ctx.moderator.getUniqueId(), pc.ctx.moderator.getName(), ModerationActionType.BAN, pc.reason, now, expiresAt, pc.cooldownSeconds, pc.scope != null ? pc.scope : ModerationScope.SERVER, serverId, null, "open"));
            notifyTarget(pc.ctx, messages.get("mod.ban.notify").replace("%duration%", formatDuration(pc.cooldownSeconds)));
            return new Result(true, messages.get("mod.ban.success").replace("%player%", pc.ctx.targetName()).replace("%duration%", formatDuration(pc.cooldownSeconds)));
        }
        return new Result(false, messages.get("errors.mustConfirm"));
    }

    public static class CommandContext {
        private final Player moderator;
        private final String targetName;
        private final UUID targetUuid;
        private final org.bukkit.command.CommandSender sender;

        public CommandContext(Player moderator, String targetName, UUID targetUuid) {
            this.moderator = moderator;
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.sender = moderator;
        }

        public Player moderator() { return moderator; }
        public String targetName() { return targetName; }
        public UUID targetUuid() { return targetUuid; }
        public org.bukkit.command.CommandSender sender() { return sender; }
    }

    private boolean checkTarget(CommandContext ctx) {
        Player targetOnline = Bukkit.getPlayer(ctx.targetUuid());
        if (targetOnline == null) {
            return true;
        }
        return hierarchy.canActOn(ctx.moderator(), targetOnline);
    }

    private String createConfirmCode() {
        int v = random.nextInt(1_000_000);
        return String.format("%06d", v);
    }

    private void notifyTarget(CommandContext ctx, String message) {
        Player targetOnline = Bukkit.getPlayer(ctx.targetUuid());
        if (targetOnline != null) {
            targetOnline.sendMessage(message);
        }
    }

    private static Long parseDuration(String arg, org.bukkit.configuration.ConfigurationSection presets) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        String val = arg.toLowerCase(Locale.ROOT);
        if (presets != null) {
            if (val.equals("1m") || val.equals("minute1")) {
                return presets.getLong("minute1", 60);
            }
            if (val.equals("5m") || val.equals("minutes5")) {
                return presets.getLong("minutes5", 300);
            }
            if (val.equals("1h") || val.equals("hour1")) {
                return presets.getLong("hour1", 3600);
            }
        }
        Long parsed = DurationParser.parseSeconds(arg);
        return parsed;
    }

    private static String formatDuration(Long seconds) {
        if (seconds == null) {
            return "";
        }
        return TimeFormatter.formatSeconds(seconds);
    }

    private String formatHistory(List<ModerationAction> actions) {
        StringBuilder sb = new StringBuilder();
        sb.append(messages.get("mod.history.header")
                .replace("%player%", actions.isEmpty() ? "" : actions.get(0).targetName())
                .replace("%count%", String.valueOf(actions.size())));
        for (ModerationAction a : actions) {
            String date = Instant.ofEpochMilli(a.createdAt()).toString();
            String line = messages.get("mod.history.entry")
                    .replace("%date%", date)
                    .replace("%type%", a.type().name())
                    .replace("%mod%", a.moderatorName())
                    .replace("%reason%", a.reason());
            sb.append("\n").append(line);
        }
        return sb.toString();
    }

    private static class PendingConfirmation {
        final String code;
        final ModerationActionType type;
        final CommandContext ctx;
        final String reason;
        final long cooldownSeconds;
        final long expiresAt;
        final ModerationScope scope;

        PendingConfirmation(String code, ModerationActionType type, CommandContext ctx, String reason, long cooldownSeconds, long expiresAt) {
            this(code, type, ctx, reason, cooldownSeconds, expiresAt, ModerationScope.SERVER);
        }

        PendingConfirmation(String code, ModerationActionType type, CommandContext ctx, String reason, long cooldownSeconds, long expiresAt, ModerationScope scope) {
            this.code = code;
            this.type = type;
            this.ctx = ctx;
            this.reason = reason;
            this.cooldownSeconds = cooldownSeconds;
            this.expiresAt = expiresAt;
            this.scope = scope;
        }
    }
}
