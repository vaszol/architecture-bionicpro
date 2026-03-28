package com.bionicpro.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${bionicpro.auth.keycloak.url}")
    private String keycloakUrl;

    @Value("${bionicpro.auth.keycloak.realm}")
    private String realm;

    @Value("${bionicpro.auth.keycloak.client-id}")
    private String clientId;

    @Value("${bionicpro.auth.keycloak.client-secret:}")
    private String clientSecret;

    private static final String SESSION_PREFIX = "session:";
    private static final int SESSION_TTL = 3600; // 1 час

    public String exchangeCode(String code, String redirectUri, String codeVerifier) {
        log.debug("PKCE exchange - code: {}, redirect: {}, verifier length: {}",
                code, redirectUri, codeVerifier != null ? codeVerifier.length() : 0);

        try {
            // 1. Обмен кода на токены в Keycloak
            Map<String, Object> tokens = exchangeCodeForTokens(code, redirectUri, codeVerifier);
            String accessToken = (String) tokens.get("access_token");
            String refreshToken = (String) tokens.get("refresh_token");

            // 2. Получение user info из Keycloak
            Map<String, Object> keycloakUserInfo = getUserInfoFromKeycloak(accessToken);

            // Используем preferred_username (логин) как userId
            String userId = (String) keycloakUserInfo.get("preferred_username");
            if (userId == null) {
                // fallback для пользователей, у которых нет preferred_username
                userId = (String) keycloakUserInfo.get("sub");
            }

            // 3. Формируем user info для хранения
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("userId", userId);
            userInfo.put("email", keycloakUserInfo.get("email"));
            userInfo.put("username", userId);
            userInfo.put("firstName", keycloakUserInfo.get("given_name"));
            userInfo.put("lastName", keycloakUserInfo.get("family_name"));

            // 4. Создаём сессию
            String sessionId = UUID.randomUUID().toString();
            String key = SESSION_PREFIX + sessionId;

            // Сохраняем user info и refresh token
            for (Map.Entry<String, Object> entry : userInfo.entrySet()) {
                redisTemplate.opsForHash().put(key, entry.getKey(), entry.getValue());
            }
            redisTemplate.opsForHash().put(key, "refreshToken", refreshToken);
            redisTemplate.expire(key, SESSION_TTL, TimeUnit.SECONDS);

            log.info("Session created: {} for user: {}", sessionId, userInfo.get("userId"));
            return sessionId;

        } catch (Exception e) {
            log.error("Error exchanging code", e);
            throw new RuntimeException("Failed to exchange code", e);
        }
    }

    private Map<String, Object> exchangeCodeForTokens(String code, String redirectUri, String codeVerifier) {
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", keycloakUrl, realm);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        if (codeVerifier != null) {
            body.add("code_verifier", codeVerifier);
        }

        if (clientSecret != null && !clientSecret.isEmpty()) {
            body.add("client_secret", clientSecret);
        }

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);
        if (response.getStatusCode() == HttpStatus.OK) {
            return response.getBody();
        }
        throw new RuntimeException("Failed to exchange code for tokens");
    }

    private Map<String, Object> getUserInfoFromKeycloak(String accessToken) {
        String userInfoUrl = String.format("%s/realms/%s/protocol/openid-connect/userinfo", keycloakUrl, realm);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                userInfoUrl, HttpMethod.GET, request, Map.class);

        if (response.getStatusCode() == HttpStatus.OK) {
            Map<String, Object> userInfo = response.getBody();

            // Если preferred_username отсутствует, пытаемся получить username из access token
            if (!userInfo.containsKey("preferred_username") || userInfo.get("preferred_username") == null) {
                // Декодируем access token (JWT) для получения username
                String[] parts = accessToken.split("\\.");
                if (parts.length == 3) {
                    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, Object> claims = null;
                    try {
                        claims = mapper.readValue(payload, Map.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }

                    String username = (String) claims.get("preferred_username");
                    if (username == null) {
                        username = (String) claims.get("sub");
                    }
                    userInfo.put("preferred_username", username);
                }
            }

            return userInfo;
        }
        throw new RuntimeException("Failed to get user info from Keycloak");
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

    public Map<String, Object> getUserInfo(String sessionId) {
        if (sessionId == null) {
            return null;
        }

        String key = SESSION_PREFIX + sessionId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        if (entries.isEmpty()) {
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String keyStr = entry.getKey().toString();
            // Не возвращаем refresh token
            if (!"refreshToken".equals(keyStr)) {
                result.put(keyStr, entry.getValue());
            }
        }

        return result;
    }

    public String refreshToken(String sessionId) {
        String key = SESSION_PREFIX + sessionId;

        if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
            throw new RuntimeException("Session not found");
        }

        // Получаем refresh token
        String refreshToken = (String) redisTemplate.opsForHash().get(key, "refreshToken");

        // Получаем новые токены
        Map<String, Object> newTokens = refreshAccessToken(refreshToken);
        String newRefreshToken = (String) newTokens.get("refresh_token");

        // Получаем все данные сессии
        Map<Object, Object> userInfo = redisTemplate.opsForHash().entries(key);

        // Удаляем старую сессию
        redisTemplate.delete(key);

        // Создаем новую сессию
        String newSessionId = UUID.randomUUID().toString();
        String newKey = SESSION_PREFIX + newSessionId;

        // Копируем данные
        for (Map.Entry<Object, Object> entry : userInfo.entrySet()) {
            redisTemplate.opsForHash().put(newKey, entry.getKey(), entry.getValue());
        }
        redisTemplate.opsForHash().put(newKey, "refreshToken", newRefreshToken);
        redisTemplate.expire(newKey, SESSION_TTL, TimeUnit.SECONDS);

        log.debug("Token refreshed: {} -> {}", sessionId, newSessionId);
        return newSessionId;
    }

    private Map<String, Object> refreshAccessToken(String refreshToken) {
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", keycloakUrl, realm);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("refresh_token", refreshToken);

        if (clientSecret != null && !clientSecret.isEmpty()) {
            body.add("client_secret", clientSecret);
        }

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);
        if (response.getStatusCode() == HttpStatus.OK) {
            return response.getBody();
        }
        throw new RuntimeException("Failed to refresh token");
    }

    public void logout(String sessionId) {
        if (sessionId != null) {
            // Получаем refresh token для logout в Keycloak
            String key = SESSION_PREFIX + sessionId;
            String refreshToken = (String) redisTemplate.opsForHash().get(key, "refreshToken");

            if (refreshToken != null) {
                logoutFromKeycloak(refreshToken);
            }

            redisTemplate.delete(key);
            log.debug("Session deleted from Redis: {}", sessionId);
        }
    }

    private void logoutFromKeycloak(String refreshToken) {
        String logoutUrl = String.format("%s/realms/%s/protocol/openid-connect/logout", keycloakUrl, realm);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("refresh_token", refreshToken);

        if (clientSecret != null && !clientSecret.isEmpty()) {
            body.add("client_secret", clientSecret);
        }

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForEntity(logoutUrl, request, String.class);
        } catch (Exception e) {
            log.warn("Error during Keycloak logout: {}", e.getMessage());
        }
    }
}