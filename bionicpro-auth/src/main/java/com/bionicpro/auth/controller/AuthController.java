package com.bionicpro.auth.controller;

import com.bionicpro.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/token")
    public ResponseEntity<?> exchangeToken(
            @RequestBody Map<String, String> request,
            HttpServletResponse response) {

        String code = request.get("code");
        String redirectUri = request.get("redirect_uri");
        String codeVerifier = request.get("code_verifier");

        log.debug("Exchanging code for token: {}", code);

        try {
            String sessionId = authService.exchangeCode(code, redirectUri, codeVerifier);

            Cookie cookie = new Cookie("bionicpro_session", sessionId);
            cookie.setHttpOnly(true);
            cookie.setSecure(false);
            cookie.setPath("/");
            cookie.setMaxAge(3600);
            response.addCookie(cookie);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Token exchange failed", e);
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/check")
    public ResponseEntity<?> checkAuth(HttpServletRequest request) {
        String sessionId = getSessionId(request);
        boolean authenticated = authService.isAuthenticated(sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("authenticated", authenticated);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = getSessionId(request);

        if (sessionId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "No session");
            return ResponseEntity.status(401).body(error);
        }

        try {
            String newSessionId = authService.refreshToken(sessionId);

            Cookie cookie = new Cookie("bionicpro_session", newSessionId);
            cookie.setHttpOnly(true);
            cookie.setSecure(false);
            cookie.setPath("/");
            cookie.setMaxAge(3600);
            response.addCookie(cookie);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(401).body(error);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = getSessionId(request);
        authService.logout(sessionId);

        Cookie cookie = new Cookie("bionicpro_session", null);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    private String getSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("bionicpro_session".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}