# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- OCR recall overhaul (engine v4, requires a re-run from 分析重建): recognized text previously covered far less than what was visibly on screen, caused by stacked losses across the pipeline — detection input was squeezed into a fixed 640×640 square (official limit is 960 longest-side; both ONNX models export dynamic dims that the code never used), source images were power-of-two sampled down to as little as half the target resolution (1080×2400 screenshots decoded to 540×1200), detection post-processing was axis-aligned connected-component bounding rects (bridged adjacent lines merged into one giant box whose multi-line strip garbled recognition, slanted text dragged in large background areas, and there was no unclip expansion or box-score filtering), long text lines were horizontally crushed into a fixed 320-wide recognition window, only the 32 largest text regions per image were recognized, and stored text was hard-truncated at 500 chars. Detection now runs at 960 longest-side with per-axis coordinate mapping; post-processing is rebuilt DB-style (connected components → row-profile line splitting at projection valleys → convex hull + rotating-calipers min-area rects → mean-score filter → polygon unclip at the official 1.6 ratio), recognition crops follow the detected rotation for slanted text, sources decode to exactly 2048 longest-side, recognition width follows line width (≤1280), up to 64 regions are recognized, and up to 2000 chars are stored/indexed.
- Analysis lane no longer stalls for hours between batches: waiting-type continuations (retry-delay windows, core-scan preemption) previously returned `Result.retry()`, compounding WorkManager's exponential backoff up to the 5-hour cap and leaving retry-delayed tasks with no timer to wake them — perceived as "runs a batch after each restart, then stops"; they now self-schedule fresh fixed-delay requests, and the analysis chain joins the startup poison-reset like the scan/pump chains. The legacy analysis pause marker (set by older "stop" buttons with no UI to resume) now also surfaces the "继续扫描" button.
- Tapping a photo from the home grid opens the tapped photo again (the instant-first-frame change briefly regressed positioning to the first item; positioning now lands on the first multi-element snapshot with its real absolute index).

### Added

- SAF folder-authorization fallback for trash deletion: files invisible to MediaStore (.nomedia dirs, unindexed custom roots) are the only undeletable class left on Android 13+; after the system dialog cannot cover them, the app explains why, asks for a one-time persistent folder grant, and deletes via document-tree traversal — subsequent deletions under a granted folder are silent. Per-row restore/delete buttons (long-press multi-select retained) and pending-delete rows show a spinner over a darkened thumbnail with actions disabled until the flow settles.

## [0.2.0] - 2026-10-04

### Fixed

- Cap video frame extraction at 10s with a watchdog (dedicated pool + external retriever release on timeout): corrupted videos previously blocked 20-40s each — or indefinitely — in MediaMetadataRetriever native calls, stalling the entire background enhancement lane.
- Photo viewer opens near-instantly from search/timeline/folder results (was multi-second to 20s+ white screens on large libraries): the tapped item renders immediately from data already in hand while the paging context loads in the background.

### Added

- Analysis rebuild page (设置 → 扫描操作 → 分析重建): per-stage full re-runs (OCR / faces / scene / quality / semantic) with engine versions shown — stage version bumps invalidate checkpoints but never enqueue work on their own, so upgrades now have an explicit re-run entry; replaces the interim "重新识别文字" button.
- Core scans are now pausable: the "停止本次分析" control previously only stopped enhancement lanes and was a silent no-op during core scans; it now pauses every lane durably (persisted across restarts), marks the active run as PAUSED keeping the last published snapshot, and a "继续扫描" button resumes from the persisted state.

- Chinese keyword search now matches text inside OCR lines and Chinese folder/file names: FTS rows are indexed as per-character + adjacent-bigram sequences and CJK queries become exact-token or consecutive-bigram phrase matches (unicode61's single-token CJK runs previously made only whole-sentence prefixes searchable); all FTS write paths (scan delta, staged commit, backup import, OCR sync) emit the expanded format.
- OCR accuracy: detection preprocessing now scales uniformly to the 640 square with mean-value padding instead of stretching any aspect ratio into it, and recognition crops are height-normalized to 48px with right padding instead of being squeezed into 320×48 — distorted glyphs were the dominant source of misrecognized characters; decode goes through the shared robust decoder (16-bit PNG support) at a 1280 cap, and up to 32 text regions per image are recognized (was 12, which silently dropped most text on long screenshots/posters).

### Added

- OCR stage version bumped to 3 to force re-recognition with the fixed preprocessing; as files are re-recognized their FTS rows are rebuilt in the new CJK-expanded format on the fly.
- Stop `ThumbnailCacheMaintenanceWorker` from re-enqueuing itself forever once a library has a full page of still-referenced thumbnails older than the 24h TTL (caused constant ~150ms WorkManager churn, 150%+ CPU, multi-second GC pauses that made every incremental scan appear stuck on "loading"); cleanup now advances past all-referenced windows and only continues when a window actually deleted something.
- Fix the incremental-scan lost-wake deadlock: `ScanWorker`'s unique WorkManager chain accumulated hours of exponential backoff from unbounded `Result.retry()` on deferred journals, and `APPEND_OR_REPLACE` never replaced a still-backing-off chain — deferred drains now self-schedule a fresh request with a fixed delay, poisoned scan/pump chains are reset on app start, and journal poison events terminalize to `FAILED` after 5 attempts instead of blocking the pipeline forever.
- Include OCR results in keyword search immediately: the OCR stage now rebuilds the FTS row for each recognized file in the same pass (previously `media_items_fts.ocrText` stayed empty until the next full scan, so OCR text was unsearchable).
- Trash permanent deletion on Android 13+: URI resolution now includes items already in the system trash (previously silently dropped to a doomed `File.delete()`), batch resolution moved off the main thread (clear-all no longer ANRs), unresolved paths are reported instead of silently left in trash, and the failure message no longer points at a permission that no longer exists.
- Keep the album file-tree scroll position when returning from the photo viewer: directory paging flows are now cached per query (same replay semantics as the timeline) and the tree's expand/view state survives navigation via `rememberSaveable`.
- Decode 16-bit PNGs (common ComfyUI output): bitmap decoding for thumbnails and semantic analysis falls back from `BitmapFactory` to `ImageDecoder`, so these files no longer fail analysis/thumbnail generation; the failed-tasks page renders their previews instead of showing a broken-file badge.
- Failed-tasks page: cancelling the ignore/delete confirmation now restores the previous selection state (single-item actions clear their temporary selection; multi-select keeps the user's selection).
- Restore missing `emap_512.bin` (face-swap emap matrix) — regenerated from `inswapper_128.onnx` via `scripts/extract_emap.py`; its absence silently degraded face-swap output to ≈ input.
- Make JSON index import atomic across media, FTS, face, and semantic-embedding tables; FTS records are now restored in batches instead of one row at a time.
- Preserve unchanged media-derived fields during a full scan and invalidate analysis checkpoints when media content changes, preventing completed analysis from being skipped after its result fields were replaced.
- Serialize semantic vectors with a locale-invariant decimal format and reject malformed/non-finite vector values instead of silently shortening a vector.
- Prevent built-in face analysis from clearing and clustering the complete face table; both face-provider paths now use bounded representative matching and pending clusters.
- Make thumbnail generation convergent with persistent tasks, transactional leases, unique WorkManager scheduling, exponential retry backoff, and terminal failure states.
- Batch trash cleanup and remove all associated face, embedding, analysis, plugin-feature, FTS, and thumbnail-task records only after physical deletion succeeds.
- Restore albums from the last atomically committed directory snapshot before foreground media reconciliation, eliminating repeated blocking album-tree construction on cold start while preserving stale data after interrupted or failed scans.

### Added

- Failed-tasks page (设置 → 扫描操作 → 查看失败任务): merged analysis/thumbnail/handoff failure lanes with per-file previews, unreadable files marked as corrupted, readable failure causes, and per-item or batch adjudication — ignore (keep file, mark corrupted, stop retrying) or move to trash; the settings scan card now also shows the persisted pipeline stage while it is converging.
- Room schema v16 with the `thumbnail_tasks` queue and migration of existing missing thumbnails.
- Room schema v17 with media scan generations and a persistent `analysis_tasks` queue.
- Room schema v28 with versioned album directory snapshots and an album-page synchronization status banner.

### Changed

- Documentation now describes the current local-media, on-device AI, model-download, import/export, and permission behavior.
- External APK/Dex plugin loading remains hidden and experimental; it is no longer documented as a supported end-user extension mechanism.
- Documentation: replace outdated Git LFS model workflow with `scripts/download_models.sh` / `extract_emap.py`; correct the model inventory and repository URLs.
- Limit analysis checkpoint lookups to the current batch and stop incremental analysis from taking a complete image-path snapshot.
- Build the album hierarchy from directory aggregates instead of complete media entities; album detail now consumes Room Paging, and missing indexer injection fails explicitly instead of falling back to an unbounded scan.
- Full scans now mark media with a scan generation and purge stale paths in bounded SQL batches; analysis, full reanalysis, and startup resume use persistent leased tasks instead of complete image-path snapshots.

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
