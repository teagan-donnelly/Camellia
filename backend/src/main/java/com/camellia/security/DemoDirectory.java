package com.camellia.security;

import java.util.List;
import java.util.Optional;

/**
 * Central registry of the fictional demo users. Practice memberships here are matched against the
 * PATIENT_PRACTICE_ASSOCIATIONS table to compute each physician's patient scope.
 */
public final class DemoDirectory {

    public static final String PRACTICE_A = "PRAC-A";
    public static final String PRACTICE_B = "PRAC-B";

    public static final List<DemoUser> USERS = List.of(
            new DemoUser("alex.kim", "Dr. Alex Kim", Role.PHYSICIAN, "Internal Medicine — Practice A", List.of(PRACTICE_A)),
            new DemoUser("sarah.mitchell", "Dr. Sarah Mitchell", Role.PHYSICIAN, "Obstetrics & Gynecology — Practice B", List.of(PRACTICE_B)),
            new DemoUser("jordan.lee", "Jordan Lee, RN", Role.REGISTERED_NURSE, "Medical-Surgical Nursing", List.of()),
            new DemoUser("taylor.morgan", "Taylor Morgan, RN", Role.LD_NURSE, "Labor & Delivery", List.of()),
            new DemoUser("maya.ellison", "Maya Ellison, CRNA", Role.CRNA, "Anesthesia OB Coordination", List.of()),
            new DemoUser("daniel.brooks", "Dr. Daniel Brooks", Role.ANESTHESIOLOGIST, "Obstetric Anesthesiology", List.of()));

    public static final String DEFAULT_USER = "maya.ellison";

    private DemoDirectory() {}

    public static Optional<DemoUser> find(String id) {
        if (id == null) return Optional.empty();
        return USERS.stream().filter(u -> u.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    public static DemoUser require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown demo user " + id));
    }
}
