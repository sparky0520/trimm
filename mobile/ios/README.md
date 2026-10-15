# trimm iOS Client

This directory contains the iOS native client for trimm.

## Architecture & Technology Stack

- **UI Framework**: SwiftUI (using `@Observable` pattern)
- **Local Persistence**: JSON document persistence for `MobileProject` and `CompositionSchema`
- **Dependency Manager**: Swift Package Manager (SPM) via `project.yml`
- **Target OS**: iOS 17+

### Core Modules

- `Media`: Built on `AVFoundation` (`AVMutableComposition`, `AVMutableVideoComposition`, `AVMutableAudioMix`). Utilizes `CoreImage` for filters (`CIFilter`) and video composition handler. Both preview (`AVPlayerItem`) and export (`AVAssetExportSession`) share the exact same raw composition builder.
- `Network`: Utilizes `supabase-swift` for authentication and project cloud synchronization, and custom streaming client for LLM agent completion over SSE.
- `Domain`: Native Swift structs/classes modeling `CompositionSchema`, `MobileProject`, `TimelineNode` (Video, Text, Effect, Audio), and undo/redo snapshots.
- `Billing`: Built on `StoreKit 2` with balance tracking synced to Supabase.

## Getting Started

This project uses [XcodeGen](https://github.com/yonaskolb/XcodeGen) to manage its `.xcodeproj` file. The project configuration and dependencies (like `supabase-swift`) are defined in `project.yml`.

1. If the `Trimm.xcodeproj` file is missing or you have added new files/dependencies to `project.yml`, run the following in this directory:
   ```bash
   xcodegen generate
   ```
2. Open `Trimm.xcodeproj` in Xcode to build, run, and develop the app using the iOS Simulator!
