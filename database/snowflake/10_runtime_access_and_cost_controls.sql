-- =============================================================================
-- Camellia — least-privilege runtime access and cost controls (run in a Snowsight SQL worksheet).
--
-- Builds on 00_account_setup.sql. Additive only: no DROP, REPLACE or TRUNCATE, and no data changes.
-- Run it as the admin user you created when signing up (ACCOUNTADMIN, SYSADMIN and SECURITYADMIN),
-- AFTER a backend from the feature/camellia-v3 branch (or later) has started once against Snowflake, so all CLINICAL
-- tables exist (37 schema statements: the original tables plus AI_CONVERSATION_TURNS and the V3 tables).
--
-- Choose before running:
--   * <PASTE_RUNTIME_PUBLIC_KEY_BODY> in step 4 (SERVERLESS_ARCHITECTURE.md §8.6 generates the key pair)
--   * the monthly credit quotas in step 5 (an XSMALL warehouse uses about 1 credit per running hour; Gen2 somewhat more)
--
-- Result:
--   CAMELLIA_APP          existing role, now the OWNER/DEPLOY role: creates tables, seeds and resets demo data.
--                         Used by local development and the camellia-jobs function only. Never internet-facing.
--   CAMELLIA_RUNTIME      new: what the internet-facing API needs. Reads every app table, writes only what the
--                         features below need. No DDL; source medical records can be added (imports) but never
--                         updated or deleted.
--   CAMELLIA_RUNTIME_SVC  new key-pair service user that holds ONLY CAMELLIA_RUNTIME.
--   CAMELLIA_READER       new read-only role for teammates exploring the data in Snowsight.
--   CAMELLIA_API_WH       new XSMALL warehouse for API traffic, with short statement timeouts. New warehouses in
--                         this account default to the second generation (Gen2); its hourly credit rate is in
--                         Snowflake's Service Consumption Table.
--   Resource monitors     hard monthly credit caps on CAMELLIA_API_WH and CAMELLIA_WH.
--
-- Running the API as CAMELLIA_RUNTIME intentionally disables "Reset demo data" and "Simulate lab result"
-- (both write source records); run those with the owner role instead.
-- Targets CAMELLIA_DEMO. For a separate hosted database, replace CAMELLIA_DEMO throughout before running.
-- =============================================================================

-- 1) Warehouses.
USE ROLE SYSADMIN;

-- Interactive API traffic: lookups and filters over a small synthetic dataset. A statement still running after a
-- minute is a bug or abuse, so it is cancelled.
CREATE WAREHOUSE IF NOT EXISTS CAMELLIA_API_WH
    WAREHOUSE_SIZE = 'XSMALL'
    AUTO_SUSPEND = 60
    AUTO_RESUME = TRUE
    INITIALLY_SUSPENDED = TRUE
    STATEMENT_TIMEOUT_IN_SECONDS = 60
    STATEMENT_QUEUED_TIMEOUT_IN_SECONDS = 30
    COMMENT = 'Camellia API runtime (synthetic data only)';

-- The existing warehouse now serves bootstrap, seeding, demo reset, screening jobs and humans.
ALTER WAREHOUSE CAMELLIA_WH SET
    STATEMENT_TIMEOUT_IN_SECONDS = 900
    STATEMENT_QUEUED_TIMEOUT_IN_SECONDS = 300
    COMMENT = 'Camellia jobs, bootstrap and ad-hoc analysis (synthetic data only)';

-- 2) Managed access: only the schema owner (SYSADMIN) and SECURITYADMIN can grant access to objects in this
--    schema. CAMELLIA_APP keeps creating and owning its tables but can no longer hand out access to them.
--    (Fails harmlessly if the schema is owned by another role; 00_account_setup.sql creates it as SYSADMIN.)
ALTER SCHEMA CAMELLIA_DEMO.CLINICAL ENABLE MANAGED ACCESS;

-- 3) Roles and grants.
USE ROLE SECURITYADMIN;

CREATE ROLE IF NOT EXISTS CAMELLIA_RUNTIME COMMENT = 'Camellia internet-facing API: read all, write workflow tables only';
CREATE ROLE IF NOT EXISTS CAMELLIA_READER  COMMENT = 'Camellia read-only access for people (Snowsight)';
GRANT ROLE CAMELLIA_RUNTIME TO ROLE SYSADMIN;   -- keeps the standard role hierarchy: admins can manage both
GRANT ROLE CAMELLIA_READER  TO ROLE SYSADMIN;

-- 3a) Runtime: connect and read.
GRANT USAGE ON WAREHOUSE CAMELLIA_API_WH TO ROLE CAMELLIA_RUNTIME;
GRANT USAGE ON DATABASE CAMELLIA_DEMO TO ROLE CAMELLIA_RUNTIME;
GRANT USAGE ON SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_RUNTIME;
GRANT SELECT ON ALL TABLES IN SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_RUNTIME;
GRANT SELECT ON FUTURE TABLES IN SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_RUNTIME;

-- 3b) Runtime: the writes the API performs, taken from the SQL in backend/src/main/java/com/camellia/repository.
--     A screening run (POST /api/analysis/run) creates/updates flags and alerts and replaces their evidence rows.
GRANT INSERT, UPDATE ON TABLE CAMELLIA_DEMO.CLINICAL.CLINICAL_FLAGS      TO ROLE CAMELLIA_RUNTIME; -- screening + flag review status
GRANT INSERT, DELETE ON TABLE CAMELLIA_DEMO.CLINICAL.FLAG_EVIDENCE       TO ROLE CAMELLIA_RUNTIME; -- evidence replaced per flag
GRANT INSERT, UPDATE ON TABLE CAMELLIA_DEMO.CLINICAL.COORDINATION_ALERTS TO ROLE CAMELLIA_RUNTIME; -- screening + coordination status
GRANT INSERT, DELETE ON TABLE CAMELLIA_DEMO.CLINICAL.ALERT_EVIDENCE      TO ROLE CAMELLIA_RUNTIME; -- evidence replaced per alert
GRANT INSERT         ON TABLE CAMELLIA_DEMO.CLINICAL.COORDINATION_NOTES  TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT         ON TABLE CAMELLIA_DEMO.CLINICAL.ACTIVITY_LOG        TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT         ON TABLE CAMELLIA_DEMO.CLINICAL.ANALYSIS_RUNS       TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, DELETE ON TABLE CAMELLIA_DEMO.CLINICAL.AI_OBSERVATIONS     TO ROLE CAMELLIA_RUNTIME; -- Gemini extraction replaces a patient's set
GRANT INSERT         ON TABLE CAMELLIA_DEMO.CLINICAL.INTEGRATION_CHECKS  TO ROLE CAMELLIA_RUNTIME; -- database read/write check
GRANT INSERT, DELETE ON TABLE CAMELLIA_DEMO.CLINICAL.AI_CONVERSATION_TURNS TO ROLE CAMELLIA_RUNTIME; -- chat history; expired turns deleted

-- 3c) Camellia V3 features.
--     Camellia Briefing: metadata and source references (audio bytes are stored outside the database).
GRANT INSERT, UPDATE ON TABLE CAMELLIA_DEMO.CLINICAL.BRIEFINGS                  TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, DELETE ON TABLE CAMELLIA_DEMO.CLINICAL.BRIEFING_SOURCE_REFERENCES TO ROLE CAMELLIA_RUNTIME;
--     Patient record import (Clinical Data Coordinator role): imports only ADD rows; they never update or delete
--     existing source records. Drop these grants if imports should run only through the owner role.
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.IMPORT_BATCHES                TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.HEALTHCARE_ORGANIZATIONS      TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.PATIENTS                      TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.PATIENT_SOURCE_IDENTIFIERS    TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.PATIENT_PRACTICE_ASSOCIATIONS TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.CLINICAL_RECORDS              TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.ENCOUNTERS                    TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.LAB_RESULTS                   TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.MEDICAL_CONDITIONS            TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.MEDICATIONS                   TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT ON TABLE CAMELLIA_DEMO.CLINICAL.APPOINTMENTS                  TO ROLE CAMELLIA_RUNTIME;
--     Simulated wearable monitoring, nursing documentation and the simulator (DELETE is used only by the simulator's
--     per-stay "reset scenario"; revoke it, and set CAMELLIA_SIMULATOR_ENABLED=false, outside demos).
GRANT INSERT                 ON TABLE CAMELLIA_DEMO.CLINICAL.MONITORING_DEVICES    TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, UPDATE, DELETE ON TABLE CAMELLIA_DEMO.CLINICAL.MONITORING_SESSIONS   TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, DELETE         ON TABLE CAMELLIA_DEMO.CLINICAL.SENSOR_OBSERVATIONS   TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, DELETE         ON TABLE CAMELLIA_DEMO.CLINICAL.ACTIVITY_EVENTS       TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, DELETE         ON TABLE CAMELLIA_DEMO.CLINICAL.MONITORING_CARE_PLANS TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, DELETE         ON TABLE CAMELLIA_DEMO.CLINICAL.NURSING_INTERVENTIONS TO ROLE CAMELLIA_RUNTIME;
GRANT INSERT, UPDATE, DELETE ON TABLE CAMELLIA_DEMO.CLINICAL.MONITORING_ALERTS     TO ROLE CAMELLIA_RUNTIME;
-- Deliberately NOT granted: UPDATE or DELETE on any source-record table, any write on PRACTICES, CLINICIANS,
-- CLINICIAN_PRACTICES, PROVIDER_SHIFTS, SHIFT_PATIENT_ASSIGNMENTS or DEMO_METADATA, and CREATE TABLE. Those belong to
-- seeding (CAMELLIA_APP).

-- 3c) Reader: SELECT only, on the jobs warehouse. Grant it to teammates' own Snowflake users, e.g.
--     GRANT ROLE CAMELLIA_READER TO USER <TEAMMATE_USER>;
GRANT USAGE ON WAREHOUSE CAMELLIA_WH TO ROLE CAMELLIA_READER;
GRANT USAGE ON DATABASE CAMELLIA_DEMO TO ROLE CAMELLIA_READER;
GRANT USAGE ON SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_READER;
GRANT SELECT ON ALL TABLES IN SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_READER;
GRANT SELECT ON FUTURE TABLES IN SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_READER;

-- 4) Runtime service user: its own key pair (never reuse the laptop/owner key), only CAMELLIA_RUNTIME, and no
--    secondary roles, so its sessions can never pick up privileges from another role granted to it later.
--    Paste the public key body WITHOUT the BEGIN/END lines, as in 00_account_setup.sql.
CREATE USER IF NOT EXISTS CAMELLIA_RUNTIME_SVC
    TYPE = SERVICE
    DEFAULT_ROLE = CAMELLIA_RUNTIME
    DEFAULT_WAREHOUSE = CAMELLIA_API_WH
    DEFAULT_NAMESPACE = CAMELLIA_DEMO.CLINICAL
    DEFAULT_SECONDARY_ROLES = ()
    COMMENT = 'Camellia API runtime in the cloud (synthetic data only). Holds only CAMELLIA_RUNTIME.';
ALTER USER CAMELLIA_RUNTIME_SVC SET RSA_PUBLIC_KEY = '<PASTE_RUNTIME_PUBLIC_KEY_BODY>';
GRANT ROLE CAMELLIA_RUNTIME TO USER CAMELLIA_RUNTIME_SVC;

-- Per-identity timeout and query tag (cost attribution in QUERY_HISTORY) without code changes.
ALTER USER CAMELLIA_RUNTIME_SVC SET STATEMENT_TIMEOUT_IN_SECONDS = 60 QUERY_TAG = 'camellia-api';
ALTER USER CAMELLIA_SVC SET QUERY_TAG = 'camellia-owner';

-- 5) Hard monthly credit caps. Quotas below are starting points (roughly one warehouse-hour per day each);
--    set them from your budget. Notifications reach account admins who have a verified email and
--    resource-monitor notifications enabled in Snowsight. When a monitor suspends a warehouse, Camellia returns
--    503 DATABASE_ERROR until the next month or until you raise the quota:
--      ALTER RESOURCE MONITOR CAMELLIA_API_RM SET CREDIT_QUOTA = <n>;
--    Resource monitors cover warehouses only. Serverless features (Snowpipe, serverless tasks, Cortex) need a
--    Budget: Snowsight > Admin > Cost management > Budgets.
USE ROLE ACCOUNTADMIN;

CREATE RESOURCE MONITOR IF NOT EXISTS CAMELLIA_API_RM WITH
    CREDIT_QUOTA = 30
    FREQUENCY = MONTHLY
    START_TIMESTAMP = IMMEDIATELY
    TRIGGERS ON 50 PERCENT DO NOTIFY
             ON 80 PERCENT DO NOTIFY
             ON 100 PERCENT DO SUSPEND
             ON 110 PERCENT DO SUSPEND_IMMEDIATE;

CREATE RESOURCE MONITOR IF NOT EXISTS CAMELLIA_JOBS_RM WITH
    CREDIT_QUOTA = 30
    FREQUENCY = MONTHLY
    START_TIMESTAMP = IMMEDIATELY
    TRIGGERS ON 50 PERCENT DO NOTIFY
             ON 80 PERCENT DO NOTIFY
             ON 100 PERCENT DO SUSPEND
             ON 110 PERCENT DO SUSPEND_IMMEDIATE;

ALTER WAREHOUSE CAMELLIA_API_WH SET RESOURCE_MONITOR = CAMELLIA_API_RM;
ALTER WAREHOUSE CAMELLIA_WH     SET RESOURCE_MONITOR = CAMELLIA_JOBS_RM;

-- Optional, only if this Snowflake account is used for nothing but Camellia: one cap over ALL warehouses.
-- CREATE RESOURCE MONITOR IF NOT EXISTS CAMELLIA_ACCOUNT_RM WITH CREDIT_QUOTA = 60 FREQUENCY = MONTHLY
--     START_TIMESTAMP = IMMEDIATELY TRIGGERS ON 90 PERCENT DO NOTIFY ON 100 PERCENT DO SUSPEND;
-- ALTER ACCOUNT SET RESOURCE_MONITOR = CAMELLIA_ACCOUNT_RM;

-- 6) Later (phase 4, needs static egress IPs such as a NAT gateway): restrict where the runtime user can log in from.
-- USE ROLE SECURITYADMIN;
-- CREATE NETWORK RULE CAMELLIA_DEMO.CLINICAL.CAMELLIA_API_EGRESS MODE = INGRESS TYPE = IPV4 VALUE_LIST = ('<NAT_EIP>/32');
-- CREATE NETWORK POLICY CAMELLIA_RUNTIME_NP ALLOWED_NETWORK_RULE_LIST = ('CAMELLIA_DEMO.CLINICAL.CAMELLIA_API_EGRESS');
-- ALTER USER CAMELLIA_RUNTIME_SVC SET NETWORK_POLICY = CAMELLIA_RUNTIME_NP;

-- 7) Verify.
SHOW GRANTS TO ROLE CAMELLIA_RUNTIME;
SHOW FUTURE GRANTS IN SCHEMA CAMELLIA_DEMO.CLINICAL;
SHOW WAREHOUSES LIKE 'CAMELLIA%';
SHOW RESOURCE MONITORS LIKE 'CAMELLIA%';
DESC USER CAMELLIA_RUNTIME_SVC;   -- RSA_PUBLIC_KEY_FP should start with SHA256:

-- 8) Cost review (run weekly; ACCOUNT_USAGE views lag real time by up to a few hours).
SELECT WAREHOUSE_NAME, DATE_TRUNC('day', START_TIME) AS DAY, ROUND(SUM(CREDITS_USED), 3) AS CREDITS
FROM SNOWFLAKE.ACCOUNT_USAGE.WAREHOUSE_METERING_HISTORY
WHERE WAREHOUSE_NAME LIKE 'CAMELLIA%' AND START_TIME >= DATEADD('day', -30, CURRENT_TIMESTAMP())
GROUP BY 1, 2
ORDER BY 2 DESC, 1;

SELECT QUERY_TAG, WAREHOUSE_NAME, COUNT(*) AS QUERIES, ROUND(SUM(TOTAL_ELAPSED_TIME) / 1000, 1) AS ELAPSED_SECONDS
FROM SNOWFLAKE.ACCOUNT_USAGE.QUERY_HISTORY
WHERE START_TIME >= DATEADD('day', -7, CURRENT_TIMESTAMP()) AND QUERY_TAG LIKE 'camellia%'
GROUP BY 1, 2
ORDER BY 4 DESC;

-- backend/.env values to test the runtime identity locally (Snowflake only; no schema creation, seeding or screening
-- at startup, because CAMELLIA_RUNTIME cannot do them):
--   SNOWFLAKE_USER=CAMELLIA_RUNTIME_SVC
--   SNOWFLAKE_ROLE=CAMELLIA_RUNTIME
--   SNOWFLAKE_WAREHOUSE=CAMELLIA_API_WH
--   SNOWFLAKE_PRIVATE_KEY_FILE=~/.snowflake/camellia_runtime_rsa_key.p8
--   SNOWFLAKE_CREATE_DATABASE=false
--   CAMELLIA_DB_MODE=snowflake
--   CAMELLIA_INIT_SCHEMA=false
--   CAMELLIA_SEED_ON_STARTUP=false
--   CAMELLIA_ANALYZE_ON_STARTUP=false
--   CAMELLIA_DEMO_ADMIN_ENABLED=false   (the runtime role cannot reset or ingest demo data)
