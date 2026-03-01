package com.resonant.utils;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

public class PermissionUtil {

    private final String permissionPrefix;

    public PermissionUtil(String permissionPrefix) {
        this.permissionPrefix = permissionPrefix;
    }

    public int resolveRoleLevel(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return Integer.MAX_VALUE;
        }
        int max = 0;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (!perm.startsWith(permissionPrefix)) {
                continue;
            }
            if (!info.getValue()) {
                continue;
            }
            String role = perm.substring(permissionPrefix.length());
            int level = resolveLevel(role);
            if (level > max) {
                max = level;
            }
        }
        return max;
    }

    public int resolveLevel(String role) {
        return switch (role.toLowerCase()) {
            case "admin" -> 100;
            case "mod" -> 50;
            case "helper" -> 10;
            default -> 0;
        };
    }
}
