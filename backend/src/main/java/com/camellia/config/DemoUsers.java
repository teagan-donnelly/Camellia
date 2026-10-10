package com.camellia.config;

import java.util.List;
import java.util.Optional;

/**
 * SIMULATED users and teams. There is no authentication in this prototype; the frontend sends the selected
 * demo user in the X-Camellia-User header. This must never be used with real patient data.
 */
public final class DemoUsers {

    public record DemoUser(String id, String name, String role, String team) {}

    public static final String HEADER = "X-Camellia-User";

    public static final List<DemoUser> ALL = List.of(
            new DemoUser("maya.ellison", "Maya Ellison, CRNA", "Chief CRNA — Anesthesia OB coordinator", "Anesthesia OB Coordination"),
            new DemoUser("samuel.okafor", "Dr. Samuel Okafor, MD", "Obstetric anesthesiologist", "Anesthesia OB Coordination"),
            new DemoUser("priya.raman", "Dr. Priya Raman, MD", "Maternal-fetal medicine physician", "Maternal-Fetal Medicine"),
            new DemoUser("jordan.lee", "Jordan Lee, RN", "Labor & delivery charge nurse", "Labor & Delivery"),
            new DemoUser("alex.kim", "Dr. Alex Kim, MD", "Primary care clinical reviewer", "Clinical Review"));

    private DemoUsers() {}

    public static DemoUser resolve(String id) {
        return find(id).orElse(ALL.getFirst());
    }

    public static Optional<DemoUser> find(String id) {
        if (id == null) return Optional.empty();
        return ALL.stream().filter(u -> u.id().equalsIgnoreCase(id.trim())).findFirst();
    }
}
