package com.resonant.commands;

import com.resonant.core.ModerationMessages;
import com.resonant.core.ResonantPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class VoiceCommand implements CommandExecutor, TabCompleter {

    private final ResonantPlugin plugin;
    private ModerationMessages messages;
    private ModCommand modCommand;

    public VoiceCommand(ResonantPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateDependencies(ModerationMessages messages, ModCommand modCommand) {
        this.messages = messages;
        this.modCommand = modCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("errors.onlyPlayers"));
            return true;
        }
        if (!player.hasPermission("resonant.use")) {
            player.sendMessage(messages.get("errors.noPermission"));
            return true;
        }
        if (args.length == 0) {
            int range = plugin.getPlayerRange(player.getUniqueId());
            plugin.sendVoiceLink(player, range);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("mod")) {
            if (!player.hasPermission("resonant.mod.use")) {
                player.sendMessage(messages.get("errors.noPermission"));
                return true;
            }
            if (modCommand == null) {
                player.sendMessage(messages.get("errors.noPermission"));
                return true;
            }
            String[] subArgs = new String[Math.max(0, args.length - 1)];
            if (args.length > 1) {
                System.arraycopy(args, 1, subArgs, 0, args.length - 1);
            }
            return modCommand.onCommand(player, command, label, subArgs);
        }
        switch (sub) {
            case "mute" -> {
                boolean muted = plugin.toggleMute(player);
                player.sendMessage(muted
                        ? messages.get("commands.mute.enabled")
                        : messages.get("commands.mute.disabled"));
                return true;
            }
            case "reload" -> {
                if (!player.hasPermission("resonant.reload")) {
                    player.sendMessage(messages.get("errors.noPermission"));
                    return true;
                }
                plugin.reloadBridgeCommand();
                player.sendMessage(messages.get("commands.reload.success"));
                return true;
            }
            case "range" -> {
                if (!player.hasPermission("resonant.range")) {
                    player.sendMessage(messages.get("errors.noPermission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(messages.get("commands.range.usage"));
                    return true;
                }
                int value;
                try {
                    value = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    player.sendMessage(messages.get("commands.range.invalidNumber"));
                    return true;
                }
                if (value < 4 || value > 128) {
                    player.sendMessage(messages.get("commands.range.outOfBounds"));
                    return true;
                }
                plugin.setPlayerRange(player.getUniqueId(), value);
                player.sendMessage(messages.get("commands.range.set").replace("%range%", String.valueOf(value)));
                plugin.sendVoiceLink(player, value);
                return true;
            }
            case "code" -> {
                plugin.sendVoiceCode(player);
                return true;
            }
            case "status" -> {
                plugin.sendVoiceStatus(player);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        if (!player.hasPermission("resonant.use")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toLowerCase(Locale.ROOT);
            if ("code".startsWith(input)) {
                suggestions.add("code");
            }
            if ("mute".startsWith(input)) {
                suggestions.add("mute");
            }
            if (player.hasPermission("resonant.mod.use") && "mod".startsWith(input)) {
                suggestions.add("mod");
            }
            if ("status".startsWith(input)) {
                suggestions.add("status");
            }
            if (player.hasPermission("resonant.reload") && "reload".startsWith(input)) {
                suggestions.add("reload");
            }
            if (player.hasPermission("resonant.range") && "range".startsWith(input)) {
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
            if (!player.hasPermission("resonant.range")) {
                return Collections.emptyList();
            }
            String input = args[1].toLowerCase(Locale.ROOT);
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
}
