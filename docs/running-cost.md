# Trimm - Infrastructure & Running Costs

This document breaks down the infrastructure components required to run the Trimm backend and their associated estimated running costs. This provides a clear visualization of where operating expenses are allocated as the app scales.

## 1. Hosting & Compute (Fly.io)

**Component**: Node.js Express Backend API  
**Configuration**: 
- Shared CPU, 1024MB RAM (`shared-cpu-1x@1024MB`)
- Scale-to-zero enabled (`min_machines_running = 0`)
- Auto-stop and Auto-start enabled

**Cost Implication**: 
- **Idle Cost**: $0.00 (Due to scale-to-zero).
- **Active Cost**: ~$5.70 / month if running 24/7.
- **Estimated Average**: **~$1.00 - $3.00 / month** depending on daily active usage bursts.

---

## 2. Database & Auth (Supabase)

**Component**: PostgreSQL Database, User Auth, and S3-Compatible Storage (SFX cache)
**Configuration**: 
- Shared Supabase instance handling Clerk/Google Auth bridging and persistent job storage.

**Cost Implication**: 
- **Free Tier**: $0/mo (500MB DB space, 1GB storage, 50,000 MAU).
- **Pro Plan (if scaled)**: **$25.00 / month** base + usage overages.

---

## 3. Large Language Models (Moonshot AI / OpenAI)

**Component**: Agent Decision Provider (`kimi-k3`)
**Purpose**: Core planner orchestration and chat completion logic for the AI assistant.

**Cost Implication**:
- Paid entirely via API usage (Pay-as-you-go).
- Prices scale linearly based on the amount of transcript context fed into the model.
- *Optimization Note*: Caching chat context and stripping non-essential transcript elements keeps context windows small.

---

## 4. Transcription (AssemblyAI)

**Component**: Speech-to-Text API
**Purpose**: Processing user uploaded audio and video for transcriptions.

**Cost Implication**:
- **Pay-as-you-go**: ~$0.37 per hour of audio processed.
- Heavy usage by users directly translates to increased transcription costs.

---

## 5. Audio & SFX Generation (ElevenLabs & Replicate)

**Component**: TTS (Text-to-Speech) and Background Music / SFX Generation
**Purpose**: Synthesizing audio assets dynamically based on agent plans.

**Cost Implication**:
- **ElevenLabs**: Measured in characters generated (e.g., ~$0.30 per 1,000 characters depending on tier).
- **Replicate**: Billed per second of compute time (GPU time) used to generate the audio models.
- *Optimization Note*: `trimm-sfx-cache` is implemented to aggressively cache generated SFX to avoid regenerating identical prompt requests.

---

## Summary of Fixed vs Variable Costs

| Category | Type | Estimated Cost |
| :--- | :--- | :--- |
| **Fly.io Compute** | Variable (Time) | ~$2.00 / mo |
| **Supabase** | Fixed | $0.00 / mo (Free Tier) |
| **Moonshot AI (LLM)** | Variable (Tokens) | Pay-as-you-go |
| **AssemblyAI** | Variable (Duration)| Pay-as-you-go |
| **Generative Audio** | Variable (Compute)| Pay-as-you-go |

**Bottom Line**: The architecture is highly optimized for **low fixed costs**. Since Fly.io scales to zero and Supabase offers a generous free tier, maintaining the app with 0 users costs practically $0. Costs scale dynamically only when users are actively transcribing, planning, and generating audio.
