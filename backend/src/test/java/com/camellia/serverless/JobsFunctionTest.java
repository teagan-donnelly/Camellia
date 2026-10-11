package com.camellia.serverless;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The camellia-jobs function: events forwarded by Lambda Web Adapter to POST /events run owner-only jobs. */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:jobs-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key=",
        "camellia.demo.anchor-date=",
        "camellia.jobs.enabled=true"
})
@AutoConfigureMockMvc
class JobsFunctionTest {

    @Autowired MockMvc mvc;

    ResultActions event(String json) throws Exception {
        return mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void scheduledScreeningRunsFromAnEvent() throws Exception {
        event("{\"job\":\"screening\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job").value("screening"))
                .andExpect(jsonPath("$.screening.patientsEvaluated").value(9))
                .andExpect(jsonPath("$.screening.flagsCreated").value(0));
    }

    @Test
    void bootstrapIsSafeToRepeat() throws Exception {
        // The context already seeded at startup, so a second bootstrap inserts nothing new.
        event("{\"job\":\"bootstrap\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsInserted").value(0))
                .andExpect(jsonPath("$.screening.patientsEvaluated").value(9));
    }

    @Test
    void simulatedLabResultIsIngestedAndScreened() throws Exception {
        event("{\"job\":\"simulate-lab-result\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screening.runId").exists());
    }

    @Test
    void unknownJobsAreRejected() throws Exception {
        event("{\"job\":\"drop-everything\"}").andExpect(status().isBadRequest());
        event("{}").andExpect(status().isBadRequest());
    }
}
