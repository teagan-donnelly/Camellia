# Camellia — Serverless Architecture on Snowflake

**Status:** the application changes in §6 are implemented and tested on branch `feature/serverless-architecture` (see "Implementation status" below). Nothing is deployed to AWS yet. Written 2026-10-10.

**Scope:** Camellia stays a demo on synthetic data. This design runs it the way a production service should be run (least privilege, managed secrets, hard cost caps), but it does **not** make Camellia HIPAA compliant or fit for real patient data. Section 10 lists what that would take.

Companion file: [database/snowflake/10_runtime_access_and_cost_controls.sql](database/snowflake/10_runtime_access_and_cost_controls.sql) creates the roles, warehouse, grants and resource monitors described here. It has not been run yet.

## Implementation status

Done on `feature/serverless-architecture`:
- **Phase 0:** removed the 18 duplicate `* 2.*` files that broke the build on `main`.
- **§6 items 1–11:** all application-side changes are in place. Items 1, 2 and 8 needed only configuration, which already existed. Each of the others has a setting or a file:
  - the `GET /api/health` endpoint
  - the `CAMELLIA_DEMO_ADMIN_ENABLED` switch, with the UI hiding the buttons when it's off
  - session-only integration checks
  - a cached anchor date and a 5-minute status poll
  - the Gemini time budget (`GEMINI_REQUEST_BUDGET_SECONDS`)
  - chat history in the new `AI_CONVERSATION_TURNS` table
  - the Secrets Manager bootstrap ([AwsSecretsBootstrap.java](backend/src/main/java/com/camellia/config/AwsSecretsBootstrap.java))
  - one Snowflake login per start instead of two
  - the jobs entry point ([JobsController.java](backend/src/main/java/com/camellia/controller/JobsController.java), `CAMELLIA_JOBS_ENABLED`)
- **Lambda package:** [deploy/lambda/run.sh](deploy/lambda/run.sh) and [deploy/lambda/package.sh](deploy/lambda/package.sh) build `deploy/lambda/dist/camellia-lambda.zip`. It is 131 MB zipped and 146 MB unzipped, so it must be uploaded through S3. Lambda's limit is 250 MB unzipped.

How it was verified (2026-10-10):
- **Backend tests:** `./mvnw test` passes all 64 tests, including 13 new ones in `serverless/`, `config/` and `integration/gemini/`.
- **Frontend:** `npm run build` passes.
- **Live run:** the packaged jar was started through `run.sh` against live Snowflake (owner role) and Gemini.
  - Health and status answered, and the Gemini check refused calls without a session.
  - A two-question chat on Ava Marlowe's alert was continued **after restarting the backend**. Gemini resolved "those results" from the first answer.
  - Another user's attempt to reuse the conversation was refused with `409 CONTEXT_MISMATCH`.
- **Not verified yet:** the runtime role. `10_runtime_access_and_cost_controls.sql` hasn't been run, and no AWS resources exist.

Next:
- **Phase 1:** generate the runtime key pair and run the `10_` SQL in Snowsight (an admin action), then test locally as `CAMELLIA_RUNTIME`.
- **Phase 2:** IaC (Terraform) and the GitHub Actions pipeline.
- **Key rotation:** rotate the Gemini key.

## 1. Inputs

Verified in the repo (and, for the region, the Snowsight URL):

| | |
|---|---|
| Product | Clinical intelligence and care-coordination prototype (PalmettoHacks 2026). Clinicians search patient records from several organizations, review screening flags (including Anesthesia OB Alerts), coordinate follow-up, and ask a Gemini assistant whose answers cite source records. |
| Users | Six **simulated** identities in five roles (Physician, RN, L&D Nurse, CRNA, Anesthesiologist). Physicians are practice-scoped. There is no real authentication. |
| Frontend | React 19, TypeScript, Vite 8, Tailwind 4. Calls relative `/api/...` paths and relies on a same-origin HttpOnly cookie (`CAMELLIA_DEMO_SESSION`, `SameSite=Strict`). |
| Backend | Java 21, Spring Boot 4.1.1, one REST service. Snowflake JDBC 4.4.0 with key-pair auth; Google Gen AI Java SDK 1.76.0. |
| Snowflake | Account `uyc13421.us-east-1`, which is **AWS US East (N. Virginia)**. `CAMELLIA_DEMO.CLINICAL` (22 tables once this branch's backend has started), warehouse `CAMELLIA_WH` (XSMALL, second generation), role `CAMELLIA_APP`, service user `CAMELLIA_SVC`. The Claude connector signs in as a personal user with the read-only `CLAUDE_MCP_ROLE`. |
| Data sources | Five synthetic source files (two FHIR-like JSON bundles, a legacy practice-system JSON export, an HL7 v2 lab feed, urgent-care dictation text), one "incoming" HL7 update, and organization/patient/practice reference JSON. The backend parses, tags and loads them at startup. |
| Hosting today | localhost only. The repo has no Dockerfile, IaC or deploy pipeline. |

Assumed until §11 is answered: the app runs on AWS in us-east-1 next to Snowflake; the Spring Boot code is kept; data stays synthetic; Terraform for IaC; GitHub Actions for CI/CD.

## 2. Recommended architecture

```
Browser
  │ HTTPS, one origin (the cookie works unchanged, no CORS)
  ▼
CloudFront ─── WAF rate-based rules
  ├── /*      → S3 bucket (private, OAC)        React/Vite build
  └── /api/*  → API Gateway HTTP API            throttling, access logs, 30 s limit
                 └→ Lambda camellia-api         existing Spring Boot jar via Lambda Web Adapter
                      ├→ Secrets Manager        Snowflake key, Gemini key, session secret
                      ├→ Snowflake              JDBC, key pair, CAMELLIA_RUNTIME on CAMELLIA_API_WH
                      └→ Gemini API             only evidence the user may see; deadline under 30 s

EventBridge Scheduler → Lambda camellia-jobs    same jar, owner role CAMELLIA_APP, no public route
                          daily screening · seeding · demo reset

Phase 3:  S3 landing bucket → Snowpipe → RAW → Stream + Task → CLINICAL
          Dynamic Tables → ANALYTICS (reporting only)
```

| Component | Why it exists | Key settings |
|---|---|---|
| CloudFront | One HTTPS origin for UI and API, so the HttpOnly `SameSite=Strict` cookie and relative `/api` calls work unchanged and no CORS is needed. Caches static assets. Attachment point for WAF. | `/api/*`: managed policies `CachingDisabled` and `AllViewerExceptHostHeader`. SPA routes via a CloudFront Function (§8.5), not custom error pages. |
| AWS WAF | Per-IP rate limits, so a public URL can't be scripted into burning Snowflake credits and Gemini quota. | Optional for a private demo; it adds a fixed monthly fee. |
| S3 (static site) | Hosts the built frontend. | Private bucket, Block Public Access, Origin Access Control. |
| API Gateway (HTTP API) | Stage throttling as a cost and abuse cap, plus access logs. Simpler and cheaper than a REST API, and nothing here needs REST-API-only features. | Route `ANY /api/{proxy+}`. The integration timeout is at most **30 s** (see §6 item 6). |
| Lambda `camellia-api` | Runs the existing jar. Lambda Web Adapter proxies HTTP events to the app's port, so nothing depends on a Lambda library that supports Spring Boot 4. Scales to zero; reserved concurrency caps spend, Snowflake sessions and Gemini calls together. | Java 21, arm64, 2048 MB to start (tune after measuring), 30 s timeout, reserved concurrency 10. |
| Lambda `camellia-jobs` | Same artifact with the owner role, invoked only by the scheduler or an operator. Keeps DDL, seeding and reset privileges off the internet-facing function. Re-runs screening daily, because the OB rule's time windows move with today's date. | No function URL, no API route. |
| Secrets Manager | Keeps secrets out of env vars, `.env` and git. IAM allows each function to read only its own secrets. | §8.3 |
| Snowflake | System of record for clinical data, workflow state and the audit trail. Separate API and jobs warehouses, each with a hard monthly cap. | §8.7 |
| Gemini API | Already integrated and validated, and part of the sponsor track. Receives only the evidence the requesting user is allowed to see (already enforced in `ClinicalAiService`). | Key from Secrets Manager. |
| CloudWatch | Logs and alarms (5xx rate, throttles, duration). | 14-day log retention; no prompts or request bodies in logs. |
| GitHub Actions | Test, build and deploy without long-lived AWS keys (OIDC). | |

**Known trade-off: cold starts fan out.** Lambda runs one request per execution environment, and the SPA fires 4–6 API calls in parallel on first load (session, user list, status, dashboard, alerts). After an idle period, the first page load therefore starts several environments at once, each with its own JVM start and Snowflake login. Mitigations: SnapStart (check compatibility with the pinned Lambda Web Adapter version first), or provisioned concurrency during demo windows only. Measure in phase 2. If it's still too slow, run the same container on one of these instead. The rest of the design doesn't change.
- **ECS on Fargate:** one always-on task serves all parallel requests, with no cold starts and a fixed monthly cost. You manage a VPC and a load balancer or VPC link.
- **Google Cloud Run:** per-instance concurrency, scales to zero, longer request timeouts. Gemini can go through Vertex AI with the service account, so there's no API key to store. Snowflake stays on AWS, so use a nearby region such as us-east4 (N. Virginia).

Not recommended: rewriting the backend into per-route TypeScript or Python functions. It would discard tested Java code (authorization, grounding, citation checks, screening rules) and add no capability.

## 3. Request and data flows

**Clinical read** (for example, opening a patient)
1. `GET /api/patients/{id}` with the session cookie → CloudFront (not cached) → API Gateway (throttle check) → `camellia-api`.
2. Lambda Web Adapter forwards to Spring on port 8080. `CurrentUserArgumentResolver` verifies the HMAC-signed cookie and yields a `DemoUser`, or returns `401 DEMO_SESSION_REQUIRED`.
3. `AuthorizationService` computes the user's `AccessScope`. Out-of-scope and missing records both get the same generic `403 ACCESS_DENIED`.
4. Parameterized SQL restricted to the scope's patient IDs → Snowflake on `CAMELLIA_API_WH`, which resumes automatically if suspended.
5. JSON back along the same path.

**AI assistant and natural-language search**
1. Same as steps 1–3 above.
2. Retrieval from Snowflake, limited to the user's patients and allowed flag categories. `EvidencePacker` caps the context at 28,000 characters.
3. Gemini call with a JSON schema for the response. The whole call, including retries and the fallback model, must finish within about 25 s to fit the 30 s gateway limit.
4. `CitationValidator` removes citations to records that weren't retrieved. The response names the model that answered.
5. For search, Gemini only fills allow-listed criteria (`SearchCriteria`, `FlagSearchCriteria`); the backend runs fixed, parameterized SQL. The model never writes SQL.

**Workflow write** (status change, note): permission and scope check → UPDATE/INSERT on the workflow tables plus an `ACTIVITY_LOG` row → response. The runtime role can't write anything else (§8.7).

**Jobs:** EventBridge Scheduler (daily) → `camellia-jobs` → screening run. Flags and alerts are updated, and human review statuses are preserved. Seeding and demo reset are manual invocations of the same function (CLI or console), never browser actions.

**Ingestion (phase 3):** source system → S3 `landing/` → Snowpipe auto-ingest (S3 event → Snowflake's SQS queue) → `RAW` tables holding each file as received, with file name and load time → stream → task → normalized rows in `CLINICAL` → screening. Where normalization runs is §11 question 6. Porting the JSON/HL7/dictation parsers and the concept tagger to SQL is the Snowflake-native option; reusing the tested Java parsers in `camellia-jobs` is the fast one.

## 4. What runs where

| Need | Where | Why |
|---|---|---|
| HTTP, sessions, per-user authorization, input validation | Lambda (Spring) | Snowflake sees one service identity. Per-user rules live in `AuthorizationService` and are applied to the SQL. |
| Calling Gemini, filtering evidence, checking citations | Lambda | Keeps the key, the user's scope and the checks together. Calling Gemini from inside Snowflake would lose the user's context. |
| Clinical data, workflow state, audit trail | Snowflake standard tables | One governed system of record; the write rate is low. |
| Chat history across requests | Snowflake table (default) or DynamoDB with TTL | Must survive across Lambda instances. A Snowflake table keeps patient-derived text in one governed store. |
| Set-based transforms, aggregates, reporting | Snowflake (Tasks, Dynamic Tables) | The data doesn't move. |
| Continuous file loads | S3 + Snowpipe | Serverless and event-driven. |
| Scheduled work that needs Java domain logic (screening rules, HL7/dictation parsing) | EventBridge Scheduler + Lambda | Reuses tested code. |
| Scheduled SQL-only work (retention, reconciliation) | Snowflake Tasks | No extra AWS parts. |
| Clinician UI | S3 + CloudFront | Not Streamlit or anything hosted in Snowflake. |
| Browser talking to Snowflake directly | Never | Credentials stay on the server. |

## 5. Snowflake features

**Use now**
- XSMALL warehouses with `AUTO_SUSPEND = 60`, `AUTO_RESUME`, and statement and queue timeouts.
- Separate API and jobs warehouses, each with a resource monitor, so a runaway job can't starve the API or run up the bill.
- Key-pair auth, `TYPE = SERVICE` users and `DEFAULT_SECONDARY_ROLES = ()`. There are no passwords, and a session can't quietly combine privileges from other roles granted to the same user.
- A managed access schema, a least-privilege runtime role, and future grants.
- Query tags per identity, for cost attribution in `QUERY_HISTORY`.
- Zero-copy clones, to try migrations or the role setup on a copy of the database without duplicating storage.

**Later (phase 3)**
- Snowpipe auto-ingest into `RAW`, once files actually arrive from outside, or as a deliberate showcase.
- Streams and Tasks: a triggered task, or a scheduled task with `WHEN SYSTEM$STREAM_HAS_DATA(...)`, so compute only starts when there's new data.
- Dynamic Tables in an `ANALYTICS` schema (flag aging, alert throughput) with a lag of an hour or more, for reporting only.
- Budgets, which cover the serverless features resource monitors can't (Snowpipe, serverless tasks, Cortex).
- Masking and row access policies plus access history (Enterprise edition or higher), once people query the data directly.

**Avoid for this use case**
- **Streamlit in Snowflake for the clinician UI.** The React UI exists, and viewers would need Snowflake logins. It's fine for internal ops dashboards.
- **Snowpark Container Services to host the API.** Compute pools bill while running, and public endpoints require a Snowflake sign-in, which doesn't fit judges or clinicians.
- **Hybrid Tables.** The workflow writes a handful of rows per action, which standard tables handle. Revisit only if writes need single-digit-millisecond latency or high concurrency.
- **Dynamic Tables or materialized views behind workflow screens.** They lag by design, and a status change has to show immediately.
- **Cortex as a Gemini replacement, for now.** Gemini is integrated, validated and part of the sponsor track. Revisit for real patient data, where keeping inference inside Snowflake is the main advantage. Confirm model availability in us-east-1 and contract coverage first.
- **Cortex Search or vector search.** The dataset is about 150 records, and the deterministic concept tags are easier to explain to clinicians.
- **Multi-cluster or larger warehouses, Search Optimization, Query Acceleration.** Nothing in this workload needs them, and each adds cost.
- **External functions or external access from Snowflake to Gemini.** They would bypass the per-user authorization in the API.
- **The Snowflake SQL API from the browser.** It would put credentials in the client.

## 6. Code changes needed before it runs serverless

Found in the current code:

| # | Finding | Why it matters on Lambda | Change |
|---|---|---|---|
| 1 | `StartupInitializer` creates the schema, seeds and runs screening on every start. | Every cold start would run DDL, seeding and a full screening run; concurrent cold starts would race; and the runtime role can't do DDL. | Set `CAMELLIA_INIT_SCHEMA`, `CAMELLIA_SEED_ON_STARTUP` and `CAMELLIA_ANALYZE_ON_STARTUP` to `false` on `camellia-api`, and run that work in `camellia-jobs`. |
| 2 | `CAMELLIA_DB_MODE=auto` falls back to H2 when Snowflake variables are missing (`DataSourceConfig`). | A misconfigured deploy would try to serve an empty local database instead of failing. | Set `CAMELLIA_DB_MODE=snowflake` in every cloud environment. |
| 3 | `POST /api/admin/reset-demo` deletes every row in 20 tables, and `POST /api/admin/simulate-lab-result` inserts source records. Both need only a demo session (`SystemController`). | On a public URL anyone can wipe workflow state mid-demo. | Gate both behind a new `CAMELLIA_DEMO_ADMIN_ENABLED` flag (off in the cloud) and run them through `camellia-jobs`. The runtime role lacks the privileges anyway. |
| 4 | Endpoints that need no session reach Snowflake or Gemini: `GET /api/integrations/status`, `GET /api/meta`, `POST /api/integrations/database/check` (writes rows), `POST /api/integrations/gemini/check` (makes a real Gemini call). | Anyone can call them, and each call costs warehouse time or Gemini quota. | Require a session for the two POSTs, keep the throttles in front of all four, and add a `GET /api/health` that touches nothing, for readiness and uptime checks. |
| 5 | The frontend polled `/api/integrations/status` every 60 s, and each poll queried `DEMO_METADATA`. | With a 60 s auto-suspend, an open Camellia tab could keep a warehouse running (roughly 1 credit per hour or more). That also affected local development against Snowflake. | Cache the anchor date in memory on the backend, and poll every 5–10 minutes or only on the System page. |
| 6 | Gemini allows a 60 s timeout, up to 2 retries, then the fallback model (`application.yml`). | That can exceed the 30 s gateway limit: the user gets a 504 while Lambda keeps running and billing. | `GEMINI_REQUEST_BUDGET_SECONDS` bounds the whole request: a retry or fallback only starts while a full `GEMINI_TIMEOUT_SECONDS` still fits. In the cloud, use `12` and `25`. |
| 7 | `ConversationStore` keeps chat history in memory (2 h TTL). | A follow-up question that lands on another Lambda instance silently loses its context. | Persist turns in a Snowflake table keyed by conversation, user and context, and keep the existing context-mismatch check. |
| 8 | `DemoSessionService` generates a random secret at startup when `CAMELLIA_DEMO_SESSION_SECRET` is blank. | Each Lambda instance would reject cookies issued by the others. | Load the secret from Secrets Manager (§8.4). |
| 9 | Secrets come from env vars or `.env`, and the Snowflake key from a file path. `backend/.env` is tracked in git and contains a live Gemini key. | There's no secret manager, and everyone with repo access, including through history, has the key. | Add the Secrets Manager bootstrap (§8.4). Rotate the Gemini key and run `git rm --cached backend/.env`. Git history keeps the old key, so rotation is the real fix. |
| 10 | Each cold start makes two Snowflake logins: the `bootstrapSnowflake` probe and the Hikari pool's minimum idle connection (`DataSourceConfig`). | Adds cold-start latency. Idle sessions don't use warehouse credits. | Skip the probe when `SNOWFLAKE_CREATE_DATABASE=false`, and measure before and after. |
| 11 | Jobs have no non-HTTP entry point. | EventBridge sends events, not HTTP requests. | Add an internal `POST /events` controller, enabled only in `camellia-jobs`. Lambda Web Adapter can forward non-HTTP events to a configured path (`AWS_LWA_PASS_THROUGH_PATH`). |

## 7. Implementation plan

**Phase 0: Unblock** (hours)
- Fix `main`: the 18 duplicate `* 2.*` files added in `b7be725` break the backend build.
- Rotate the Gemini key and stop tracking `backend/.env` (§6 item 9).
- Answer the questions in §11.
- Exit: `./mvnw test` and `npm run build` pass on `main`.

**Phase 1: Snowflake hardening, no deployment yet** (hours)
- Generate the runtime key pair (§8.6) and run `10_runtime_access_and_cost_controls.sql`.
- Point a local `backend/.env` at the runtime identity (the values are at the end of the SQL file) and click through each role's workflows. Reset demo data and Simulate lab result should fail; nothing else should.
- Fix the status polling (§6 item 5).
- Exit: the app works end to end as `CAMELLIA_RUNTIME`, and both resource monitors show in Snowsight.

**Phase 2: Serverless deployment** (days)
- Make the code changes in §6.
- Give the hosted environment its own database, so local resets and test data can't touch it. Create it as in steps 1–2 of `00_account_setup.sql`, bootstrap it through `camellia-jobs`, then run the `10_` SQL with the database name replaced.
- Write IaC for S3, CloudFront (Function and WAF), the HTTP API, both Lambdas, Secrets Manager, IAM, log retention, the schedule and an AWS Budget.
- Set up CI/CD with GitHub Actions: tests on every PR, build and deploy with OIDC, and manual approval for the hosted environment.
- Measure cold start and p95 latency, then decide on SnapStart or demo-window provisioned concurrency (§2, known trade-off).
- Exit: a public URL, RBAC tests A–J passing in CI, and one week of cost data reviewed.

**Phase 3: Snowflake-native data platform** (days; optional for the hackathon)
- Add an S3 landing bucket, a storage integration, and Snowpipe into `RAW`.
- Add Streams and Tasks to process new files, plus a reconciliation task that checks every file in `RAW` is processed exactly once.
- Add Dynamic Tables in `ANALYTICS` for operational reporting, and a Snowflake Budget for serverless features.

**Phase 4: Before any real patient data** (§10; not in hackathon scope)

## 8. Repository structure, environment variables and starter code

### 8.1 Repository structure

```
Camellia/
├── backend/                                     Spring Boot API (layout unchanged)
│   └── src/main/java/com/camellia/
│       ├── CamelliaApplication.java             + AwsSecretsBootstrap.load() before SpringApplication.run
│       └── config/AwsSecretsBootstrap.java      new (§8.4)
├── frontend/                                    React/Vite → S3
├── database/snowflake/
│   ├── 00_account_setup.sql                     existing: warehouse, database, owner role, service user
│   ├── 10_runtime_access_and_cost_controls.sql  new: runtime role/user, API warehouse, monitors
│   ├── 20_raw_landing.sql                       phase 3: storage integration, stage, pipes, streams
│   └── 30_analytics.sql                         phase 3: dynamic tables
├── deploy/lambda/run.sh                         Lambda Web Adapter entry script (§8.5)
├── infra/                                       IaC (tool per §11)
│   ├── modules/{edge,api,jobs,secrets,landing}/
│   └── envs/{dev,prod}/                         non-secret values only
└── .github/workflows/
    ├── ci.yml                                   tests and builds on every PR
    └── deploy.yml                               OIDC to AWS; manual approval for prod
```

Rules:
- No secret values anywhere in the repo, including `.tfvars` files.
- Terraform state can contain secrets, so it lives in a private, encrypted S3 backend and is never committed.
- Add `.terraform/` and `*.tfstate*` to `.gitignore`.

### 8.2 Environment variables (`camellia-api`)

| Variable | Value | Note |
|---|---|---|
| `CAMELLIA_DB_MODE` | `snowflake` | Fail fast; never H2 in the cloud. |
| `CAMELLIA_INIT_SCHEMA`, `CAMELLIA_SEED_ON_STARTUP`, `CAMELLIA_ANALYZE_ON_STARTUP` | `false` | That work belongs to `camellia-jobs`. |
| `CAMELLIA_DEMO_ADMIN_ENABLED` | `false` | New (§6 item 3). |
| `CAMELLIA_CORS_ORIGINS` | `https://<cloudfront-domain>` | Requests are same-origin anyway; keep it narrow. |
| `SNOWFLAKE_ACCOUNT` | `uyc13421.us-east-1` | An identifier, not a secret. |
| `SNOWFLAKE_USER`, `SNOWFLAKE_ROLE` | `CAMELLIA_RUNTIME_SVC`, `CAMELLIA_RUNTIME` | |
| `SNOWFLAKE_WAREHOUSE` | `CAMELLIA_API_WH` | |
| `SNOWFLAKE_DATABASE`, `SNOWFLAKE_SCHEMA` | hosted database, `CLINICAL` | |
| `SNOWFLAKE_CREATE_DATABASE` | `false` | |
| `GEMINI_MODEL`, `GEMINI_FALLBACK_MODEL`, `GEMINI_THINKING_LEVEL`, `GEMINI_MAX_CONTEXT_CHARS` | as today | |
| `GEMINI_TIMEOUT_SECONDS`, `GEMINI_REQUEST_BUDGET_SECONDS` | `12`, `25` | Fits the 30 s limit (§6 item 6). |
| `CAMELLIA_JOBS_ENABLED` | `false` (`true` only on `camellia-jobs`) | Registers `POST /events`. |
| `CAMELLIA_DEMO_SESSION_SECRET` | from Secrets Manager | Shared by all instances (§6 item 8). |
| `CAMELLIA_SNOWFLAKE_KEY_SECRET_ARN`, `CAMELLIA_GEMINI_KEY_SECRET_ARN`, `CAMELLIA_SESSION_SECRET_ARN` | secret ARNs | New. ARNs only, never values. |
| `AWS_LAMBDA_EXEC_WRAPPER` | `/opt/bootstrap` | Lambda Web Adapter, zip packaging. |
| `AWS_LWA_PORT`, `AWS_LWA_READINESS_CHECK_PATH` | `8080`, `/api/health` | The readiness path must not touch Snowflake. |

`camellia-jobs` uses the same variables with `CAMELLIA_SVC`, `CAMELLIA_APP`, `CAMELLIA_WH` and its own key secret.

The frontend needs no variables because it calls relative `/api` paths. Anything prefixed `VITE_` ships to the browser, so never put a secret there.

CI (GitHub Actions) uses `AWS_REGION=us-east-1` and `AWS_DEPLOY_ROLE_ARN` as repository variables; the role trusts GitHub's OIDC provider. There are no AWS access keys.

### 8.3 Secrets (Secrets Manager, us-east-1)

| Secret | Contents | Read by |
|---|---|---|
| `camellia/<env>/snowflake-runtime-key` | PKCS#8 PEM for `CAMELLIA_RUNTIME_SVC` | `camellia-api` |
| `camellia/<env>/snowflake-owner-key` | PKCS#8 PEM for `CAMELLIA_SVC`, or a new owner key | `camellia-jobs` |
| `camellia/<env>/gemini-api-key` | Gemini API key (the rotated one) | both |
| `camellia/<env>/demo-session-secret` | 48 random bytes, base64 (the app requires at least 16 characters) | `camellia-api` |

Each Lambda's execution role gets `secretsmanager:GetSecretValue` on exactly its own secret ARNs.

### 8.4 Secrets bootstrap (Java)

Implemented as [AwsSecretsBootstrap.java](backend/src/main/java/com/camellia/config/AwsSecretsBootstrap.java) and called from `CamelliaApplication.main`. It uses `software.amazon.awssdk:secretsmanager` 2.55.3. The version below is the original sketch; the implemented class differs only in being unit-testable.

```java
package com.camellia.config;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Cloud only: copies secrets from AWS Secrets Manager into system properties before Spring starts, so the existing
 * ${GEMINI_API_KEY}, ${CAMELLIA_DEMO_SESSION_SECRET} and ${SNOWFLAKE_PRIVATE_KEY_FILE} placeholders keep working.
 * Environment variables hold only secret ARNs. Does nothing when none are set (local development).
 */
public final class AwsSecretsBootstrap {

    private AwsSecretsBootstrap() {}

    public static void load() throws IOException {
        String gemini = System.getenv("CAMELLIA_GEMINI_KEY_SECRET_ARN");
        String session = System.getenv("CAMELLIA_SESSION_SECRET_ARN");
        String snowflakeKey = System.getenv("CAMELLIA_SNOWFLAKE_KEY_SECRET_ARN");
        if (gemini == null && session == null && snowflakeKey == null) return;

        try (SecretsManagerClient sm = SecretsManagerClient.create()) {
            if (gemini != null) System.setProperty("GEMINI_API_KEY", read(sm, gemini));
            if (session != null) System.setProperty("CAMELLIA_DEMO_SESSION_SECRET", read(sm, session));
            if (snowflakeKey != null) {
                // The driver reads the key from a file. Lambda's /tmp is private to this execution environment.
                Path key = Files.createTempFile("snowflake-", ".p8",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(key, read(sm, snowflakeKey));
                System.setProperty("SNOWFLAKE_PRIVATE_KEY_FILE", key.toString());
            }
        }
    }

    private static String read(SecretsManagerClient sm, String arn) {
        return sm.getSecretValue(r -> r.secretId(arn)).secretString();
    }
}
```

Then call it first thing in `CamelliaApplication.main`, which must also declare `throws IOException`:

```java
AwsSecretsBootstrap.load();   // no-op locally; in Lambda, copies secrets into system properties
```

Spring gives system properties precedence over environment variables, so the existing `${...}` placeholders resolve unchanged.

### 8.5 Lambda entry script and CloudFront Function

[deploy/lambda/run.sh](deploy/lambda/run.sh) is the handler for the zip package, and [deploy/lambda/package.sh](deploy/lambda/package.sh) builds the zip. Add the Lambda Web Adapter layer ARN published in that project's README, using the arm64 variant.

The package is 146 MB unzipped, almost all of it the Snowflake JDBC driver and the cloud SDKs. Upload it through S3, and treat its size as part of cold-start time when measuring.

```sh
#!/bin/sh
# Lambda Web Adapter runs this, waits for AWS_LWA_READINESS_CHECK_PATH, then proxies requests to port 8080.
# The JVM flags mirror pom.xml's spring-boot:run jvmArguments, which do not apply to java -jar.
exec java -XX:TieredStopAtLevel=1 \
     --add-opens=java.base/java.nio=ALL-UNNAMED -Duser.timezone=UTC \
     -jar /var/task/camellia-backend.jar
```

The CloudFront Function runs on viewer request, attached to the default (S3) behavior only:

```js
function handler(event) {
  var request = event.request;
  // Paths without a file extension are client-side routes: serve the SPA shell.
  var last = request.uri.split('/').pop();
  if (last.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
```

Why not use CloudFront custom error pages (rewriting 403/404 to `index.html`)? They apply to the whole distribution, so they would also replace the API's JSON `403 ACCESS_DENIED` and `404` responses with the HTML shell.

### 8.6 Starter: runtime key pair and secrets (shell)

```sh
# Key pair for CAMELLIA_RUNTIME_SVC (unencrypted PKCS#8, protected by file permissions and Secrets Manager).
openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -nocrypt -out ~/.snowflake/camellia_runtime_rsa_key.p8
chmod 600 ~/.snowflake/camellia_runtime_rsa_key.p8
openssl rsa -in ~/.snowflake/camellia_runtime_rsa_key.p8 -pubout -out ~/.snowflake/camellia_runtime_rsa_key.pub
# Paste the .pub body (without the BEGIN/END lines) into step 4 of the SQL file.

# Values come from files or are generated, so they never appear in shell history.
aws secretsmanager create-secret --region us-east-1 --name camellia/prod/snowflake-runtime-key \
    --secret-string file://$HOME/.snowflake/camellia_runtime_rsa_key.p8
aws secretsmanager create-secret --region us-east-1 --name camellia/prod/demo-session-secret \
    --secret-string "$(openssl rand -base64 48)"
# Add the rotated Gemini key in the Secrets Manager console.
```

### 8.7 Snowflake SQL

[database/snowflake/10_runtime_access_and_cost_controls.sql](database/snowflake/10_runtime_access_and_cost_controls.sql) covers roles, users, warehouses, grants, resource monitors, verification and cost-review queries. It is additive only: no DROP, REPLACE or TRUNCATE.

The 21 application tables are defined in `backend/src/main/resources/db/schema-snowflake.sql` and created by the owner role. The phase 3 `RAW` and `ANALYTICS` objects will go in the `20_` and `30_` files once §11 is settled.

The runtime role's privileges, taken from the SQL in the repositories:

| Privilege | Tables |
|---|---|
| SELECT | all, including future tables |
| INSERT, UPDATE | `CLINICAL_FLAGS`, `COORDINATION_ALERTS` |
| INSERT, DELETE | `FLAG_EVIDENCE`, `ALERT_EVIDENCE`, `AI_OBSERVATIONS` |
| INSERT | `COORDINATION_NOTES`, `ACTIVITY_LOG`, `ANALYSIS_RUNS`, `INTEGRATION_CHECKS` |
| no writes | the 11 source-record tables and `DEMO_METADATA`; no `CREATE TABLE` |

## 9. Cost controls

**Snowflake (the hard caps)**
- XSMALL everywhere, billed per second with a 60-second minimum each time a warehouse resumes, and `AUTO_SUSPEND = 60`.
  - A first-generation XSMALL uses 1 credit per running hour.
  - This account's warehouses are second generation (Gen2), which bills somewhat more per hour; the exact rate is in Snowflake's Service Consumption Table. The credit math below assumes 1 credit per hour, so treat it as a lower bound.
- Resource monitors of 30 credits a month each on the API and jobs warehouses, which is about one warehouse-hour per day. They notify at 50% and 80%, suspend at 100% and suspend immediately at 110%. A tripped monitor means `503 DATABASE_ERROR` until the month resets or someone raises the quota. That is the price of a predictable bill.
- Statement timeouts of 60 s (API) and 900 s (jobs), plus queue timeouts.
- Nothing polls Snowflake: health checks touch no database (§6 item 4) and the status poll is fixed (§6 item 5). Open JDBC sessions use no credits; only running queries do.
- A Budget for serverless features, which resource monitors don't cover.
- Weekly review queries at the end of the SQL file (credits per warehouse per day, queries by tag).
- UI-only work can run locally with `CAMELLIA_DB_MODE=local` (the labeled H2 fallback) and spend no credits.

**AWS**
- Reserved concurrency of 10 on `camellia-api` caps concurrent Lambdas, Snowflake sessions and Gemini calls. New AWS accounts can start with a Lambda concurrency quota too low to allow any reservation; request an increase, and rely on API Gateway throttling meanwhile.
- API Gateway stage throttling: start around 25 requests/s with a burst of 50, then adjust from the access logs.
- WAF rate rules:
  - About 100 requests per 5 minutes per IP on the Gemini-backed endpoints (`/api/ai/*`, interpretation, extraction).
  - A loose rule (about 2,000 per 5 minutes) on everything else.
  - Judges at one venue may share a single public IP, so test from there.
- arm64 Lambdas, which cost less per GB-second, with memory tuned after measuring.
- No VPC or NAT gateway: Lambda reaches Snowflake and Gemini over HTTPS with key-pair and API-key auth, which avoids hourly NAT charges. Revisit in phase 4.
- 14-day log retention, an AWS Budget with an alert, and lifecycle rules on the landing bucket (phase 3).

**Gemini**
- Keep `thinking-level: low` and the 28,000-character context cap (both already in place).
- Retries only inside the deadline (§6 item 6).
- A Google Cloud billing budget alert and a request quota on the project that owns the key.
- Record token usage per call (the SDK returns usage metadata) to see cost per feature.

At demo traffic, warehouse time and Gemini calls should dominate the bill, and the request-priced AWS services should be small. Confirm that against the first week of data instead of assuming it.

## 10. Before any real patient data

This is a checklist, not a compliance claim. Completing it is necessary but not sufficient: HIPAA compliance depends on an organization's policies, agreements and operations, not on architecture alone.

- **Identity:** replace demo impersonation with a real identity provider (OIDC or SAML; SMART on FHIR for an EHR launch). Map verified claims to roles and practices in `CurrentUserArgumentResolver`; authorization is already centralized in `AuthorizationService`.
- **Agreements and editions:** sign BAAs with AWS, Snowflake and the AI provider. Snowflake requires Business Critical edition or higher for PHI. The Gemini API-key path is not the route for PHI; evaluate Gemini on Vertex AI under a Google Cloud BAA, or Snowflake Cortex. Confirm coverage with each vendor.
- **Network:** either run Lambda in a VPC with fixed egress IPs and put a Snowflake network policy on the service users (step 6 of the SQL file), or use AWS PrivateLink to Snowflake (Business Critical).
- **Data protection:** masking and row access policies for anyone querying directly, retention rules, access-history review, and no PHI in logs or traces.
- **Assurance:** a threat model, a penetration test, incident response, backup and restore drills, and clinical validation and governance of the screening rules (currently labeled as unvalidated prototype heuristics).

## 11. Open questions

The defaults are what this document assumes.

| # | Question | Default |
|---|---|---|
| 1 | Is the goal a hardened, hosted Camellia demo on synthetic data, or a path to real patient data? | Synthetic only. Real data moves §10 to the front and changes the edition, contracts, AI provider and authentication. |
| 2 | Which cloud hosts the app? | AWS us-east-1, next to the Snowflake account. The alternative is Cloud Run with Vertex AI (§2). |
| 3 | Keep the Spring Boot backend as one function? | Yes; no rewrite into per-route functions. |
| 4 | Which Snowflake edition, and is the account a trial? | The design needs only Standard. A trial expires, which matters for anything called production. |
| 5 | Monthly budget ceilings for Snowflake, AWS and Gemini? | Resource monitors of 30 + 30 credits a month, plus AWS and Google billing alerts. |
| 6 | Will new source files arrive after the seed (S3 drops, an HL7 feed, a partner API)? When they do, should normalization move into Snowflake SQL or stay in Java? | With no new feed, Snowpipe waits, or gets built now as a Snowflake showcase: "Simulate lab result" becomes dropping the HL7 file into S3. Normalization runs in Java in `camellia-jobs` first, then moves to SQL one format at a time. |
| 7 | A public URL for judges, or restricted access? When is the demo? | Public, with WAF, throttling and the simulated-identity banner. Phases 0–2 before the demo; phase 3 after. |
| 8 | Which IaC tool and CI? | Terraform (it can manage Snowflake too) and GitHub Actions. AWS SAM is quicker if nobody on the team knows Terraform. |
