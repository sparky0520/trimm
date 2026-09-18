# React Native Best Practices

When working on features, configurations, or assets that span both mobile platforms (e.g., app icons, splash screens, permissions, UI components), **always prefer common, cross-platform solutions based on React Native** before diverging into platform-specific implementations.

- Look for existing React Native CLI tools, npm packages, or libraries (e.g., `@bam.tech/react-native-make` for icons) that handle both Android and iOS simultaneously.
- Avoid manually editing files inside the `android/` and `ios/` specific folders unless a unified React Native solution does not exist or fails to meet the requirements.
- By defaulting to cross-platform tools, we maintain consistency, reduce duplicate work, and keep the native project files cleaner and easier to upgrade.
