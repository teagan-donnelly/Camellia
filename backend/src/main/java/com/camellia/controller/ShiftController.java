package com.camellia.controller;

import com.camellia.security.DemoUser;
import com.camellia.workforce.ShiftScheduleService;
import com.camellia.workforce.ShiftScheduleService.Schedule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own simulated shift schedule (dashboard). Other users' schedules are never returned. */
@RestController
@RequestMapping("/api/shifts")
public class ShiftController {

    private final ShiftScheduleService schedule;

    public ShiftController(ShiftScheduleService schedule) {
        this.schedule = schedule;
    }

    @GetMapping("/schedule")
    public Schedule schedule(DemoUser user) {
        return schedule.forUser(user);
    }
}
