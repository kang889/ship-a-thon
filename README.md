# PackBack

**Bring it. Do it. Bring it back.**

PackBack is an Android app for university students who forget what to bring to class — or what to take with them when class ends.

It uses the student's timetable and class requirements to generate a simple daily packing checklist, deduplicates items needed across multiple classes, and sends context-aware reminders before class and near the end of each class.

## What PackBack does

- **Daily packing list** — combines required items across the day and removes duplicates.
- **Per-class Bring Back reminders** — reminds students to check for their belongings before leaving each class.
- **Timetable management** — add, edit, repeat, and manage classes or events.
- **One-off requirements** — attach an item to a specific class occurrence.
- **Cloud sync** — signed-in users can sync their events across devices.
- **Weather context** — can suggest an umbrella when rain is relevant to the student's schedule.
- **AI-assisted import** — timetable/instruction extraction can turn unstructured input into reviewable event data.
- **Adaptive reminders** — reminder timing can adapt from prior user behaviour.
- **RevenueCat integration** — used for PackBack's Pro entitlement and subscription flow.

## How it works

PackBack follows a **day-as-one-outing** model for packing:

```text
Calculus: Laptop, Calculator
DSA:      Laptop, iPad
OS:       Laptop, Notebook

Daily packing list:
- Laptop
- Calculator
- iPad
- Notebook
```

The student packs shared items once, while **Bring Back reminders remain class-specific** so they are reminded to take their belongings when leaving each classroom.

The main product logic is implemented in a platform-independent **C++ core**. Android-specific concerns such as UI, notifications, Firebase authentication, networking, and RevenueCat are handled by a thin **Kotlin** layer.

## Tech stack

- **Android:** Kotlin, Android SDK, AlarmManager
- **Core logic:** C++20
- **Android ↔ C++:** JNI
- **Backend:** Python, FastAPI
- **Database:** PostgreSQL
- **Authentication:** Firebase Authentication
- **Subscriptions:** RevenueCat
- **Build:** Gradle + CMake
- **CI:** GitHub Actions

---

## Try PackBack

### Option 1 — Download the APK from GitHub Actions

The easiest way to evaluate PackBack is to use the APK built automatically from `main`.

1. Open the repository's **Actions** tab.
2. Open the latest successful **Build and test** workflow run on `main`.
3. Under **Artifacts**, download **`student-memory-debug`**.
4. Extract the archive and install `app-debug.apk` on an Android device or emulator.
5. Allow notification permission when prompted.

The APK supports:

- Android 8.0+ (API 26+)
- ARM64 Android devices
- x86-64 Android emulators

> This is a debug APK for evaluation and testing; it is not a Google Play release build.

### Option 2 — Build locally

#### Requirements

- JDK 17
- Android SDK 35
- Android NDK `27.2.12479018`
- CMake 3.22.1+

Clone the repository:

```bash
git clone https://github.com/kang889/ship-a-thon.git
cd ship-a-thon
```

Build the Android APK:

**Windows**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
platform\android\gradlew.bat -p platform\android assembleDebug
```

**macOS / Linux**

```bash
platform/android/gradlew -p platform/android assembleDebug
```

The APK will be generated at:

```text
platform/android/app/build/outputs/apk/debug/app-debug.apk
```

Install it on an Android device or emulator, then allow notifications when prompted.

The default Android configuration targets the hosted PackBack demo backend, so evaluators do **not** need to run the backend locally for the normal app demo.

---

## Run the test suite

### C++ core

```bash
cmake -S . -B out
cmake --build out
ctest --test-dir out --output-on-failure
```

### Backend

Requires Python 3.12:

```bash
python -m venv .venv
python -m pip install -r backend/requirements.txt
python -m pytest -q
ruff check backend tests/test_backend.py
```

### Android

```bash
platform/android/gradlew -p platform/android assembleDebug lintDebug
```

On Windows, use `platform\android\gradlew.bat`.

---

## Repository structure

```text
source/            C++ product logic and app state
tests/             C++ and backend tests
platform/android/  Android application and JNI bridge
backend/           FastAPI backend
data/              Data-driven reminder configuration
.github/workflows/ Continuous integration
```

For deeper implementation and setup details, see:

- [`BUILD.md`](BUILD.md)
- [`docs/SETUP.md`](docs/SETUP.md)

---

## Project goal

PackBack is designed around one simple question:

> **What do I need to remember before I leave — and what must I remember to take back with me?**

The goal is to reduce the amount of manual planning students need to do while keeping reminder logic deterministic, testable, and useful even when connectivity is limited.
