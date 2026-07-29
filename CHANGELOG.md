# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Restore missing `emap_512.bin` (face-swap emap matrix) — regenerated from `inswapper_128.onnx` via `scripts/extract_emap.py`; its absence silently degraded face-swap output to ≈ input.
- Make JSON index import atomic across media, FTS, face, and semantic-embedding tables; FTS records are now restored in batches instead of one row at a time.
- Preserve unchanged media-derived fields during a full scan and invalidate analysis checkpoints when media content changes, preventing completed analysis from being skipped after its result fields were replaced.
- Serialize semantic vectors with a locale-invariant decimal format and reject malformed/non-finite vector values instead of silently shortening a vector.

### Changed

- Documentation now describes the current local-media, on-device AI, model-download, import/export, and permission behavior.
- External APK/Dex plugin loading remains hidden and experimental; it is no longer documented as a supported end-user extension mechanism.
- Documentation: replace outdated Git LFS model workflow with `scripts/download_models.sh` / `extract_emap.py`; correct the model inventory and repository URLs.

## [0.1.0] - 2026-07-27

### Added

- Core media indexing engine (HybridIndexer) with full/incremental scan
- Room database v11 with FTS4 full-text search
- AI plugin system with DexClassLoader-based hot-loading
- Plugin manifest JSON editor with real-time validation
- Model import wizard (4-step visual flow)
- Plugin manager UI with enable/disable and ordering
- Dynamic capability registry (CapabilityRegistryV2) with provider switching
- Face detection and clustering (ML Kit + InsightFace/RetinaFace/SCRFD)
- Face swap pipeline (ReActor-like, ONNX-based inswapper with emap latent transform)
- Scene classification (MobileNetV2 TFLite + heuristic fallback)
- Quality scoring (heuristic analysis)
- OCR text recognition (PaddleOCR + ML Kit Chinese + GLM-OCR)
- Semantic embedding (EVA02-CLIP ONNX + MobileCLIP TFLite + concept vectors)
- Semantic search engine with hybrid retrieval
- Similar/duplicate photo detection (perceptual hash)
- Geographic clustering and reverse geocoding
- Map view (osmdroid-based)
- Timeline view with section grouping
- Album tree builder with directory hierarchy
- Recommendation engine
- Database JSON import/export for cross-device data migration
- Global progress indicator with ETA estimation
- Trash cleanup worker (WorkManager)
- Compose Material 3 UI with dark/light theme support
- ONNX Runtime 1.19.2, TensorFlow Lite 2.14.0, PyTorch Mobile 1.13.1 runtimes
- OpenCV 5.0 integration (affine transforms, Poisson blending)
- emutls shim for cross-library thread-local storage compatibility
- Extension plugin registry (InSwapper, style transfer)

[0.1.0]: https://github.com/r-y-ren/Local-Album/releases/tag/v0.1.0
