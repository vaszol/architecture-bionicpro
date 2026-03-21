package com.bionicpro.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String SESSION_PREFIX = "session:";
    private static final int SESSION_TTL = 3600; // 1 час

    public String exchangeCode(String code, String redirectUri, String codeVerifier) {
        log.debug("PKCE exchange - code: {}, redirect: {}, verifier length: {}",
                code, redirectUri, codeVerifier != null ? codeVerifier.length() : 0);

        String sessionId = UUID.randomUUID().toString();

        // Сохраняем в Redis с TTL
        redisTemplate.opsForValue().set(
                SESSION_PREFIX + sessionId,
                "test-refresh-token",
                SESSION_TTL,
                TimeUnit.SECONDS
        );

        log.info("Session created in Redis: {}", sessionId);
        return sessionId;
    }

    public boolean isAuthenticated(String sessionId) {
        if (sessionId == null) {
            return false;
        }

        String key = SESSION_PREFIX + sessionId;
        boolean authenticated = Boolean.TRUE.equals(redisTemplate.hasKey(key));

        log.debug("Session {} authenticated: {}", sessionId, authenticated);
        return authenticated;
    }

    public String refreshToken(String sessionId) {
        String key = SESSION_PREFIX + sessionId;

        if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
            throw new RuntimeException("Session not found");
        }

        // Получаем refresh token
        String refreshToken = redisTemplate.opsForValue().get(key);

        // Удаляем старую сессию
        redisTemplate.delete(key);

        // Создаем новую сессию
        String newSessionId = UUID.randomUUID().toString();
        String newKey = SESSION_PREFIX + newSessionId;

        redisTemplate.opsForValue().set(
                newKey,
                refreshToken,
                SESSION_TTL,
                TimeUnit.SECONDS
        );

        log.debug("Token refreshed: {} -> {}", sessionId, newSessionId);
        return newSessionId;
    }

    public void logout(String sessionId) {
        if (sessionId != null) {
            String key = SESSION_PREFIX + sessionId;
            redisTemplate.delete(key);
            log.debug("Session deleted from Redis: {}", sessionId);
        }
    }
}