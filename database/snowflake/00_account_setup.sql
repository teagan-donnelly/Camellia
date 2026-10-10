-- =============================================================================
-- Camellia — one-time Snowflake account setup (run in a Snowsight worksheet).
-- Replace the <PLACEHOLDERS>. Do NOT commit real values or keys.
--
-- The backend creates the tables itself on startup (backend/src/main/resources/db/schema-snowflake.sql)
-- and seeds the synthetic demo data idempotently, so after this script you only need backend/.env.
-- =============================================================================

-- 1) Warehouse, database and schema (skip what already exists).
USE ROLE SYSADMIN;
CREATE WAREHOUSE IF NOT EXISTS CAMELLIA_WH
    WAREHOUSE_SIZE = 'XSMALL' AUTO_SUSPEND = 60 AUTO_RESUME = TRUE INITIALLY_SUSPENDED = TRUE;
CREATE DATABASE IF NOT EXISTS CAMELLIA_DEMO;
CREATE SCHEMA IF NOT EXISTS CAMELLIA_DEMO.CLINICAL;

-- 2) Role for the app (optional; you can also use an existing role that owns the schema).
USE ROLE SECURITYADMIN;
CREATE ROLE IF NOT EXISTS CAMELLIA_APP;
GRANT USAGE ON WAREHOUSE CAMELLIA_WH TO ROLE CAMELLIA_APP;
GRANT USAGE ON DATABASE CAMELLIA_DEMO TO ROLE CAMELLIA_APP;
GRANT USAGE, CREATE TABLE ON SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_APP;
GRANT SELECT, INSERT, UPDATE, DELETE ON FUTURE TABLES IN SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_APP;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_APP;

-- 3) Key-pair authentication for the user the backend connects as.
--    Generate keys locally (Git Bash / macOS / Linux):
--      openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out rsa_key.p8 -nocrypt
--      openssl rsa -in rsa_key.p8 -pubout -out rsa_key.pub
--    Paste the body of rsa_key.pub (without the BEGIN/END lines) below.
--    Keep rsa_key.p8 OUTSIDE the repository (e.g. ~/.snowflake/rsa_key.p8).
CREATE USER IF NOT EXISTS <CAMELLIA_USER> DEFAULT_ROLE = CAMELLIA_APP DEFAULT_WAREHOUSE = CAMELLIA_WH TYPE = SERVICE;
ALTER USER <CAMELLIA_USER> SET RSA_PUBLIC_KEY = '<PASTE_PUBLIC_KEY_BODY>';
GRANT ROLE CAMELLIA_APP TO USER <CAMELLIA_USER>;

-- 4) Verify:
--    DESC USER <CAMELLIA_USER>;   -- RSA_PUBLIC_KEY_FP should be populated
--    SELECT CURRENT_ACCOUNT(), CURRENT_REGION();
--    Account identifier for SNOWFLAKE_ACCOUNT = <orgname>-<account_name> (Snowsight: Admin > Accounts).
--
-- If the role cannot create databases, keep SNOWFLAKE_CREATE_DATABASE=false in backend/.env
-- (the database and schema above must then already exist).
