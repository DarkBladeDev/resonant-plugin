package com.resonant.core;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public class TokenService {

    private final Algorithm algorithm;
    private final int ttlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(String secret, int ttlSeconds) {
        this.algorithm = Algorithm.HMAC256(secret);
        this.ttlSeconds = ttlSeconds;
    }

    public String createPlayerToken(UUID uuid, String name, String serverId) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer("voice-bridge")
                .withSubject(uuid.toString())
                .withClaim("name", name)
                .withClaim("serverId", serverId)
                .withClaim("type", "player")
                .withExpiresAt(Date.from(now.plusSeconds(ttlSeconds)))
                .sign(algorithm);
    }

    public String createPlayerCode() {
        int value = secureRandom.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public String createBridgeToken(String serverId) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer("voice-bridge")
                .withSubject(serverId)
                .withClaim("type", "bridge")
                .withExpiresAt(Date.from(now.plusSeconds(ttlSeconds)))
                .sign(algorithm);
    }
}
