# Nexus Browser - Android App

A feature-rich native Android browser with advanced capabilities including video downloading, bookmarks, and dark mode support.

## Project Structure

This is a properly structured Android Gradle project with the following layout:

```
NexusBrowser/
├── .github/
│   └── workflows/
│       └── build.yml          # GitHub Actions CI/CD workflow
├── app/                       # Main app module
│   ├── build.gradle.kts       # App-level build configuration
│   ├── proguard-rules.pro     # ProGuard/R8 obfuscation rules
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/
│           │   └── com/nexus/browser/  # Kotlin source files
│           └── res/                     # Resources (layouts, drawables, etc.)
├── build.gradle.kts           # Project-level build configuration
├── settings.gradle.kts        # Gradle settings and module configuration
├── gradle.properties          # Gradle build properties
├── .gitignore                 # Git ignore rules
└── README.md                  # This file
```

## Build Configuration

### Latest Versions
- **Android Gradle Plugin (AGP)**: 8.3.2
- **Kotlin**: 1.9.24
- **Gradle**: 8.7+
- **Target SDK**: 34
- **Min SDK**: 24 (Android 7.0)
- **Compile SDK**: 34 (Android 14)

### Key Dependencies
- **AndroidX Core KTX**: 1.13.1
- **AppCompat**: 1.7.0
- **Material Design**: 1.12.0
- **Constraint Layout**: 2.1.4
- **RecyclerView**: 1.3.2
- **Coroutines**: 1.8.1
- **Lifecycle**: 2.8.1

## Building Locally

### Prerequisites
- Android Studio (Latest)
- JDK 17 or higher
- Android SDK 34

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Build and run tests
./gradlew build test

# Clean build
./gradlew clean assembleDebug
```

## GitHub Actions CI/CD

The project includes an automated build workflow (`.github/workflows/build.yml`) that:
- Triggers on push/PR to main, master, or develop branches
- Uses `gradle/actions/setup-gradle@v3` for reliable Gradle setup
- Builds the Debug APK using Gradle 8.7+
- Uploads build artifacts
- Provides build failure summaries

### Workflow Features
- **JDK 17** with Temurin distribution
- **Gradle Caching** for faster builds
- **Stacktrace** on build errors
- **APK Artifact Upload** (30-day retention)

## Project Fixes Applied

### Gradle Configuration
✅ Created proper `settings.gradle.kts` with repository configuration  
✅ Created project-level `build.gradle.kts` with plugin versions  
✅ Updated app-level `build.gradle.kts` with latest stable versions  
✅ Added `gradle.properties` for optimization settings  
✅ Proper module structure with `/app` directory  

### Dependencies
✅ Updated all AndroidX libraries to latest stable versions  
✅ Updated Coroutines to 1.8.1  
✅ Updated Lifecycle to 2.8.1  
✅ Updated Material Design to 1.12.0  

### CI/CD
✅ Created GitHub Actions workflow with gradle/actions/setup-gradle@v3  
✅ Direct `gradle assembleDebug` without requiring gradlew  
✅ Proper JDK 17 setup with caching  
✅ APK artifact upload configuration  

### Optimization
✅ ProGuard/R8 enabled for release builds  
✅ Resource shrinking enabled for release builds  
✅ Gradle parallel builds enabled  
✅ Gradle caching and daemon enabled  

## Development Features

### ProGuard/R8 Obfuscation
- Enabled in release builds
- Dead code elimination (~30-40% size reduction)
- Uses `proguard-rules.pro` for custom configurations
- Disables null/parameter assertions for performance

### ViewBinding
- Enabled for type-safe view references
- Replaces findViewById() calls

### Kotlin Compiler Optimizations
- Disabled parameter assertions
- Disabled call assertions
- Official code style

## Troubleshooting

### Build Failures
1. Clean build: `./gradlew clean assembleDebug`
2. Check JDK version: `java -version` (should be 17+)
3. Clear Gradle cache: `rm -rf ~/.gradle`
4. Update Android SDK: Use Android Studio SDK Manager

### GitHub Actions Issues
- Ensure `settings.gradle.kts` exists (it does)
- Check Java/JDK version compatibility
- Verify branch names match workflow triggers

## Testing

```bash
# Run unit tests
./gradlew test

# Run Android tests
./gradlew connectedAndroidTest

# Run with coverage
./gradlew testDebugUnitTest testDebugAndroidTestCoverage
```

## Release Building

```bash
# Create release build
./gradlew assembleRelease

# Bundle for Play Store
./gradlew bundleRelease
```

APK files are generated in: `app/build/outputs/apk/[debug|release]/`

## Contributing

1. Create a feature branch
2. Make your changes
3. Ensure `./gradlew build` passes
4. Push and create a PR

## License

[Specify your license here]

## Support

For issues or questions, please create an issue in the repository.
