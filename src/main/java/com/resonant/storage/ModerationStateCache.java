package com.resonant.storage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModerationStateCache {

    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deafens = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bans = new ConcurrentHashMap<>();
    private final Map<UUID, Long> kickCooldowns = new ConcurrentHashMap<>();

    public void setMute(UUID uuid, Long expiresAt) {
        if (expiresAt == null) {
            mutes.remove(uuid);
            return;
        }
        mutes.put(uuid, expiresAt);
    }

    public void setDeafen(UUID uuid, Long expiresAt) {
        if (expiresAt == null) {
            deafens.remove(uuid);
            return;
        }
        deafens.put(uuid, expiresAt);
    }

    public void setBan(UUID uuid, Long expiresAt) {
        if (expiresAt == null) {
            bans.remove(uuid);
            return;
        }
        bans.put(uuid, expiresAt);
    }

    public void setKickCooldown(UUID uuid, Long expiresAt) {
        if (expiresAt == null) {
            kickCooldowns.remove(uuid);
            return;
        }
        kickCooldowns.put(uuid, expiresAt);
    }

    public boolean isMuted(UUID uuid) {
        return isActive(mutes, uuid);
    }

    public boolean isDeafened(UUID uuid) {
        return isActive(deafens, uuid);
    }

    public boolean isBanned(UUID uuid) {
        return isActive(bans, uuid);
    }

    public boolean isKickBlocked(UUID uuid) {
        return isActive(kickCooldowns, uuid);
    }

    private boolean isActive(Map<UUID, Long> map, UUID uuid) {
        Long expiresAt = map.get(uuid);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt == 0L) {
            return true;
        }
        if (System.currentTimeMillis() > expiresAt) {
            map.remove(uuid);
            return false;
        }
        return true;
    }
}
