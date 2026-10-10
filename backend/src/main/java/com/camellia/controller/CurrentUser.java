package com.camellia.controller;

import com.camellia.config.DemoUsers;
import com.camellia.config.DemoUsers.DemoUser;

/** Resolves the SIMULATED user from the X-Camellia-User header (no real authentication in this prototype). */
final class CurrentUser {
    private CurrentUser() {}

    static DemoUser of(String header) {
        return DemoUsers.resolve(header);
    }
}
