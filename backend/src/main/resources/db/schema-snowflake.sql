-- =============================================================================
-- Camellia — Snowflake schema (canonical DDL)
-- Synthetic demo data only. Not for clinical use.
--
-- Run automatically by the backend at startup (CAMELLIA_INIT_SCHEMA=true), or
-- manually in a Snowsight worksheet after: USE SCHEMA <database>.<schema>;
--
-- Notes on Snowflake semantics:
--  * PRIMARY KEY / FOREIGN KEY constraints are informational (not enforced) in
--    standard Snowflake tables. Uniqueness and de-duplication are enforced by the
--    application (deterministic IDs + DEDUP_KEY lookups).
--  * TIMESTAMP_NTZ columns hold UTC wall-clock time (the JDBC session uses TIMEZONE=UTC).
--  * Every source item also gets a CLINICAL_RECORDS row, so RECORD_ID is the single
--    citation namespace used by flags, alerts and Gemini answers.
-- =============================================================================

CREATE TABLE IF NOT EXISTS HEALTHCARE_ORGANIZATIONS (
    ORG_ID          VARCHAR(32)  NOT NULL PRIMARY KEY,
    NAME            VARCHAR(200) NOT NULL,
    ORG_TYPE        VARCHAR(64),
    SOURCE_SYSTEM   VARCHAR(200),
    SOURCE_FORMAT   VARCHAR(64),
    CITY            VARCHAR(100),
    STATE           VARCHAR(32),
    IS_SYNTHETIC    BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS PATIENTS (
    PATIENT_ID          VARCHAR(32)  NOT NULL PRIMARY KEY,
    DISPLAY_ID          VARCHAR(32)  NOT NULL,
    FIRST_NAME          VARCHAR(100) NOT NULL,
    LAST_NAME           VARCHAR(100) NOT NULL,
    DATE_OF_BIRTH       DATE,
    SEX                 VARCHAR(16),
    IS_OBSTETRIC        BOOLEAN DEFAULT FALSE,
    ESTIMATED_DUE_DATE  DATE,
    GESTATIONAL_NOTE    VARCHAR(200),
    PRIMARY_ORG_ID      VARCHAR(32),
    SUMMARY             VARCHAR(2000),
    DEMO_SCENARIO       VARCHAR(200),
    IS_SYNTHETIC        BOOLEAN DEFAULT TRUE,
    CREATED_AT          TIMESTAMP_NTZ
);

-- Master patient index: one internal patient, many source-system MRNs.
CREATE TABLE IF NOT EXISTS PATIENT_SOURCE_IDENTIFIERS (
    PATIENT_ID   VARCHAR(32) NOT NULL,
    ORG_ID       VARCHAR(32) NOT NULL,
    SOURCE_MRN   VARCHAR(64) NOT NULL
);

-- Practices (role-based access): physicians may only see patients registered to their practice.
-- Added in iteration 2 with CREATE TABLE IF NOT EXISTS; no existing table changes.
CREATE TABLE IF NOT EXISTS PRACTICES (
    PRACTICE_ID   VARCHAR(32)  NOT NULL PRIMARY KEY,
    NAME          VARCHAR(200) NOT NULL,
    ORG_ID        VARCHAR(32),
    SPECIALTY     VARCHAR(100),
    IS_SYNTHETIC  BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS PATIENT_PRACTICE_ASSOCIATIONS (
    PATIENT_ID     VARCHAR(32) NOT NULL,
    PRACTICE_ID    VARCHAR(32) NOT NULL,
    RELATIONSHIP   VARCHAR(64),
    REGISTERED_AT  TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS ENCOUNTERS (
    ENCOUNTER_ID         VARCHAR(64) NOT NULL PRIMARY KEY,
    RECORD_ID            VARCHAR(64) NOT NULL,
    PATIENT_ID           VARCHAR(32) NOT NULL,
    ORG_ID               VARCHAR(32) NOT NULL,
    ENCOUNTER_TYPE       VARCHAR(64),
    DEPARTMENT           VARCHAR(100),
    SPECIALTY            VARCHAR(100),
    PROVIDER_NAME        VARCHAR(200),
    START_TIME           TIMESTAMP_NTZ,
    REASON               VARCHAR(1000),
    SYMPTOM_CATEGORY     VARCHAR(64),
    IS_FRACTURE_RELATED  BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS CLINICAL_RECORDS (
    RECORD_ID         VARCHAR(64)  NOT NULL PRIMARY KEY,
    PATIENT_ID        VARCHAR(32)  NOT NULL,
    ORG_ID            VARCHAR(32)  NOT NULL,
    ENCOUNTER_ID      VARCHAR(64),
    RECORD_TYPE       VARCHAR(32)  NOT NULL,   -- NOTE | LAB_RESULT | CONDITION | MEDICATION | ENCOUNTER | APPOINTMENT | IMAGING
    TITLE             VARCHAR(500) NOT NULL,
    RECORD_DATE       TIMESTAMP_NTZ,
    AUTHOR            VARCHAR(200),
    SPECIALTY         VARCHAR(100),
    CONTENT_TEXT      VARCHAR,                 -- normalized, human-readable text used for search and AI context
    SOURCE_FORMAT     VARCHAR(64),             -- FHIR_R4_JSON | LEGACY_JSON | HL7V2_ORU | PLAINTEXT_NOTE
    SOURCE_RECORD_ID  VARCHAR(128),            -- identifier in the originating (fictional) system
    RAW_CONTENT       VARCHAR,                 -- original source payload as received
    CATEGORY_TAGS     VARCHAR(1000),           -- deterministic concept tags, pipe-delimited: |FRACTURE|EMERGENCY|
    INGESTED_AT       TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS LAB_RESULTS (
    LAB_ID          VARCHAR(64) NOT NULL PRIMARY KEY,
    RECORD_ID       VARCHAR(64) NOT NULL,
    PATIENT_ID      VARCHAR(32) NOT NULL,
    ORG_ID          VARCHAR(32) NOT NULL,
    TEST_CODE       VARCHAR(32),
    TEST_NAME       VARCHAR(200),
    VALUE_NUM       NUMBER(14,4),
    VALUE_TEXT      VARCHAR(200),
    UNIT            VARCHAR(64),
    REF_LOW         NUMBER(14,4),
    REF_HIGH        NUMBER(14,4),
    REF_TEXT        VARCHAR(100),
    ABNORMAL_FLAG   VARCHAR(8),
    COLLECTED_AT    TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS MEDICAL_CONDITIONS (
    CONDITION_ID      VARCHAR(64) NOT NULL PRIMARY KEY,
    RECORD_ID         VARCHAR(64) NOT NULL,
    PATIENT_ID        VARCHAR(32) NOT NULL,
    ORG_ID            VARCHAR(32) NOT NULL,
    CODE              VARCHAR(32),
    CODE_SYSTEM       VARCHAR(64),
    DISPLAY           VARCHAR(500),
    CLINICAL_STATUS   VARCHAR(32),
    ONSET_DATE        DATE,
    RECORDED_BY       VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS MEDICATIONS (
    MEDICATION_ID   VARCHAR(64) NOT NULL PRIMARY KEY,
    RECORD_ID       VARCHAR(64) NOT NULL,
    PATIENT_ID      VARCHAR(32) NOT NULL,
    ORG_ID          VARCHAR(32) NOT NULL,
    NAME            VARCHAR(200),
    DOSE            VARCHAR(100),
    ROUTE           VARCHAR(64),
    FREQUENCY       VARCHAR(100),
    STATUS          VARCHAR(32),
    START_DATE      DATE,
    END_DATE        DATE,
    PRESCRIBER      VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS APPOINTMENTS (
    APPOINTMENT_ID    VARCHAR(64) NOT NULL PRIMARY KEY,
    RECORD_ID         VARCHAR(64) NOT NULL,
    PATIENT_ID        VARCHAR(32) NOT NULL,
    ORG_ID            VARCHAR(32) NOT NULL,
    APPOINTMENT_TYPE  VARCHAR(100),
    DEPARTMENT        VARCHAR(100),
    SCHEDULED_START   TIMESTAMP_NTZ,
    STATUS            VARCHAR(32),
    PROVIDER_NAME     VARCHAR(200),
    DESCRIPTION       VARCHAR(1000),
    IS_OBSTETRIC      BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS CLINICAL_FLAGS (
    FLAG_ID                 VARCHAR(40)  NOT NULL PRIMARY KEY,
    PATIENT_ID              VARCHAR(32)  NOT NULL,
    RULE_ID                 VARCHAR(64)  NOT NULL,
    RULE_VERSION            VARCHAR(16),
    TITLE                   VARCHAR(300) NOT NULL,
    DETECTION_REASON        VARCHAR(4000),
    REVIEW_PRIORITY         VARCHAR(32),   -- EXPEDITED | STANDARD (workflow ordering, not a risk score)
    STATUS                  VARCHAR(32),   -- UNREVIEWED | IN_REVIEW | REVIEWED | DISMISSED
    STATUS_REASON           VARCHAR(1000),
    DEDUP_KEY               VARCHAR(300) NOT NULL,
    EVIDENCE_FINGERPRINT    VARCHAR(64),
    CREATED_AT              TIMESTAMP_NTZ,
    UPDATED_AT              TIMESTAMP_NTZ,
    UPDATED_BY              VARCHAR(200),
    LAST_EVALUATED_AT       TIMESTAMP_NTZ,
    EVIDENCE_UPDATED_AT     TIMESTAMP_NTZ,
    AI_EXPLANATION_JSON     VARCHAR,
    AI_EXPLANATION_MODEL    VARCHAR(100),
    AI_EXPLANATION_AT       TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS FLAG_EVIDENCE (
    FLAG_ID        VARCHAR(40) NOT NULL,
    RECORD_ID      VARCHAR(64) NOT NULL,
    EVIDENCE_ROLE  VARCHAR(32),          -- TRIGGER | SUPPORTING | UPCOMING_ENCOUNTER
    DESCRIPTION    VARCHAR(1000),
    ADDED_AT       TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS COORDINATION_ALERTS (
    ALERT_ID                       VARCHAR(40)  NOT NULL PRIMARY KEY,
    FLAG_ID                        VARCHAR(40)  NOT NULL,
    PATIENT_ID                     VARCHAR(32)  NOT NULL,
    ALERT_TYPE                     VARCHAR(64),     -- ANESTHESIA_OB | MULTIDISCIPLINARY_OB
    TITLE                          VARCHAR(300),
    ASSIGNED_TEAM                  VARCHAR(200),
    STATUS                         VARCHAR(32),     -- NEW | UNDER_REVIEW | COORDINATION_NEEDED | PLAN_DOCUMENTED | RESOLVED | DISMISSED
    PRIORITY                       VARCHAR(32),
    APPOINTMENT_ID                 VARCHAR(64),
    ENCOUNTER_DATE                 TIMESTAMP_NTZ,
    REVIEW_DUE_DATE                DATE,
    ACKNOWLEDGED_BY                VARCHAR(200),
    ACKNOWLEDGED_AT                TIMESTAMP_NTZ,
    CONTACT_STATUS                 VARCHAR(32),     -- NOT_STARTED | PLANNED | COMPLETED
    PLANNING_DISCUSSION_OCCURRED   BOOLEAN DEFAULT FALSE,
    INFO_NEEDED                    BOOLEAN DEFAULT FALSE,
    DISMISS_REASON                 VARCHAR(1000),
    CONCERN_SUMMARY                VARCHAR(2000),
    INVOLVED_SPECIALTIES           VARCHAR(500),
    CREATED_AT                     TIMESTAMP_NTZ,
    UPDATED_AT                     TIMESTAMP_NTZ,
    UPDATED_BY                     VARCHAR(200),
    EVIDENCE_UPDATED_AT            TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS ALERT_EVIDENCE (
    ALERT_ID       VARCHAR(40) NOT NULL,
    RECORD_ID      VARCHAR(64) NOT NULL,
    EVIDENCE_ROLE  VARCHAR(32),
    DESCRIPTION    VARCHAR(1000),
    ADDED_AT       TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS COORDINATION_NOTES (
    NOTE_ID       VARCHAR(40) NOT NULL PRIMARY KEY,
    ALERT_ID      VARCHAR(40) NOT NULL,
    PATIENT_ID    VARCHAR(32) NOT NULL,
    AUTHOR_NAME   VARCHAR(200),
    AUTHOR_ROLE   VARCHAR(200),
    NOTE_TYPE     VARCHAR(32),     -- GENERAL | INFO_REQUEST | CONTACT | PLANNING_DISCUSSION | PLAN_SUMMARY | SYSTEM
    BODY          VARCHAR(4000),
    CREATED_AT    TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS ACTIVITY_LOG (
    ACTIVITY_ID   VARCHAR(40) NOT NULL PRIMARY KEY,
    ENTITY_TYPE   VARCHAR(32),
    ENTITY_ID     VARCHAR(64),
    PATIENT_ID    VARCHAR(32),
    ACTION        VARCHAR(64),
    DETAIL        VARCHAR(2000),
    ACTOR         VARCHAR(200),
    CREATED_AT    TIMESTAMP_NTZ
);

-- Gemini-extracted observations. Each row must cite an existing RECORD_ID and quote text that
-- appears in that record (validated server-side before insert).
CREATE TABLE IF NOT EXISTS AI_OBSERVATIONS (
    OBSERVATION_ID   VARCHAR(40) NOT NULL PRIMARY KEY,
    PATIENT_ID       VARCHAR(32) NOT NULL,
    RECORD_ID        VARCHAR(64) NOT NULL,
    CATEGORY         VARCHAR(64),
    STATEMENT        VARCHAR(2000),
    EVIDENCE_QUOTE   VARCHAR(2000),
    MODEL            VARCHAR(100),
    CREATED_AT       TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS ANALYSIS_RUNS (
    RUN_ID              VARCHAR(40) NOT NULL PRIMARY KEY,
    STARTED_AT          TIMESTAMP_NTZ,
    FINISHED_AT         TIMESTAMP_NTZ,
    PATIENTS_EVALUATED  NUMBER(10,0),
    FLAGS_CREATED       NUMBER(10,0),
    FLAGS_UPDATED       NUMBER(10,0),
    FLAGS_UNCHANGED     NUMBER(10,0),
    ALERTS_CREATED      NUMBER(10,0),
    TRIGGERED_BY        VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS INTEGRATION_CHECKS (
    CHECK_ID      VARCHAR(40) NOT NULL PRIMARY KEY,
    CHECK_TYPE    VARCHAR(32),
    DETAIL        VARCHAR(500),
    CREATED_AT    TIMESTAMP_NTZ
);

CREATE TABLE IF NOT EXISTS DEMO_METADATA (
    META_KEY     VARCHAR(64) NOT NULL PRIMARY KEY,
    META_VALUE   VARCHAR(500),
    UPDATED_AT   TIMESTAMP_NTZ
);

-- Assistant conversation history: the clinician's question and the validated answer text, one row per turn.
-- Every row carries the user + context key, so a conversation can never be continued for another user or patient.
-- Stored here (not in memory) so follow-up questions work across stateless API instances; rows expire after 2 hours.
CREATE TABLE IF NOT EXISTS AI_CONVERSATION_TURNS (
    CONVERSATION_ID  VARCHAR(48)  NOT NULL,
    TURN_NO          NUMBER(10,0) NOT NULL,
    CONTEXT_KEY      VARCHAR(300) NOT NULL,
    SPEAKER          VARCHAR(16)  NOT NULL,
    MESSAGE_TEXT     VARCHAR,
    CREATED_AT       TIMESTAMP_NTZ
);
