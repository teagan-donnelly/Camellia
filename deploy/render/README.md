# Backend on Render

Runs the Spring Boot backend as a Render web service so the Vercel frontend works without a local computer or tunnel.
Data stays in Snowflake; Render only runs the server.

## Service settings

| Setting | Value |
|---|---|
| Runtime | Docker |
| Repository / branch | `teagan-donnelly/Camellia`, the branch to demo |
| Dockerfile path | `./backend/Dockerfile` |
| Docker build context | `./backend` |
| Plan | Free (512 MB; the JVM flags in the Dockerfile are sized for it) |
| Health check path | `/api/health` |

The image includes the committed `backend/.env` (Snowflake account settings, Gemini and ElevenLabs keys). Environment
variables set on Render override it.

## Secrets and variables to set in the Render dashboard

| Name | Where | Value |
|---|---|---|
| `snowflake_key.p8` | **Secret File** (Environment → Secret Files) | The contents of the Snowflake private key file for `CAMELLIA_SVC` |
| `CAMELLIA_CORS_ORIGINS` | Environment variable | `https://camellia-snowy.vercel.app` |
| `CAMELLIA_DEMO_SESSION_SECRET` | Environment variable | Any long random string, so demo sign-ins survive restarts |

`docker-entrypoint.sh` points `SNOWFLAKE_PRIVATE_KEY_FILE` at `/etc/secrets/snowflake_key.p8`. As an alternative to the
secret file, the key's contents can go in a `SNOWFLAKE_PRIVATE_KEY` environment variable.

## Point the frontend at it

```bash
deploy/vercel/deploy.sh https://<service-name>.onrender.com
```

## Free-plan behaviour

- The service sleeps after 15 minutes without requests. The next request starts it again, which takes about a minute,
  so open the site a minute or two before presenting.
- The disk is temporary: uploaded documents are lost when the service restarts or redeploys. Snowflake data is kept.
- Pushing to the deployed branch redeploys automatically.
