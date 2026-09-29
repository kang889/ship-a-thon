# Cloud deployment — FastAPI backend (prototype)

This deploys the **backend** to Render or Railway with **Supabase PostgreSQL**. It runs
`APP_MODE=PRODUCTION` **without Qdrant** by using `MEMORY_MODE=lexical` (labelled keyword
memory). Vector/semantic memory is out of scope for this prototype and is not required.

Nothing about the app architecture changes: C++ owns product logic, the Android client stays
a thin shell, AI extraction and RevenueCat/Firebase behaviour are unchanged. Only server
configuration and hosting are covered here.

> Never commit secrets. Set every value below in the host's environment/secret manager, not in
> the repository. `.env` is git-ignored; `.env.example` contains placeholders only.

---

## 1. Supabase PostgreSQL

1. Create a project at supabase.com. Choose a region close to your backend host.
2. In **Project Settings → Database → Connection string**, copy the connection info
   (host, port `5432`, database `postgres`, user `postgres`, and the database password you set).
3. Build a SQLAlchemy + psycopg URL (the app uses the `psycopg` v3 driver):

   ```
   postgresql+psycopg://postgres:<DB_PASSWORD>@<HOST>:5432/postgres
   ```

   - The value **must start with `postgresql`** (the backend rejects non-Postgres URLs in PRODUCTION).
   - If your host has no static outbound IPs, prefer Supabase's **connection pooler** host
     (Session mode) and its port; keep the same `postgresql+psycopg://` prefix.
   - No manual schema step is required: the app creates its tables on first start
     (`records`, `ai_cache`, `ai_daily_usage`).

No Supabase service-role key is needed by the backend — only the database connection string.

---

## 2. Firebase server credentials (production auth)

In `PRODUCTION`, the backend verifies client Firebase ID tokens with the Firebase Admin SDK,
which authenticates using **Application Default Credentials** (a service-account JSON).

1. Firebase Console → **Project settings → Service accounts → Generate new private key**.
   This downloads a service-account JSON. **Do not commit it.**
2. On the host, provide it as a **secret file** and point the SDK at it:
   - `FIREBASE_PROJECT_ID` = your Firebase project id.
   - `GOOGLE_APPLICATION_CREDENTIALS` = absolute path to the mounted service-account JSON
     (for example `/etc/secrets/firebase-service-account.json`).
3. Enable Email/Password sign-in in Firebase Auth for the Android package
   `com.studentmemory.copilot` (client-side requirement).

The Android app sends a Firebase ID token; the backend verifies it against this project.

---

## 3. Other required server secrets

- **OpenAI** (server-side extraction): `OPENAI_API_KEY` from your OpenAI API project, with billing
  enabled and access to the configured `AI_MODEL`. Set `ENABLE_PAID_AI=true`. Never put this key
  in Android/Gradle.
- **RevenueCat** (server-side Pro verification): `REVENUECAT_SECRET_KEY` (the **secret** API key,
  not the public Android key). Without it, Pro-gated endpoints return 503 in PRODUCTION.

---

## 4. Deploy on Render

Render can build from the repo's `backend/Dockerfile`.

1. New → **Web Service**, connect the repo, select the `deploy` branch.
2. **Runtime: Docker.** Dockerfile path: `backend/Dockerfile`; build context: repository root.
   Set the Docker build argument **`ONLINE_DEPENDENCIES=true`** so the image installs
   `firebase-admin` (required for production token verification).
3. Render injects `PORT`; the container already binds `0.0.0.0` and honours `$PORT`.
4. Add a **Secret File** with the Firebase service-account JSON at, e.g.,
   `/etc/secrets/firebase-service-account.json`.
5. Set the environment variables from section 6.
6. **Health check path:** `/api/v1/health`.
7. Deploy. When live, confirm: `GET https://<your-service>.onrender.com/api/v1/health`
   returns `{"ok": true, "mode": "PRODUCTION", "paid_ai": true}`.

---

## 5. Deploy on Railway (alternative)

1. New Project → Deploy from repo → select the `deploy` branch.
2. Use the **Dockerfile** builder with `backend/Dockerfile` and build arg
   `ONLINE_DEPENDENCIES=true`. Railway injects `PORT` (honoured by the container).
3. Add the Firebase service-account JSON as a mounted file/secret and set
   `GOOGLE_APPLICATION_CREDENTIALS` to its path.
4. Set the environment variables from section 6.
5. Health check: `/api/v1/health`.

---

## 6. Environment variables (Render/Railway)

Required for `APP_MODE=PRODUCTION` (lexical memory, no Qdrant):

| Variable | Value / notes |
|---|---|
| `APP_MODE` | `PRODUCTION` |
| `DATABASE_URL` | `postgresql+psycopg://postgres:<pw>@<host>:5432/postgres` (Supabase) |
| `ENABLE_PAID_AI` | `true` |
| `OPENAI_API_KEY` | server OpenAI key (secret) |
| `AI_MODEL` | `gpt-4.1-mini` (or another image-capable Responses model) |
| `MEMORY_MODE` | `lexical` (no Qdrant for this prototype) |
| `FIREBASE_PROJECT_ID` | your Firebase project id |
| `GOOGLE_APPLICATION_CREDENTIALS` | path to the mounted Firebase service-account JSON |
| `REVENUECAT_SECRET_KEY` | RevenueCat **secret** key (secret) |
| `DAILY_USER_AI_CALLS` | e.g. `5` (optional) |
| `DAILY_GLOBAL_AI_CALLS` | e.g. `50` (optional) |

Not needed for the prototype (only if you later set `MEMORY_MODE=semantic`):
`QDRANT_URL`, `QDRANT_API_KEY`.

Operational note: run a **single web worker** for AI-cache coalescing (quota counters remain
atomic across workers, but identical concurrent requests could be billed twice across workers).

---

## 7. Point the Android app at the deployed backend

The backend URL is public client configuration (not a secret). Rebuild the app with your host:

```sh
platform/android/gradlew -p platform/android assembleDebug \
  -PbackendUrl=https://<your-service>.onrender.com \
  -PfirebaseApiKey=<public-firebase-web-api-key> \
  -PfirebaseAppId=<public-firebase-android-app-id> \
  -PfirebaseProjectId=<your-firebase-project> \
  -PrevenuecatPublicKey=<public-revenuecat-android-key>
```

- The client refuses plaintext; use the deployment's **HTTPS** URL (Render/Railway provide TLS).
- `10.0.2.2` is only for a local emulator against a local backend; production uses the HTTPS host.
- Only public client values go into Gradle. Server secrets (OpenAI, RevenueCat secret, Firebase
  service account) live only on the backend host.

---

## 8. Post-deploy verification checklist

1. `GET /api/v1/health` → `200 {"ok":true,"mode":"PRODUCTION","paid_ai":true}`.
2. Sign in on the app; confirm authenticated API calls succeed (Firebase token accepted).
3. Add an event → confirm it persists (Supabase `records` table receives the row).
4. Pro screen: purchase/restore via RevenueCat; confirm entitlement `pro` unlocks AI import.
5. Run one real AI import and confirm review-before-save still applies.
