# Trimm Application

This repository contains the full source code for the Trimm application, including the React Native cross-platform client and the Node.js backend services.

## Architecture

Trimm is a completely declarative, AI-driven video editor. The core principle is that the AI never manipulates the UI directly. Instead, the AI agent outputs tool calls that mutate a unified **Project Schema** (the single source of truth).

The application components are:
- **`app/`**: The React Native cross-platform client (iOS & Android)
  - Uses `zustand` for state management.
  - Uses `@shopify/react-native-skia` for real-time high-performance preview rendering.
  - Uses `react-native-reanimated` for gesture-driven timeline interactions and UI motion.
  - Uses `ffmpeg-kit-react-native` to compile the schema into background video exports.
- **`cloud/`**: Node.js Backend services
  - Exposes an SSE-streaming OpenAI agent endpoint (`/v1/chat/completion`) connected to Moonshot AI / OpenAI.
  - Emits JSON tool calls directly to the mobile app for real-time UI updates.

## Prerequisites

- Node.js (v18+)
- npm or yarn
- Supabase CLI
- Xcode (for iOS development)
- Android Studio (for Android development)

## Setup & Running

### 1. Supabase (Database)
Start the local Supabase instance to run migrations.
```bash
cd cloud
supabase start
```

### 2. Backend (Cloud)
Set up the Node.js backend. Ensure you have the required API keys in `cloud/.env`.
```bash
cd cloud
npm install
npm start
```
The server will run on `http://localhost:8787`.

### 3. Mobile App (React Native)
Install dependencies and run the metro bundler.
```bash
cd app
npm install
npm run ios # or npm run android
```

Make sure the backend is running locally so the AI chat can connect to the server's SSE endpoint. 

## Documentation
- **Architecture**: See `architecture.md` for a deep dive into the schema-driven rendering.
- **Web Pages**: Visit the `docs/` directory for visual explainers and cost estimation tools.
