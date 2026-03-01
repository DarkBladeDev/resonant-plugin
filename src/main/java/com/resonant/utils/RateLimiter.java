package com.resonant.utils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {

    private final Map<UUID, Deque<Long>> bucket = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxActions;
    private final long windowMillis;

    public RateLimiter(Clock clock, int maxActions, long windowSeconds) {
        this.clock = clock;
        this.maxActions = maxActions;
        this.windowMillis = windowSeconds * 1000L;
    }

    public boolean tryConsume(UUID moderator) {
        long now = Instant.now(clock).toEpochMilli();
        Deque<Long> deque = bucket.computeIfAbsent(moderator, id -> new ArrayDeque<>());
        while (!deque.isEmpty() && now - deque.peekFirst() > windowMillis) {
            deque.pollFirst();
        }
        if (deque.size() >= maxActions) {
            return false;
        }
        deque.addLast(now);
        return true;
    }
}
