package com.camellia.config;

import com.camellia.analysis.AnalysisService;
import com.camellia.ingestion.DemoDataService;
import com.camellia.integration.snowflake.DatabaseMode;
import com.camellia.integration.snowflake.SchemaInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Ensures the schema exists, seeds the synthetic dataset idempotently and runs the screening engine. */
@Component
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final CamelliaProperties props;
    private final SchemaInitializer schema;
    private final DemoDataService demoData;
    private final AnalysisService analysis;
    private final DatabaseMode mode;

    public StartupInitializer(CamelliaProperties props, SchemaInitializer schema, DemoDataService demoData,
                              AnalysisService analysis, DatabaseMode mode) {
        this.props = props;
        this.schema = schema;
        this.demoData = demoData;
        this.analysis = analysis;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        if (props.getDatabase().isInitSchema()) schema.initialize();
        if (props.getDatabase().isSeedOnStartup()) demoData.seed();
        if (props.getDatabase().isAnalyzeOnStartup()) analysis.run("System (startup)");
        log.info("Camellia ready on {} in {} ms — open http://localhost:5173 (frontend) / http://localhost:8080/api/integrations/status",
                mode.label(), System.currentTimeMillis() - start);
    }
}
