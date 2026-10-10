-- =============================================================================
-- Camellia — one-time Snowflake account setup (run in a Snowsight SQL worksheet).
--
-- The only value to replace is <PASTE_PUBLIC_KEY_BODY> in step 3.
-- Run the whole worksheet as the admin user you created when signing up
-- (that user has the ACCOUNTADMIN, SYSADMIN and SECURITYADMIN roles).
--
-- The backend creates the tables itself on startup and seeds the synthetic demo data,
-- so after this script you only need backend/.env.
-- =============================================================================

-- 1) Warehouse, database and schema.
USE ROLE SYSADMIN;
CREATE WAREHOUSE IF NOT EXISTS CAMELLIA_WH
    WAREHOUSE_SIZE = 'XSMALL' AUTO_SUSPEND = 60 AUTO_RESUME = TRUE INITIALLY_SUSPENDED = TRUE;
CREATE DATABASE IF NOT EXISTS CAMELLIA_DEMO;
CREATE SCHEMA IF NOT EXISTS CAMELLIA_DEMO.CLINICAL;

-- 2) A dedicated role for the app, with just what it needs.
USE ROLE SECURITYADMIN;
CREATE ROLE IF NOT EXISTS CAMELLIA_APP;
GRANT ROLE CAMELLIA_APP TO ROLE SYSADMIN;   -- lets admins see and manage the app's tables
GRANT USAGE ON WAREHOUSE CAMELLIA_WH TO ROLE CAMELLIA_APP;
GRANT USAGE ON DATABASE CAMELLIA_DEMO TO ROLE CAMELLIA_APP;
GRANT USAGE, CREATE TABLE ON SCHEMA CAMELLIA_DEMO.CLINICAL TO ROLE CAMELLIA_APP;

-- 3) A service user that logs in with key-pair authentication (no password).
--    Generate the key pair first (see README → Snowflake setup), then paste the contents of
--    rsa_key.pub WITHOUT the "-----BEGIN PUBLIC KEY-----" / "-----END PUBLIC KEY-----" lines.
--    The line breaks inside the key may stay or be removed.
CREATE USER IF NOT EXISTS CAMELLIA_SVC
    TYPE = SERVICE
    DEFAULT_ROLE = CAMELLIA_APP
    DEFAULT_WAREHOUSE = CAMELLIA_WH
    DEFAULT_NAMESPACE = CAMELLIA_DEMO.CLINICAL
    COMMENT = 'Camellia hackathon backend (synthetic data only)';
ALTER USER CAMELLIA_SVC SET RSA_PUBLIC_KEY = '<PASTE_PUBLIC_KEY_BODY>';
GRANT ROLE CAMELLIA_APP TO USER CAMELLIA_SVC;

-- 4) Verify. RSA_PUBLIC_KEY_FP should be populated (it starts with SHA256:).
DESC USER CAMELLIA_SVC;
SELECT CURRENT_ORGANIZATION_NAME() || '-' || CURRENT_ACCOUNT_NAME() AS SNOWFLAKE_ACCOUNT_FOR_ENV_FILE;

-- backend/.env values for this script:
--   SNOWFLAKE_ACCOUNT=<result of the SELECT above>
--   SNOWFLAKE_USER=CAMELLIA_SVC
--   SNOWFLAKE_WAREHOUSE=CAMELLIA_WH
--   SNOWFLAKE_DATABASE=CAMELLIA_DEMO
--   SNOWFLAKE_SCHEMA=CLINICAL
--   SNOWFLAKE_ROLE=CAMELLIA_APP
--   SNOWFLAKE_CREATE_DATABASE=false
