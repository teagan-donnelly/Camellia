package com.camellia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed view of the {@code camellia.*} configuration tree in application.yml. */
@ConfigurationProperties(prefix = "camellia")
public class CamelliaProperties {

    private String corsOrigins = "http://localhost:5173";
    private Database database = new Database();
    private Snowflake snowflake = new Snowflake();
    private Gemini gemini = new Gemini();
    private Demo demo = new Demo();
    private Rules rules = new Rules();

    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String corsOrigins) { this.corsOrigins = corsOrigins; }
    public Database getDatabase() { return database; }
    public void setDatabase(Database database) { this.database = database; }
    public Snowflake getSnowflake() { return snowflake; }
    public void setSnowflake(Snowflake snowflake) { this.snowflake = snowflake; }
    public Gemini getGemini() { return gemini; }
    public void setGemini(Gemini gemini) { this.gemini = gemini; }
    public Demo getDemo() { return demo; }
    public void setDemo(Demo demo) { this.demo = demo; }
    public Rules getRules() { return rules; }
    public void setRules(Rules rules) { this.rules = rules; }

    public static class Database {
        private String mode = "auto";
        private boolean initSchema = true;
        private boolean seedOnStartup = true;
        private boolean analyzeOnStartup = true;
        private String localUrl = "jdbc:h2:file:./data/camellia-local";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public boolean isInitSchema() { return initSchema; }
        public void setInitSchema(boolean initSchema) { this.initSchema = initSchema; }
        public boolean isSeedOnStartup() { return seedOnStartup; }
        public void setSeedOnStartup(boolean seedOnStartup) { this.seedOnStartup = seedOnStartup; }
        public boolean isAnalyzeOnStartup() { return analyzeOnStartup; }
        public void setAnalyzeOnStartup(boolean analyzeOnStartup) { this.analyzeOnStartup = analyzeOnStartup; }
        public String getLocalUrl() { return localUrl; }
        public void setLocalUrl(String localUrl) { this.localUrl = localUrl; }
    }

    public static class Snowflake {
        private String account = "";
        private String user = "";
        private String privateKeyFile = "";
        private String privateKeyPassphrase = "";
        private String warehouse = "";
        private String database = "CAMELLIA_DEMO";
        private String schema = "CLINICAL";
        private String role = "";
        private boolean createDatabaseIfMissing = true;

        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPrivateKeyFile() { return privateKeyFile; }
        public void setPrivateKeyFile(String privateKeyFile) { this.privateKeyFile = privateKeyFile; }
        public String getPrivateKeyPassphrase() { return privateKeyPassphrase; }
        public void setPrivateKeyPassphrase(String privateKeyPassphrase) { this.privateKeyPassphrase = privateKeyPassphrase; }
        public String getWarehouse() { return warehouse; }
        public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public boolean isCreateDatabaseIfMissing() { return createDatabaseIfMissing; }
        public void setCreateDatabaseIfMissing(boolean v) { this.createDatabaseIfMissing = v; }
    }

    public static class Gemini {
        private String apiKey = "";
        private String model = "gemini-3.8-flash";
        private int timeoutSeconds = 60;
        private int maxContextChars = 28000;
        private int maxHistoryTurns = 8;
        private String fallbackModel = "";
        private String thinkingLevel = "low";
        private int maxRetries = 2;

        public String getFallbackModel() { return fallbackModel; }
        public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }
        public String getThinkingLevel() { return thinkingLevel; }
        public void setThinkingLevel(String thinkingLevel) { this.thinkingLevel = thinkingLevel; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxContextChars() { return maxContextChars; }
        public void setMaxContextChars(int maxContextChars) { this.maxContextChars = maxContextChars; }
        public int getMaxHistoryTurns() { return maxHistoryTurns; }
        public void setMaxHistoryTurns(int maxHistoryTurns) { this.maxHistoryTurns = maxHistoryTurns; }
    }

    public static class Demo {
        private String anchorDate = "";

        public String getAnchorDate() { return anchorDate; }
        public void setAnchorDate(String anchorDate) { this.anchorDate = anchorDate; }
    }

    public static class Rules {
        private ObAnesthesia obAnesthesia = new ObAnesthesia();
        private CountRule recurrentFracture = new CountRule(3, 1825);
        private CountRule repeatedAbnormalLab = new CountRule(3, 730);
        private CountRule repeatedSymptom = new CountRule(3, 365);

        public ObAnesthesia getObAnesthesia() { return obAnesthesia; }
        public void setObAnesthesia(ObAnesthesia obAnesthesia) { this.obAnesthesia = obAnesthesia; }
        public CountRule getRecurrentFracture() { return recurrentFracture; }
        public void setRecurrentFracture(CountRule recurrentFracture) { this.recurrentFracture = recurrentFracture; }
        public CountRule getRepeatedAbnormalLab() { return repeatedAbnormalLab; }
        public void setRepeatedAbnormalLab(CountRule repeatedAbnormalLab) { this.repeatedAbnormalLab = repeatedAbnormalLab; }
        public CountRule getRepeatedSymptom() { return repeatedSymptom; }
        public void setRepeatedSymptom(CountRule repeatedSymptom) { this.repeatedSymptom = repeatedSymptom; }
    }

    public static class ObAnesthesia {
        private int lookaheadDays = 120;
        private int labLookbackDays = 300;
        private int expeditedWithinDays = 21;
        private int reviewLeadDays = 7;
        private List<String> plateletTestCodes = new ArrayList<>(List.of("777-3"));
        private double plateletHighlightBelow = 100;
        private Map<String, List<String>> relevantConditionTerms = new LinkedHashMap<>();
        private List<String> anticoagulantTerms = new ArrayList<>();

        public int getLookaheadDays() { return lookaheadDays; }
        public void setLookaheadDays(int lookaheadDays) { this.lookaheadDays = lookaheadDays; }
        public int getLabLookbackDays() { return labLookbackDays; }
        public void setLabLookbackDays(int labLookbackDays) { this.labLookbackDays = labLookbackDays; }
        public int getExpeditedWithinDays() { return expeditedWithinDays; }
        public void setExpeditedWithinDays(int expeditedWithinDays) { this.expeditedWithinDays = expeditedWithinDays; }
        public int getReviewLeadDays() { return reviewLeadDays; }
        public void setReviewLeadDays(int reviewLeadDays) { this.reviewLeadDays = reviewLeadDays; }
        public List<String> getPlateletTestCodes() { return plateletTestCodes; }
        public void setPlateletTestCodes(List<String> plateletTestCodes) { this.plateletTestCodes = plateletTestCodes; }
        public double getPlateletHighlightBelow() { return plateletHighlightBelow; }
        public void setPlateletHighlightBelow(double plateletHighlightBelow) { this.plateletHighlightBelow = plateletHighlightBelow; }
        public Map<String, List<String>> getRelevantConditionTerms() { return relevantConditionTerms; }
        public void setRelevantConditionTerms(Map<String, List<String>> terms) { this.relevantConditionTerms = terms; }
        public List<String> getAnticoagulantTerms() { return anticoagulantTerms; }
        public void setAnticoagulantTerms(List<String> anticoagulantTerms) { this.anticoagulantTerms = anticoagulantTerms; }
    }

    public static class CountRule {
        private int minEncounters;
        private int minResults;
        private int lookbackDays;

        public CountRule() {}

        public CountRule(int min, int lookbackDays) {
            this.minEncounters = min;
            this.minResults = min;
            this.lookbackDays = lookbackDays;
        }

        public int getMinEncounters() { return minEncounters; }
        public void setMinEncounters(int minEncounters) { this.minEncounters = minEncounters; }
        public int getMinResults() { return minResults; }
        public void setMinResults(int minResults) { this.minResults = minResults; }
        public int getLookbackDays() { return lookbackDays; }
        public void setLookbackDays(int lookbackDays) { this.lookbackDays = lookbackDays; }
    }
}
