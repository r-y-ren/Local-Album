# Contributing to LocalAlbum

Thank you for your interest in contributing! This document outlines the process for contributing to the project.

## Development Setup

1. **Fork** the repository and clone it locally.
2. Open the project in **Android Studio** (Hedgehog 2023.1+ recommended).
3. Wait for Gradle sync to complete.
4. Run `./gradlew assembleDebug` to verify the build.

### Prerequisites

- **JDK 17**
- **Android SDK 35** with NDK 27.0.12077973
- **CMake 3.22.1** (for native emutls shim)

### Model Files

Most AI features require ONNX/TFLite model files placed in `app/src/main/assets/models/`. See [Model Setup](#model-files) in the README.

## Development Workflow

### Branch Naming

- `feature/<description>` — new features
- `fix/<description>` — bug fixes
- `refactor/<description>` — code improvements
- `docs/<description>` — documentation only

### Code Style

- **Kotlin** with KDoc comments for public APIs
- Follow existing patterns: sealed class polymorphism, Flow-based reactivity
- Use `Dispatchers.Default` or `InferenceDispatchers.cpuBound` for CPU-heavy work
- Avoid blocking the main thread

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add face clustering visualization
fix: resolve ONNX session leak on rotation
refactor: extract ProgressReporter interface
docs: update plugin API documentation
```

### Testing

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run a specific test class
./gradlew testDebugUnitTest --tests "com.renyxin.localalbum.core.plugin.PluginRegistryTest"
```

Write tests for:

- New DAO queries
- Plugin lifecycle methods
- Pipeline stage execution
- Data transformation logic

### Pull Request Process

1. Ensure `./gradlew testDebugUnitTest` passes
2. Ensure `./gradlew assembleDebug` builds without errors
3. Update documentation if applicable
4. Add a changelog entry under `[Unreleased]` in `CHANGELOG.md`
5. Submit PR with a clear description of changes

## Architecture Guidelines

### Adding a New Analysis Stage

1. Implement the `AnalysisStage` interface
2. Add a `when` branch mapping in `PluginAnalysisPipeline.create()` (or register a new `CapabilitySlot` + Provider)
3. Set correct `stageId`, `dependencies`, and `displayName`

### Adding a New Capability Provider

1. Implement the capability interface (e.g., `SceneProvider`)
2. Register in `AppContainer`'s `capabilityRegistry` initialization block (`CapabilityRegistryV2().apply { ... }`) with a unique provider ID
3. Add default activation logic if applicable

### Adding a New Model Runtime

1. Implement `ModelRuntime` interface
2. Add format mapping in `ModelRuntime.create()` factory method
3. Add tensor parsing logic in `TensorMetadataParser`

## Questions?

Open a [GitHub Discussion](https://github.com/r-y-ren/Local-Album/discussions) for questions, ideas, or general conversation.
