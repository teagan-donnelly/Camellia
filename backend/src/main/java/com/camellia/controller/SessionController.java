package com.camellia.controller;

import com.camellia.model.Practice;
import com.camellia.repository.PracticeRepository;
import com.camellia.security.*;
import com.camellia.web.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * SIMULATED demo identity. Choosing a user issues a server-signed session cookie; all other endpoints derive the user
 * from that cookie. This is impersonation for a hackathon demo, not authentication.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    public static final String NOTICE = "Simulated demo identity for a hackathon prototype — not a secure login. Synthetic data only.";

    private final DemoSessionService sessions;
    private final PracticeRepository practices;

    public SessionController(DemoSessionService sessions, PracticeRepository practices) {
        this.sessions = sessions;
        this.practices = practices;
    }

    public record PracticeRef(String practiceId, String name) {}

    public record UserView(String id, String name, String role, String roleLabel, String department, List<PracticeRef> practices,
                           String patientScope) {}

    public record SessionView(UserView user, List<String> permissions, List<String> navigation, boolean simulated, String notice) {}

    @GetMapping("/users")
    public List<UserView> users() {
        Map<String, String> names = practiceNames();
        return DemoDirectory.USERS.stream().map(u -> view(u, names)).toList();
    }

    @GetMapping
    public SessionView current(DemoUser user) {
        return session(user);
    }

    public record SwitchRequest(String userId) {}

    @PostMapping
    public SessionView switchUser(@RequestBody SwitchRequest req, HttpServletResponse response) {
        DemoUser user = DemoDirectory.find(req == null ? null : req.userId())
                .orElseThrow(() -> ApiException.badRequest("Unknown demo user."));
        ResponseCookie cookie = ResponseCookie.from(DemoSessionService.COOKIE, sessions.issue(user))
                .httpOnly(true).sameSite("Strict").path("/").maxAge(DemoSessionService.TTL).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return session(user);
    }

    @DeleteMapping
    public void signOut(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(DemoSessionService.COOKIE, "")
                .httpOnly(true).sameSite("Strict").path("/").maxAge(0).build().toString());
    }

    SessionView session(DemoUser user) {
        // Sidebar entries follow permissions; the backend enforces the same permissions on every endpoint.
        List<String> nav = new ArrayList<>(List.of("DASHBOARD"));
        if (user.has(Permission.CLINICAL_BRIEFING)) nav.add("BRIEFING");
        if (user.has(Permission.VIEW_PATIENTS)) nav.add("PATIENTS");
        if (user.has(Permission.CLINICAL_SEARCH)) nav.add("CLINICAL_SEARCH");
        if (user.has(Permission.CLINICAL_ALERTS)) nav.add("CLINICAL_ALERTS");
        if (user.has(Permission.ANESTHESIA_OB_ALERTS)) nav.add("ANESTHESIA_OB_ALERTS");
        if (user.has(Permission.NURSING_MONITORING)) nav.add("NURSING_MONITORING");
        List<String> perms = user.role().permissions().stream().map(Enum::name).sorted().toList();
        return new SessionView(view(user, practiceNames()), perms, nav, true, NOTICE);
    }

    private UserView view(DemoUser u, Map<String, String> names) {
        List<PracticeRef> refs = u.practiceIds().stream().map(id -> new PracticeRef(id, names.getOrDefault(id, id))).toList();
        String scope = u.role().networkScope() ? "All synthetic patients in the prototype network"
                : "Patients registered to " + String.join(", ", refs.stream().map(PracticeRef::name).toList());
        return new UserView(u.id(), u.name(), u.role().name(), u.role().label(), u.department(), refs, scope);
    }

    private Map<String, String> practiceNames() {
        Map<String, String> m = new HashMap<>();
        for (Practice p : practices.findAll()) m.put(p.practiceId(), p.name());
        return m;
    }
}
