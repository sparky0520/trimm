# trimm iOS Client

This directory contains the iOS native client for trimm.

## Architecture & Technology Stack

- **UI Framework**: SwiftUI (using the latest declarative features)
- **Local Database**: SwiftData (for local project persistence, chat history, and evidence caching)
- **Dependency Manager**: Swift Package Manager (SPM)
- **Target OS**: iOS 17+ (to leverage the latest AVFoundation and SwiftData APIs)

### Core Modules

- `Media`: Built on `AVFoundation` (`AVVideoComposition`, `AVMutableComposition`), replacing Android's Media3. `CoreImage` is used for shaders (`CIFilter`) and `AVVideoCompositing` for custom overlays.
- `Evidence`: Uses the `Vision` framework for visual analysis (saliency, face detection), the `Speech` framework for transcription, and `CoreML` for custom ranking models.
- `Network`: Utilizes the official `supabase-swift` package for backend communication and `URLSession` for derivative uploads.
- `Domain`: Pure native Swift structs/classes mirroring the Android domain models (EditEngines, ConversationalRevision, EditorialPlan).
- `Work`: Uses the `BackgroundTasks` framework (`BGProcessingTask`) to handle intensive media operations in the background.

## Getting Started

This project uses [XcodeGen](https://github.com/yonaskolb/XcodeGen) to manage its `.xcodeproj` file. The project configuration and dependencies (like `supabase-swift`) are defined in `project.yml`.

1. If the `Trimm.xcodeproj` file is missing or you have added new files/dependencies to `project.yml`, run the following in this directory:
   ```bash
   xcodegen generate
   ```
2. Open `Trimm.xcodeproj` in Xcode to build, run, and develop the app using the iOS Simulator!
