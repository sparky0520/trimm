# Trimm Application

This repository contains the full source code for the Trimm application, including the mobile clients, backend, and Supabase database structure.

## Structure

- `cloud/` - Node.js Backend services and Supabase configurations
- `mobile/` - Mobile clients
  - `mobile/android/` - Native Android client (Kotlin + Jetpack Compose)
  - `mobile/ios/` - Native iOS client (Swift + SwiftUI)

## Prerequisites

- Node.js (v18+)
- npm or yarn
- Supabase CLI
- Android Studio (for Android client)
- Xcode (for iOS client)

## Setup & Running

### 1. Supabase (Database)

First, set up and run the local Supabase instance.

```bash
cd cloud
supabase start
```

This will run the migrations located in `cloud/supabase/migrations` and provide you with local database URLs and API keys. Note these down.

### 2. Backend (Cloud)

Set up the Node.js backend. Create a `.env` file in the `cloud/` directory with necessary credentials (e.g., Supabase URL, LLM API keys).

```bash
cd cloud
npm install
npm run backend:serve
```
*(Alternatively, you can just use `npm start`)*

### 3. Android Client

To run the Android client:

1. Open Android Studio.
2. Select **Open an existing project**.
3. Navigate to and select the `mobile/android` directory.
4. Let Gradle sync and build the project.
5. Setup an Emulator or connect a physical device.
6. Click the **Run** button to install and launch the app.

### 4. iOS Client

To run the iOS client:

1. Open Xcode.
2. Select **Open a project or file**.
3. Navigate to the `mobile/ios` directory and open the `.xcodeproj` or `.xcworkspace` file (if cocoapods is used).
4. Select a Simulator or connect a physical iOS device.
5. Click the **Run** button (or press `Cmd + R`) to build and launch the app.

## Additional Notes

- Both clients maintain feature parity and communicate with the `trimm-cloud` backend.
- Ensure the backend URL in the mobile clients points to your local network IP when testing on physical devices.
