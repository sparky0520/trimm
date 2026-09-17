# Trimm Architecture (Cross-Platform Mobile-First AI Video Editor)

## Goals

- Single React Native codebase for Android and iOS
- AI agent works identically on all platforms
- Platform-agnostic editing engine (declarative schema)
- High-performance graphics and animations via Skia
- Export powered by FFmpeg

---

# High-Level Architecture

┌─────────────────────┐
│      AI Agent       │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   Agent Contracts   │
│   (Tool Layer)      │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   Project Schema    │
│  (Source of Truth)  │
└──────────┬──────────┘
           │
     ┌─────┴─────┐
     ▼           ▼
┌─────────┐ ┌─────────┐
│ Preview │ │ Export  │
│ Engine  │ │ Engine  │
└─────────┘ └─────────┘
     │           │
     ▼           ▼
 React Native   FFmpeg
 Skia           Native

---

# Core Principle

The AI should never manipulate UI.

Instead:

AI → Tool Calls → Project Schema

The UI simply visualizes the schema.

---

# Agent Contract Examples

## Add Text

{
  "tool": "add_text",
  "text": "Hello World",
  "start": 0,
  "end": 5
}

## Add Clip

{
  "tool": "add_clip",
  "source": "video.mp4",
  "start": 0
}

## Add Animation

{
  "tool": "animate",
  "target": "text_123",
  "animation": "fade_in"
}

---

# Project Schema

Example:

{
  "timeline": [
    {
      "type": "video",
      "src": "video.mp4",
      "start": 0,
      "end": 10
    },
    {
      "type": "text",
      "content": "Hello World",
      "start": 1,
      "end": 5,
      "animation": "fade_in"
    }
  ]
}

This schema becomes the single source of truth.

---

# Frontend

## React Native

Responsible for:

- Screens
- Timeline UI
- Asset picker
- User interaction
- AI chat

Expected code sharing between Android and iOS: 100%

---

# Graphics Engine

## React Native Skia

Responsible for:

- Text rendering
- Shapes
- Particles
- Overlays
- Masks
- Filters
- Stickers
- Animated graphics

Benefits:

- GPU accelerated
- Cross-platform
- Runs on Android and iOS

---

# Animation Engine

## React Native Reanimated

Responsible for:

- Timeline scrubbing
- Layer animation
- UI animation
- Motion effects

Examples:

- Fade
- Slide
- Scale
- Bounce
- Rotation

---

# Video Processing

## FFmpeg (ffmpeg-kit-react-native)

Responsible for:

- Trimming
- Merging
- Encoding
- Complex Animations mapped from Skia
- Audio extraction

---

# Rendering Pipeline

User Action
    ↓
AI Tool Call
    ↓
Project Schema Updated
    ↓
Preview Engine Reads Schema
    ↓
Skia Renders Frame
    ↓
User Sees Result

---

# Export Pipeline

Project Schema
      ↓
Render Instructions
      ↓
FFmpeg Processing
      ↓
Final MP4

---

# Future Expansion

Because the AI only talks to contracts:

AI
 ↓
Contracts
 ↓
Project Schema

You can later replace:

- React Native Preview → Web Preview
- FFmpeg → Cloud Renderer
- Mobile App → Desktop App

without changing the AI prompts or tools.

---

# Recommended Stack

Frontend:
- React Native

Graphics:
- React Native Skia

Animations:
- React Native Reanimated

Video Processing:
- FFmpeg

State Management:
- Zustand

Agent:
- OpenAI / Anthropic / Gemini

Backend:
- Node.js

Storage:
- S3 compatible object storage

Database:
- PostgreSQL

---

# Rule to Never Break

AI should modify the project schema.

AI should NOT:
- Click buttons
- Manipulate UI
- Depend on screen layouts

The UI is a viewer/editor of the schema.

The schema is the product.