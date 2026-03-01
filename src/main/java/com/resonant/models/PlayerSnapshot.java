package com.resonant.models;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

public record PlayerSnapshot(UUID uuid, String name, String world, String dimension, double x, double y, double z) {

    public static PlayerSnapshot from(Player player, Location location) {
        String world = location.getWorld() != null ? location.getWorld().getName() : "unknown";
        String dimension = location.getWorld() != null ? location.getWorld().getEnvironment().name() : "UNKNOWN";
        return new PlayerSnapshot(player.getUniqueId(), player.getName(), world, dimension, location.getX(), location.getY(), location.getZ());
    }
}
