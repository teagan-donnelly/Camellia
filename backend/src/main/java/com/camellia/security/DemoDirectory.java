package com.camellia.security;

import com.camellia.ingestion.Json;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.camellia.ingestion.Json.text;

/**
 * Registry of the fictional workforce (seed/clinicians.json, also persisted to the CLINICIANS table). Practice
 * memberships are matched against PATIENT_PRACTICE_ASSOCIATIONS to compute each physician's patient scope.
 *
 * <p>{@link #USERS} holds only the representative demo accounts offered in the user switcher; {@link #ALL} holds the
 * full synthetic workforce (100 clinicians plus one administrative account).</p>
 */
public final class DemoDirectory {

    public static final String PRACTICE_A = "PRAC-A";
    public static final String PRACTICE_B = "PRAC-B";
    static final String RESOURCE = "seed/clinicians.json";

    /** A seeded identity with the attributes stored in CLINICIANS. */
    public record Clinician(DemoUser user, String orgId, String specialty, String credentials, boolean demoAccount) {}

    public static final List<Clinician> CLINICIANS = load();
    public static final List<DemoUser> ALL = CLINICIANS.stream().map(Clinician::user).toList();
    public static final List<DemoUser> USERS = CLINICIANS.stream().filter(Clinician::demoAccount).map(Clinician::user).toList();
    private static final Map<String, DemoUser> BY_ID = ALL.stream()
            .collect(Collectors.toMap(u -> u.id().toLowerCase(Locale.ROOT), Function.identity()));

    public static final String DEFAULT_USER = "maya.ellison";

    private DemoDirectory() {}

    public static Optional<DemoUser> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(BY_ID.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    public static DemoUser require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown demo user " + id));
    }

    private static List<Clinician> load() {
        String json;
        try {
            json = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + RESOURCE, e);
        }
        List<Clinician> out = new ArrayList<>();
        for (JsonNode n : Json.parse(json)) {
            List<String> practices = new ArrayList<>();
            n.path("practices").forEach(p -> practices.add(p.asString()));
            DemoUser u = new DemoUser(text(n.path("id")), text(n.path("name")), Role.valueOf(text(n.path("role"))),
                    text(n.path("department")), List.copyOf(practices));
            out.add(new Clinician(u, text(n.path("orgId")), text(n.path("specialty")), text(n.path("credentials")),
                    n.path("demoAccount").asBoolean(false)));
        }
        return List.copyOf(out);
    }
}
