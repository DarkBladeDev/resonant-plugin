package com.resonant.models;

import java.util.UUID;

public record ModerationAction(
        String id,
        UUID targetUuid,
        String targetName,
        UUID moderatorUuid,
        String moderatorName,
        ModerationActionType type,
        String reason,
        long createdAt,
        Long expiresAt,
        Long durationSeconds,
        ModerationScope scope,
        String serverId,
        String metadata,
        String appealStatus
) {
}
