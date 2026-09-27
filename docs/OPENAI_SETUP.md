# Enable OpenAI extraction in PackBack

The backend now uses OpenAI's Responses API for timetable screenshots and lecturer messages. Android still receives the same validated data and requires review/confirmation before saving. Memory is retrieval, not an LLM chatbot.

## Add your key later

A local `.env` has been prepared (ignored by Git). If missing, copy `.env.example` to `.env`. Set these values in that file:

```dotenv
APP_MODE=LOCAL
ENABLE_PAID_AI=true
OPENAI_API_KEY=your-openai-api-key
AI_MODEL=gpt-4.1-mini
MEMORY_MODE=lexical
DAILY_USER_AI_CALLS=5
DAILY_GLOBAL_AI_CALLS=50
DEV_TOKEN=local-development-only
```

Obtain the key from your OpenAI API project. Do not paste it in chat or put it in Android/Gradle. The key needs API billing/credits and access to the configured model. Until you add it, keep `APP_MODE=MOCK` and `ENABLE_PAID_AI=false`; all extraction results are explicitly simulated.

`MEMORY_MODE=lexical` preserves labelled keyword retrieval and avoids requiring Qdrant or an embedding-model download. It is the default for local development and for the prototype cloud deployment (see [DEPLOYMENT.md](DEPLOYMENT.md)). `MEMORY_MODE=semantic` additionally requires Qdrant. Either way, production still requires PostgreSQL, Firebase and the subscription configuration described in [SETUP.md](SETUP.md).

## Start the backend (Windows PowerShell, repository root)

```powershell
.venv/Scripts/python.exe -m pip install -r backend/requirements.txt
.venv/Scripts/python.exe -m uvicorn backend.api.main:create_app --factory --env-file .env --host 127.0.0.1 --port 8000 --workers 1 --no-access-log
```

Restart after changing `.env`. Existing shell environment variables override `.env`; remove stale values if configuration appears unchanged. Check `http://127.0.0.1:8000/api/v1/health`: real extraction reports `mode: LOCAL` and `paid_ai: true`. This confirms configuration, not a successful paid request.

## Connect Android

Build an emulator-connected APK:

```powershell
$env:ANDROID_HOME="$PWD/out/android-sdk"
platform/android/gradlew.bat -p platform/android assembleDebug -PbackendUrl=http://10.0.2.2:8000
out/android-sdk/platform-tools/adb.exe install -r platform/android/app/build/outputs/apk/debug/app-debug.apk
```

The existing debug emulator flow uses the default development token. If you override `DEV_TOKEN`, check the app's existing session/authentication configuration. A physical phone needs a reachable HTTPS backend URL instead of `10.0.2.2`; rebuild with `-PbackendUrl=https://your-host`. Never expose the development-token server publicly. Firebase and RevenueCat remain required for production.

## Test real extraction after adding your key

1. Start the backend and app. Choose Import timetable → screenshot, select a readable PNG/JPEG, and compare every proposed class/day/time/room to the image. Nothing is saved until confirmation.
2. Choose Lecturer instruction for an existing class, paste a message with an explicit date, required items and preparation tasks, then inspect the review. Confirm only after checking the fields.
3. Submit identical input again. The backend should return `cached: true` without another provider call. You can inspect responses through `http://127.0.0.1:8000/docs` using `Authorization: Bearer local-development-only`.
4. Test an unreadable image/unrelated message and a wrong key. The app should offer manual entry, with no partial data saved.

Timetables extract class details; lecturer messages extract required items and tasks. Missing course/room fields remain empty. Task duration retains the app's 60-minute default when absent; review it before confirming. Unresolvable required dates/times are rejected rather than invented.

## Cost and verification

- Default model: `gpt-4.1-mini`; change `AI_MODEL` only to a Responses model supporting image input and strict structured output.
- Existing content cache, user isolation and daily quotas are preserved. Every physical attempt counts, including the one allowed validation retry. HTTP/authentication/rate-limit failures are not automatically retried.
- Output is capped at 2,048 tokens. Long timetables may need smaller screenshots; incomplete output is rejected. Images use high detail for text readability. Input limits remain 8,000 characters / 2 MB / 12 million pixels.
- Responses use `store=false`. This is not a claim of zero provider data retention. No API keys or screenshot contents are added to application logs.
- Request limits are not a currency budget; configure provider usage controls too.
- Automated tests use intercepted HTTP responses for text/images, errors, validation, caching and confirmation. Live recognition quality and account access can only be verified after adding a real key.

Official references: [Responses image input](https://developers.openai.com/api/docs/guides/images-vision), [structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini).

## Verification recorded for this change

- 37 backend tests passed; Ruff passed. OpenAI requests were intercepted, so no paid calls were made.
- Android debug build succeeded with `backendUrl=http://10.0.2.2:8000`.
- Local backend health and both mock extraction endpoints passed.
- The emulator pasted-timetable flow reached the backend and displayed Edit/Discard/Confirm; the test result was discarded.
- Real model recognition, provider credentials and billing remain unverified until a key is supplied.
