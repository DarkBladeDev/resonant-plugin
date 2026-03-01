package com.resonant.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.resonant.core.ModerationMessages;
import com.resonant.mechanics.ModService;
import com.resonant.models.ModerationScope;
import com.resonant.utils.DurationParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ModCommand implements CommandExecutor, TabCompleter {

    private final ModService modService;
    private final ModerationMessages messages;

    public ModCommand(ModService modService, ModerationMessages messages) {
        this.modService = modService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player moderator)) {
            sender.sendMessage(messages.get("errors.onlyPlayers"));
            return true;
        }
        if (args.length < 1) {
            return false;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("confirm")) {
            if (args.length < 2) {
                moderator.sendMessage(messages.get("errors.mustConfirm"));
                return true;
            }
            ModService.Result result = modService.confirm(moderator, args[1]);
            moderator.sendMessage(result.message());
            return true;
        }
        if (args.length < 2) {
            return false;
        }
        String targetName = args[1];
        UUID targetUuid = resolveUuid(targetName);
        if (targetUuid == null) {
            moderator.sendMessage(messages.get("errors.playerNotFound"));
            return true;
        }
        if (moderator.getUniqueId().equals(targetUuid)) {
            moderator.sendMessage(messages.get("errors.targetSelf"));
            return true;
        }
        ModService.CommandContext ctx = new ModService.CommandContext(moderator, targetName, targetUuid);
        switch (sub) {
            case "mute" -> {
                if (args.length < 4) {
                    moderator.sendMessage(messages.get("errors.missingReason"));
                    return true;
                }
                String duration = args[2];
                String reason = join(args, 3);
                ModService.Result result = modService.mute(ctx, duration, reason);
                moderator.sendMessage(result.message());
                return true;
            }
            case "unmute" -> {
                ModService.Result result = modService.unmute(ctx);
                moderator.sendMessage(result.message());
                return true;
            }
            case "deafen" -> {
                if (args.length < 3) {
                    moderator.sendMessage(messages.get("errors.missingReason"));
                    return true;
                }
                String reason = join(args, 2);
                ModService.Result result = modService.deafen(ctx, reason);
                moderator.sendMessage(result.message());
                return true;
            }
            case "undeafen" -> {
                ModService.Result result = modService.undeafen(ctx);
                moderator.sendMessage(result.message());
                return true;
            }
            case "kick" -> {
                if (args.length < 4) {
                    moderator.sendMessage(messages.get("errors.missingReason"));
                    return true;
                }
                long cooldown = parseCooldown(args[2]);
                String reason = join(args, 3);
                ModService.Result result = modService.kick(ctx, reason, cooldown);
                moderator.sendMessage(result.message());
                return true;
            }
            case "ban" -> {
                if (args.length < 4) {
                    moderator.sendMessage(messages.get("errors.missingReason"));
                    return true;
                }
                String duration = args[2];
                String reason = join(args, 3);
                ModService.Result result = modService.ban(ctx, duration, reason, ModerationScope.SERVER);
                moderator.sendMessage(result.message());
                return true;
            }
            case "unban" -> {
                ModService.Result result = modService.unban(ctx);
                moderator.sendMessage(result.message());
                return true;
            }
            case "warn" -> {
                if (args.length < 3) {
                    moderator.sendMessage(messages.get("errors.missingReason"));
                    return true;
                }
                String reason = join(args, 2);
                ModService.Result result = modService.warn(ctx, reason);
                moderator.sendMessage(result.message());
                return true;
            }
            case "history" -> {
                String filterType = args.length >= 3 ? args[2] : null;
                Long since = args.length >= 4 ? parseDateMillis(args[3]) : null;
                ModService.Result result = modService.history(targetUuid, filterType, since, null);
                moderator.sendMessage(result.message());
                return true;
            }
            case "info" -> {
                ModService.Result result = modService.info(ctx);
                moderator.sendMessage(result.message());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("mute", "unmute", "deafen", "undeafen", "kick", "ban", "unban", "warn", "history", "info", "confirm")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    suggestions.add(option);
                }
            }
            return suggestions;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("confirm")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                    suggestions.add(player.getName());
                }
            }
            return suggestions;
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("mute") || args[0].equalsIgnoreCase("ban"))) {
            for (String option : List.of("1m", "5m", "1h", "1d", "perm")) {
                if (option.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    suggestions.add(option);
                }
            }
        }
        return suggestions;
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return null;
    }

    private static String join(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static long parseCooldown(String value) {
        Long parsed = DurationParser.parseSeconds(value);
        return parsed != null ? parsed : 0L;
    }

    private static Long parseDateMillis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
