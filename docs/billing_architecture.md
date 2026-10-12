# Trimm Billing & Credits Architecture

This document describes the billing architecture, consumable credit model, RevenueCat integration, Supabase database schemas, and backend enforcement for Trimm.

---

## 1. Overview & Monetization Model

Trimm uses a **Consumable Credits / Pay-as-you-go** monetization model deeply integrated with its privacy-first on-device editing architecture.

### Architectural Core: On-Device Video vs. Cloud AI
- **On-Device Video Processing (100% Free / 0 Credits)**: Raw video files **never leave the user's device**. Video scrubbing, real-time timeline preview, compositing, Core Image effects, and high-definition export to the Camera Roll are executed entirely on-device via AVFoundation (`AVMutableComposition`, `VideoProcessor.swift`, `ExportCoordinator.swift`).
- **Cloud AI Infrastructure (Credit Gated)**: Credits are strictly deducted for external AI operations that incur third-party API costs:
  1. **Audio Transcription** (3 credits): Extracting audio (`.m4a`), uploading chunks to `/v1/transcribe/uploads`, and generating word-level timestamps, speaker IDs, and silence intervals via AssemblyAI.
  2. **Conversational Timeline Editing** (2 credits): Running the LLM agent (`/v1/chat/completion` via Moonshot Kimi / OpenAI) to process natural language editing requests and generate `CompositionSchema` timeline patches (`apply_schema_patch`).

### Credit Costs
| Operation | Cost | Infrastructure / Provider | Description |
| :--- | :--- | :--- | :--- |
| **Conversational AI Edit** | 2 credits | Moonshot / Kimi / OpenAI | Generates `CompositionSchema` patch: cuts, captions, effects, transitions |
| **Audio Transcription** | 3 credits | AssemblyAI | Word-level timestamps, speaker identification, and silence detection |
| **On-Device Preview** | 0 credits (Free) | Apple AVFoundation | Instant local playback and multi-layer rendering |
| **Camera Roll Video Export**| 0 credits (Free) | Apple AVFoundation | High-resolution on-device rendering directly to user's photo library |

### Why 20 Starter Credits is the Perfect Free Tier
- **Typical Project Consumption**:
  - 1x Audio Transcription: **3 credits**
  - 8x Conversational Revisions ("cut silences", "yellow captions", "vintage look"): **16 credits**
  - High-res Video Export: **0 credits**
  - **Total per completed video**: **~19 credits**
- **User Impact & Monetization Psychology**:
  - With **20 starter credits**, every new creator gets **1 full video edit completely free** from import to export.
  - The creator experiences the full "aha!" moment, exports their first polished clip, and discovers the value of conversational editing.
  - When they run out of credits, purchasing the **100-credit Starter Pack ($4.99 / ~500 INR)** gives them **5x their entire initial free usage** (5 full video edits instead of just 2x), creating strong perceived value and a natural conversion trigger.

### Credit Packs (In-App Purchases via RevenueCat)
| Product Identifier | Package Name | Credits | Price (USD) | Effective Output |
| :--- | :--- | :--- | :--- | :--- |
| `ai.ctrlb.credits.100` | Starter Pack | 100 | $4.99 | ~5 complete video edits (5x the free tier) |
| `ai.ctrlb.credits.300` | Creator Pack | 300 | $11.99 | ~15 complete video edits (`MOST POPULAR`) |
| `ai.ctrlb.credits.1000` | Pro Studio Pack | 1000 | $29.99 | ~50 complete video edits with multi-clip projects (`BEST VALUE`) |

---

## 2. Supabase Database Schema

The initial migration is in `cloud/supabase/migrations/20261012060000_create_billing_and_credits.sql` and the 20-credits adjustment is in `cloud/supabase/migrations/20261012071500_update_initial_credits_to_20.sql`.

### Tables

#### `public.user_credits`
Tracks current balance and lifetime statistics per user:
- `user_id` (`TEXT PRIMARY KEY`): Matches the authenticated Supabase user ID.
- `balance` (`INTEGER NOT NULL DEFAULT 20 CHECK (balance >= 0)`): Active balance.
- `lifetime_purchased` (`INTEGER NOT NULL DEFAULT 0`): Cumulative credits purchased.
- `lifetime_spent` (`INTEGER NOT NULL DEFAULT 0`): Cumulative credits spent.
- `created_at` (`TIMESTAMPTZ`): Creation timestamp.
- `updated_at` (`TIMESTAMPTZ`): Last updated timestamp.

#### `public.credit_transactions`
Auditable immutable ledger recording every balance modification:
- `id` (`UUID PRIMARY KEY DEFAULT gen_random_uuid()`): Transaction identifier.
- `user_id` (`TEXT NOT NULL`): Foreign user identifier.
- `amount` (`INTEGER NOT NULL`): Signed credit change (negative for deductions, positive for purchases/grants).
- `balance_after` (`INTEGER NOT NULL CHECK (balance_after >= 0)`): Resulting balance.
- `type` (`TEXT NOT NULL`): `initial_grant`, `purchase`, `chat_completion`, `bgm_generation`, `transcription`, `sfx_fetch`, `refund`.
- `description` (`TEXT`): Human-readable reason for transaction.
- `reference_id` (`TEXT UNIQUE`): Unique idempotency key (e.g. RevenueCat event ID or request correlation ID).
- `metadata` (`JSONB`): Additional context (currency, price, store, environment).
- `created_at` (`TIMESTAMPTZ`): Timestamp.

### Stored Procedures
- `public.get_or_create_user_credits(p_user_id TEXT)`: Retrieves user balance; initializes with 50 welcome credits if the user is new.
- `public.deduct_user_credits(p_user_id TEXT, p_amount INT, p_type TEXT, p_description TEXT, p_reference_id TEXT)`: Concurrency-safe deduction using `SELECT FOR UPDATE`. Rejects transaction if balance < required amount.
- `public.add_user_credits(p_user_id TEXT, p_amount INT, p_type TEXT, p_description TEXT, p_reference_id TEXT, p_metadata JSONB)`: Atomically adds credits and prevents double-crediting using `p_reference_id` idempotency.

---

## 3. Backend HTTP Endpoints & Enforcement

The cloud backend server (`cloud/src/server.js`) enforces credit verification and deductions:

### Endpoints
- `GET /v1/billing/credits`:
  - **Auth**: Bearer JWT.
  - **Response**: `{ userId, balance, lifetimePurchased, lifetimeSpent, costs }`.
- `GET /v1/billing/history`:
  - **Auth**: Bearer JWT.
  - **Response**: `{ transactions: [...] }`.
- `POST /v1/billing/revenuecat-webhook`:
  - **Auth**: Header verification (`REVENUECAT_WEBHOOK_AUTH_KEY`).
  - **Payload**: RevenueCat webhook event.
  - **Processing**: Automatically parses `NON_RENEWING_PURCHASE` or `INITIAL_PURCHASE`, matches `product_id`, and calls `add_user_credits`.

### Endpoint Gating (HTTP 402 Payment Required)
When a user attempts an AI action with insufficient credits:
```json
{
  "error": "Insufficient credits. Required: 2, current balance: 0.",
  "code": "insufficient_credits",
  "balance": 0,
  "required": 2,
  "requestId": "..."
}
```
Endpoints gated:
- `/v1/chat/completion`: Deducts 2 credits.
- `/v1/audio/generate-bgm`: Deducts 5 credits.
- `/v1/audio/fetch-sfx`: Deducts 1 credit.
- `/v1/transcribe` & `/v1/transcribe/uploads/:id/complete`: Deducts 3 credits.

---

## 4. iOS Implementation

### `BillingManager` (`mobile/ios/Trimm/Billing/BillingManager.swift`)
- Single reactive `@Observable` coordinator.
- Configures RevenueCat SDK with `Secrets.revenueCatApiKey`.
- Tracks live balance, package offerings, purchase states, and transaction ledger.
- Synchronizes with Supabase Auth state: automatically logs in to RevenueCat with `user.id.uuidString` on sign-in and logs out on sign-out.
- Catches HTTP 402 in network requests and triggers the top-up sheet.

### `CreditStoreView` (`mobile/ios/Trimm/Billing/CreditStoreView.swift`)
- Built to match Trimm’s dark theme (`Color.ink`, `Color.workspace`, `Color.raised`, `Color.cream`).
- Interactive tier selection with visual highlights for `MOST POPULAR` and `BEST VALUE`.
- Native Apple In-App Purchase trigger.
- Full breakdown of how credits are used.
- "Restore Purchases" and App Store guideline compliance copy.

### UI Integration Points
1. **Top Navigation Bar**: Prominent credit pill (`⚡ <balance>`) allowing users to check balance and tap to top up at any time.
2. **App Drawer / Sidebar**: In `SidebarView`, users see their credits and a "Top up" action right next to their profile.
3. **Editor Screen**: Header displays credit count and provides immediate access to the store.
