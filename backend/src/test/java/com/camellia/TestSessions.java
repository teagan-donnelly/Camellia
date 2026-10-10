package com.camellia;

import com.camellia.security.DemoSessionService;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Selects a simulated demo user through {@code POST /api/session} and returns the signed session cookie. */
public final class TestSessions {

    private static final Map<String, Cookie> CACHE = new ConcurrentHashMap<>();

    private TestSessions() {}

    public static Cookie as(MockMvc mvc, String userId) {
        // Cache per MockMvc instance: each Spring context may generate its own session secret.
        return CACHE.computeIfAbsent(System.identityHashCode(mvc) + "/" + userId, k -> login(mvc, userId));
    }

    private static Cookie login(MockMvc mvc, String userId) {
        try {
            String setCookie = mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + userId + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
            if (setCookie == null || !setCookie.startsWith(DemoSessionService.COOKIE + "=")) {
                throw new IllegalStateException("No demo session cookie issued for " + userId);
            }
            String value = setCookie.substring(DemoSessionService.COOKIE.length() + 1, setCookie.indexOf(';'));
            return new Cookie(DemoSessionService.COOKIE, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
