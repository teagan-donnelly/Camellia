package com.camellia.security;

import java.util.List;

/** A SIMULATED healthcare professional. Not an authenticated identity. */
public record DemoUser(String id, String name, Role role, String department, List<String> practiceIds) {

    public boolean has(Permission p) {
        return role.permissions().contains(p);
    }

    /** "Role — department" for notes, activity and display. */
    public String roleDescription() {
        return role.label() + " — " + department;
    }
}
