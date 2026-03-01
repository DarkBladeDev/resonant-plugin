package com.resonant.utils;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class RoleHierarchy {

    private final Map<String, Integer> levels;
    private final String permissionPrefix;

    public RoleHierarchy(Map<String, Integer> levels, String permissionPrefix) {
        this.levels = levels;
        this.permissionPrefix = permissionPrefix;
    }

    public int resolveLevel(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return Integer.MAX_VALUE;
        }
        int max = 0;
        for (String role : levels.keySet()) {
            if (player.hasPermission(permissionPrefix + role)) {
                int level = levels.getOrDefault(role, 0);
                if (level > max) {
                    max = level;
                }
            }
        }
        return max;
    }

    public boolean canActOn(CommandSender actor, CommandSender target) {
        return resolveLevel(actor) > resolveLevel(target);
    }
}
