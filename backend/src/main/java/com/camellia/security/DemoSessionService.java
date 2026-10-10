package com.camellia.security;

import com.camellia.config.CamelliaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * DEMO IMPERSONATION, not authentication: the server issues an HMAC-signed session token naming the selected demo user
 * and only trusts identities carried by a valid token (never a raw user id from the client). Anyone using the app can
 * still choose any demo user — that is the point of the hackathon demo — so this is not a security boundary for real data.
 */
@Service
public class DemoSessionService {

    private static final Logger log = LoggerFactory.getLogger(DemoSessionService.class);
    public static final String COOKIE = "CAMELLIA_DEMO_SESSION";
    public static final Duration TTL = Duration.ofHours(12);

    private final byte[] secret;

    public DemoSessionService(CamelliaProperties props) {
        String configured = props.getDemo().getSessionSecret();
        if (configured != null && configured.length() >= 16) {
            secret = configured.getBytes(StandardCharsets.UTF_8);
        } else {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            log.info("Demo session secret generated at startup (sessions reset when the backend restarts). "
                    + "Set CAMELLIA_DEMO_SESSION_SECRET to keep them across restarts.");
        }
    }

    public String issue(DemoUser user) {
        String payload = user.id() + "|" + Instant.now().getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    public Optional<DemoUser> resolve(String token) {
        if (token == null || !token.contains(".")) return Optional.empty();
        String encoded = token.substring(0, token.lastIndexOf('.'));
        String signature = token.substring(token.lastIndexOf('.') + 1);
        if (!MessageDigest.isEqual(sign(encoded).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|");
            long issued = Long.parseLong(parts[1]);
            if (Instant.ofEpochSecond(issued).plus(TTL).isBefore(Instant.now())) return Optional.empty();
            return DemoDirectory.find(parts[0]);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
