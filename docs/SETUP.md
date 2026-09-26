# Student Memory Copilot

Android app with a platform-independent C++20 core. `BUILD.md` takes precedence over the original README architecture. No paid service is enabled by default.

## Try the app

Build output: `platform/android/app/build/outputs/apk/debug/app-debug.apk`.

Install this debug APK on Android 8.0 or newer (ARM64 phone or x86-64 emulator). Allow notifications when prompted. Add a class, room, items and a preparation task. The C++ core generates the next week's occurrences, preparation slots, risk scores and reminder plans.

Item actions follow explicit transitions: Needed → Packed → Brought → In use → Needs to return → Safe. Forgotten records a failure for that event and item; the next occurrence starts Needed with the updated risk. Not needed skips only that occurrence. Items aren't assumed to have been brought merely because they were packed.

Imports default to visibly labelled **demo results**, not recognition of the selected screenshot. Review and confirm before any events, items or tasks are saved. Repeating classes retain their local wall-clock time across daylight-saving changes. Preparation tasks are one-time, and instruction items apply to the extracted occurrence only.

## Build and test

C++ requires CMake 3.22+ and a C++20 compiler. The pinned JSON dependency and its MIT license are vendored, so native builds need no network dependencies.

```sh
cmake -S . -B out
cmake --build out
ctest --test-dir out --output-on-failure
```

On Windows with MinGW, configure with `-G "MinGW Makefiles"`. `out/memory_demo.exe` prints a sample core-generated view.

Android requires JDK 17, SDK 35, NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` to your SDK directory, then:

```sh
platform/android/gradlew -p platform/android assembleDebug lintDebug
```

On Windows use `platform\android\gradlew.bat`. For this workspace, downloaded build tools are in `out/android-sdk`; they are ignored by Git. The Gradle wrapper pins version 8.13. This build produces a debug APK, not a store-signed release.

Backend, Python 3.12:

```sh
python -m venv .venv
# Activate .venv using your shell's activation command.
python -m pip install -r backend/requirements.txt
python -m pytest -q --basetemp=out/pytest
ruff check backend tests/test_backend.py
uvicorn backend.api.main:create_app --factory --host 127.0.0.1 --port 8000 --no-access-log
```

API documentation: `http://127.0.0.1:8000/docs`. Development authentication is `Authorization: Bearer local-development-only`; override with `DEV_TOKEN`. That fixed development identity is rejected in production mode. The `.env.example` file is a reference: set environment variables in your shell, or use Compose's `.env` loading.

```sh
docker compose up --build
```

Compose starts the backend, PostgreSQL and Qdrant. Only the backend is exposed, bound to localhost. Default MOCK mode does not download an embedding model, initialize Firebase or call an AI service. Docker wasn't available in the implementation environment, so this Compose stack has not been run here.

## Add your keys later

### Backend modes

Exactly three modes are supported:

| Mode | Services |
|---|---|
| MOCK (default) | SQLite or PostgreSQL, fake extraction/weather, labelled lexical memory retrieval |
| LOCAL | SQLite or PostgreSQL, Qdrant, local sentence-transformer, real weather, optional paid extraction |
| PRODUCTION | PostgreSQL, Qdrant, Firebase identity verification, explicitly enabled direct AI |

For LOCAL/PRODUCTION, install `backend/requirements-online.txt`. With Compose, set `ONLINE_DEPENDENCIES=true`, `APP_MODE=LOCAL` and rebuild. The pinned MiniLM model downloads once and persists in the `models` volume. It runs on the backend CPU. Memory embeddings are reused unless the text changes; a search embeds only its query, and retrieves at most five user-scoped records without an LLM.

To enable real extraction, set these **server environment variables**:

```dotenv
APP_MODE=LOCAL
ENABLE_PAID_AI=true
GEMINI_API_KEY=your-server-secret
AI_MODEL=gemini-2.5-flash-lite
DAILY_USER_AI_CALLS=5
DAILY_GLOBAL_AI_CALLS=50
```

Never put the Gemini key into Android. Production additionally requires `DATABASE_URL` beginning with `postgresql`, `FIREBASE_PROJECT_ID`, Google application credentials via `GOOGLE_APPLICATION_CREDENTIALS`, and `QDRANT_API_KEY`. Place the backend behind HTTPS; the Android client refuses plaintext connections. Keep PostgreSQL/Qdrant private. Use one backend worker for cache coalescing; quota counters remain atomic across workers, but identical requests could still be paid for twice across separate workers.

### Android public configuration

Pass Gradle properties with `-Pname=value`, or put them in your **local, uncommitted** Gradle configuration:

```properties
backendUrl=https://your-backend.example
firebaseApiKey=public-firebase-web-api-key
firebaseAppId=public-firebase-android-app-id
firebaseProjectId=your-firebase-project
revenuecatPublicKey=public-revenuecat-android-key
```

Enable email/password authentication in Firebase for package `com.studentmemory.copilot`. The client initializes Firebase from these public values; no `google-services.json` is required. Without Firebase configuration, the backend-session dialog accepts a development token for a LOCAL/MOCK server. With Firebase configuration, API calls use a refreshed Firebase ID token. Offline functions remain available without sign-in.

In RevenueCat, connect your Google Play application, define a `pro` entitlement and a current offering with your products. Set `REVENUECAT_SECRET_KEY` on the backend for server-side Pro verification. The Pro screen fetches the configured offering and supports purchase/restore via the SDK. No purchase is attempted unless the user selects a store product. Billing and authentication need testing against your configured projects before release. Production extraction and semantic-memory requests require an active Pro entitlement; deletion remains available without a subscription. Development modes bypass the subscription gate. Verification is cached for at most five minutes and never past the entitlement expiry.

## Cost controls

- Timetable extraction is cached by normalized image/text content, provider, schema version and user. The same timetable remains cached across days.
- Instruction extraction also includes the reference date because “next Wednesday” changes meaning over time.
- Maximum input: 8,000 text characters or a 2 MB PNG/JPEG, with an overall 3 MB request limit. Image metadata is stripped before forwarding; original screenshots are not stored.
- Maximum provider output: 2,048 tokens. No model thinking budget, model-generated reminder text or background AI calls.
- Defaults: five physical attempts per user per UTC day and fifty globally. Each retry consumes a reservation. Parsing/validation is retried at most once; HTTP errors aren't automatically retried. Provider-side budget limits should also be configured when you add a key.
- These are request limits, not a guaranteed currency budget. Actual charges depend on the configured provider's current prices and input size.
- Cached weather lasts one hour. Weather decisions, recurrence, risk, preparation and reminder wording are deterministic.
- The default development/test flow makes **zero paid AI calls**. Model downloads and optional server hosting still use bandwidth/storage/compute.

## Verified and remaining

Verified locally: Windows C++ compilation/tests with warnings treated as errors; backend tests and Ruff; Android ARM64/x86-64 compilation and lint; native static analysis. The API/vector tests use temporary local databases and a deterministic test encoder—no paid AI or cloud account.

No device was attached, so the APK has not been exercised on a phone/emulator. Live Firebase, RevenueCat, model extraction, hosted Qdrant and the real embedding model need integration testing after configuration.

This is an initial runnable implementation, not completion of every item in `BUILD.md`. Remaining work includes full automatic account sync/conflict handling and deletion propagation, richer reminder response history (ignored/opened/snoozed), and final visual/device acceptance testing. The memory screen uses labelled local keyword search in the default offline demo and semantic search with a LOCAL/PRODUCTION backend. Confirmed instructions can be saved to memory with a separate confirmation; screenshots store the reviewed extraction summary. The backend's initial relational record store needs normalized tables/migrations/indexes before production scale.

Notifications use battery-friendly inexact alarms. Android may delay them, and only HIGH/VERY HIGH plans create system notifications; lower priorities remain in the app. Scheduling refreshes daily and on app use, reboot, app replacement and clock/zone changes. Adaptive timing currently learns from Packed/Forgotten transitions. Preparation uses calendar free windows and does not yet apply waking-hour preferences.

## Design decisions and provider references

- C++ owns all product rules and state serialization. Kotlin handles UI, permissions, atomic file storage, networking transport, Firebase, RevenueCat and alarm delivery.
- JSON/UTF-8 byte arrays cross JNI. Native exceptions become error responses; failed commands never overwrite saved state. Android's `AtomicFile` retains the previous state if a write fails.
- Native Android widgets are used for this first shell; SDL/OpenGL is unnecessary for these forms/checklists.
- Backend records are scoped by authenticated identity. Vector IDs include that identity, and every vector search applies the user filter.
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output), [Firebase Android authentication](https://firebase.google.com/docs/auth/android/start), [RevenueCat Android SDK](https://www.revenuecat.com/docs/getting-started/installation/android).
