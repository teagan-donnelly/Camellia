package com.camellia.integration.gemini;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-request Gemini time budget that keeps retries and the fallback inside an API gateway's timeout. */
class GeminiRequestBudgetTest {

    @Test
    void noBudgetMeansEveryAttemptMayStart() {
        GoogleGenAiGateway.Budget unbounded = new GoogleGenAiGateway.Budget(0, 0);
        assertThat(unbounded.fits(Long.MAX_VALUE / 2, 60_000)).isTrue();
    }

    @Test
    void anotherAttemptStartsOnlyWhileAFullTimeoutStillFits() {
        // 25 s budget starting at t=1 s, so the deadline is t=26 s; each attempt may take up to 12 s.
        GoogleGenAiGateway.Budget budget = new GoogleGenAiGateway.Budget(1_000, 25);
        assertThat(budget.fits(13_000, 12_000)).isTrue();    // ends by 25 s
        assertThat(budget.fits(14_000, 12_000)).isTrue();    // ends exactly at the deadline
        assertThat(budget.fits(14_001, 12_000)).isFalse();   // would overrun
    }
}
