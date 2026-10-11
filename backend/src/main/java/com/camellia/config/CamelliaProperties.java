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
    private Jobs jobs = new Jobs();
    private Monitoring monitoring = new Monitoring();
    private Briefing briefing = new Briefing();
    private ElevenLabs elevenlabs = new ElevenLabs();
    private Documents documents = new Documents();
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
    public Jobs getJobs() { return jobs; }
    public Monitoring getMonitoring() { return monitoring; }
    public void setMonitoring(Monitoring monitoring) { this.monitoring = monitoring; }
    public Briefing getBriefing() { return briefing; }
    public void setBriefing(Briefing briefing) { this.briefing = briefing; }
    public ElevenLabs getElevenlabs() { return elevenlabs; }
    public Documents getDocuments() { return documents; }
    public void setDocuments(Documents v) { this.documents = v; }
    public void setElevenlabs(ElevenLabs elevenlabs) { this.elevenlabs = elevenlabs; }
    public void setJobs(Jobs jobs) { this.jobs = jobs; }
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
        private int requestBudgetSeconds = 0;

        /** Upper bound on one request's total Gemini time across retries and the fallback model. 0 = no bound. */
        public int getRequestBudgetSeconds() { return requestBudgetSeconds; }
        public void setRequestBudgetSeconds(int requestBudgetSeconds) { this.requestBudgetSeconds = requestBudgetSeconds; }
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
        private String sessionSecret = "";
        private boolean adminEnabled = true;

        public boolean isAdminEnabled() { return adminEnabled; }
        public void setAdminEnabled(boolean adminEnabled) { this.adminEnabled = adminEnabled; }
        public String getSessionSecret() { return sessionSecret; }
        public void setSessionSecret(String sessionSecret) { this.sessionSecret = sessionSecret; }
        public String getAnchorDate() { return anchorDate; }
        public void setAnchorDate(String anchorDate) { this.anchorDate = anchorDate; }
    }

    /** The non-HTTP job entry point used by the camellia-jobs function (see JobsController). */
    public static class Jobs {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Simulated wearable monitoring. */
    public static class Monitoring {
        private String hospitalTimeZone = "America/New_York";
        private int staleDataMinutes = 30;
        private boolean simulatorEnabled = true;

        public String getHospitalTimeZone() { return hospitalTimeZone; }
        public void setHospitalTimeZone(String v) { this.hospitalTimeZone = v; }
        public int getStaleDataMinutes() { return staleDataMinutes; }
        public void setStaleDataMinutes(int v) { this.staleDataMinutes = v; }
        public boolean isSimulatorEnabled() { return simulatorEnabled; }
        public void setSimulatorEnabled(boolean v) { this.simulatorEnabled = v; }
    }

    /** Camellia Briefing (Gemini script + ElevenLabs audio). */
    public static class Briefing {
        private int maxAudioSeconds = 480;
        private int wordsPerMinute = 150;
        private int maxScriptAttempts = 3;
        private String audioDir = "./data/briefings";

        public int getMaxAudioSeconds() { return maxAudioSeconds; }
        public void setMaxAudioSeconds(int v) { this.maxAudioSeconds = v; }
        public int getWordsPerMinute() { return wordsPerMinute; }
        public void setWordsPerMinute(int v) { this.wordsPerMinute = v; }
        public int getMaxScriptAttempts() { return maxScriptAttempts; }
        public void setMaxScriptAttempts(int v) { this.maxScriptAttempts = v; }
        public String getAudioDir() { return audioDir; }
        public void setAudioDir(String v) { this.audioDir = v; }
    }

    /** Uploaded patient documents (PDF, DOCX, DOC). Stored outside any public directory. */
    public static class Documents {
        private String storageDir = "./data/documents";
        private int maxUploadMb = 10;
        private int maxExtractedChars = 200_000;

        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String v) { this.storageDir = v; }
        public int getMaxUploadMb() { return maxUploadMb; }
        public void setMaxUploadMb(int v) { this.maxUploadMb = v; }
        public int getMaxExtractedChars() { return maxExtractedChars; }
        public void setMaxExtractedChars(int v) { this.maxExtractedChars = v; }
    }

    /** ElevenLabs text-to-speech. The key stays on the server. */
    public static class ElevenLabs {
        private String apiKey = "";
        private String baseUrl = "https://api.elevenlabs.io";
        private String voiceId = "";
        private String voiceUseCase = "conversational";
        private String modelId = "eleven_multilingual_v2";
        private String outputFormat = "mp3_44100_128";
        private int maxCharacters = 9500;
        private double stability = 0.55;
        private double similarityBoost = 0.75;
        private double style = 0.0;
        private double speed = 1.0;
        private int timeoutSeconds = 180;
        private int maxRetries = 2;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getVoiceId() { return voiceId; }
        public void setVoiceId(String v) { this.voiceId = v; }
        public String getVoiceUseCase() { return voiceUseCase; }
        public void setVoiceUseCase(String v) { this.voiceUseCase = v; }
        public String getModelId() { return modelId; }
        public void setModelId(String v) { this.modelId = v; }
        public String getOutputFormat() { return outputFormat; }
        public void setOutputFormat(String v) { this.outputFormat = v; }
        public int getMaxCharacters() { return maxCharacters; }
        public void setMaxCharacters(int v) { this.maxCharacters = v; }
        public double getStability() { return stability; }
        public void setStability(double v) { this.stability = v; }
        public double getSimilarityBoost() { return similarityBoost; }
        public void setSimilarityBoost(double v) { this.similarityBoost = v; }
        public double getStyle() { return style; }
        public void setStyle(double v) { this.style = v; }
        public double getSpeed() { return speed; }
        public void setSpeed(double v) { this.speed = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { this.maxRetries = v; }
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
