package com.camellia.serverless;

import com.camellia.TestSessions;
import com.camellia.ai.ConversationStore;
import com.camellia.config.CamelliaProperties;
import com.camellia.integration.gemini.GeminiGateway.Turn;
import com.camellia.repository.ConversationRepository;
import com.camellia.web.ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internet-facing API as configured for a serverless deployment: demo administration off, no jobs endpoint, and
 * nothing that depends on one long-lived process (conversation history lives in the database).
 */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:cloud-api-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key=",
        "camellia.demo.anchor-date=",
        "camellia.demo.admin-enabled=false"
})
@AutoConfigureMockMvc
class CloudApiConfigurationTest {

    @Autowired MockMvc mvc;
    @Autowired ConversationRepository conversationRepository;
    @Autowired CamelliaProperties props;

    @Test
    void healthCheckNeedsNoSession() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void liveIntegrationChecksNeedADemoSession() throws Exception {
        mvc.perform(post("/api/integrations/database/check"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEMO_SESSION_REQUIRED"));
        mvc.perform(post("/api/integrations/gemini/check"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEMO_SESSION_REQUIRED"));
    }

    @Test
    void demoAdministrationIsOffAndTheUiIsTold() throws Exception {
        Cookie maya = TestSessions.as(mvc, "maya.ellison");
        mvc.perform(post("/api/admin/reset-demo").cookie(maya))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_ADMIN_DISABLED"));
        mvc.perform(post("/api/admin/simulate-lab-result").cookie(maya))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_ADMIN_DISABLED"));
        mvc.perform(get("/api/integrations/status").cookie(maya))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoAdminEnabled").value(false));
    }

    @Test
    void jobsEndpointIsNotServedByTheApi() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("{\"job\":\"reset-demo\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void conversationHistorySurvivesAcrossApiInstances() {
        // Two stores sharing one database stand in for two stateless API instances.
        ConversationStore first = new ConversationStore(conversationRepository, props);
        ConversationStore second = new ConversationStore(conversationRepository, props);
        String context = "maya.ellison@ALERT:ALERT-TEST-1";

        ConversationStore.Conversation started = first.getOrCreate(null, context);
        started.append(Turn.user("Q1"), Turn.model("A1"));

        ConversationStore.Conversation resumed = second.getOrCreate(started.id(), context);
        assertThat(resumed.id()).isEqualTo(started.id());
        assertThat(resumed.recent(8)).extracting(Turn::text).containsExactly("Q1", "A1");

        resumed.append(Turn.user("Q2"), Turn.model("A2"));
        assertThat(first.getOrCreate(started.id(), context).recent(8)).extracting(Turn::text)
                .containsExactly("Q1", "A1", "Q2", "A2");
        assertThat(first.getOrCreate(started.id(), context).recent(1)).extracting(Turn::text)
                .containsExactly("Q2", "A2");

        // Another user (or another patient context) can never continue it.
        assertThatThrownBy(() -> second.getOrCreate(started.id(), "daniel.brooks@ALERT:ALERT-TEST-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different patient or context");

        // An unknown or expired id starts a fresh conversation instead of failing.
        assertThat(second.getOrCreate("CONV-does-not-exist", context).id()).isNotEqualTo("CONV-does-not-exist");
    }
}
