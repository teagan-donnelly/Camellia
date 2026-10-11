package com.camellia.workforce;

import com.camellia.config.CamelliaProperties;
import com.camellia.model.Organization;
import com.camellia.repository.OrganizationRepository;
import com.camellia.repository.WorkforceRepository;
import com.camellia.repository.WorkforceRepository.RosterShift;
import com.camellia.security.DemoUser;
import com.camellia.security.Permission;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The signed-in user's simulated shift schedule for the dashboard: current and next shift, later shifts this week,
 * open shifts in the user's own units, and staffing coverage. Synthetic data only. Times are hospital wall-clock times.
 */
@Service
public class ShiftScheduleService {

    /** Shifts that ended more than this long ago are no longer listed. */
    static final int COMPLETED_VISIBLE_HOURS = 12;

    private final WorkforceRepository workforce;
    private final OrganizationRepository organizations;
    private final ZoneId zone;

    public ShiftScheduleService(WorkforceRepository workforce, OrganizationRepository organizations, CamelliaProperties props) {
        this.workforce = workforce;
        this.organizations = organizations;
        this.zone = ZoneId.of(props.getMonitoring().getHospitalTimeZone());
    }

    /**
     * @param timing     IN_PROGRESS | UPCOMING | COMPLETED (relative to now in the hospital time zone)
     * @param coverage   FULL | SHORT (one below target) | UNDERSTAFFED (two or more below), or null without a target
     */
    public record ShiftEntry(String scheduleId, String label, String orgName, String department, String unit,
                             LocalDateTime startAt, LocalDateTime endAt, String status, String timing, Integer staffed,
                             Integer target, String coverage, String note, String linkedShiftId, boolean briefingAvailable) {}

    public record Schedule(String timeZone, LocalDateTime now, ShiftEntry current, ShiftEntry next, List<ShiftEntry> myShifts,
                           List<ShiftEntry> openShifts) {}

    public Schedule forUser(DemoUser user) {
        return forUser(user, LocalDateTime.now(zone));
    }

    Schedule forUser(DemoUser user, LocalDateTime now) {
        Map<String, String> orgNames = organizations.findAll().stream()
                .collect(Collectors.toMap(Organization::orgId, Organization::name));
        boolean briefing = user.has(Permission.CLINICAL_BRIEFING);
        List<ShiftEntry> mine = workforce.rosterForUser(user.id()).stream()
                .filter(r -> r.endAt().isAfter(now.minusHours(COMPLETED_VISIBLE_HOURS)))
                .map(r -> entry(r, now, orgNames, briefing))
                .toList();
        ShiftEntry current = mine.stream().filter(e -> e.timing().equals("IN_PROGRESS")).findFirst().orElse(null);
        ShiftEntry next = mine.stream().filter(e -> e.timing().equals("UPCOMING")).findFirst().orElse(null);

        // Open shifts only in units where this user works (never other departments' staffing).
        Set<String> units = new LinkedHashSet<>();
        workforce.rosterForUser(user.id()).forEach(r -> units.add(r.orgId() + "|" + r.department()));
        List<ShiftEntry> open = new ArrayList<>();
        for (String u : units) {
            String[] k = u.split("\\|", 2);
            workforce.openShifts(k[0], k[1]).stream()
                    .filter(r -> r.startAt().isAfter(now))
                    .forEach(r -> open.add(entry(r, now, orgNames, false)));
        }
        open.sort(Comparator.comparing(ShiftEntry::startAt));
        return new Schedule(zone.getId(), now, current, next, mine, open);
    }

    private static ShiftEntry entry(RosterShift r, LocalDateTime now, Map<String, String> orgNames, boolean briefing) {
        String timing = now.isBefore(r.startAt()) ? "UPCOMING" : now.isBefore(r.endAt()) ? "IN_PROGRESS" : "COMPLETED";
        return new ShiftEntry(r.scheduleId(), r.label(), orgNames.getOrDefault(r.orgId(), r.orgId()), r.department(), r.unit(),
                r.startAt(), r.endAt(), r.status(), timing, r.staffed(), r.target(), coverage(r.staffed(), r.target()),
                r.note(), r.linkedShiftId(), briefing && r.linkedShiftId() != null);
    }

    static String coverage(Integer staffed, Integer target) {
        if (staffed == null || target == null || target <= 0) return null;
        int gap = target - staffed;
        return gap <= 0 ? "FULL" : gap == 1 ? "SHORT" : "UNDERSTAFFED";
    }
}
