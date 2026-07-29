# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability within LocalAlbum, please **do not** file a public issue. Instead, send an email to the project maintainers.

We take all security reports seriously and will respond within **48 hours** with:

- Acknowledgment of the report
- An initial assessment of severity
- An estimated timeline for a fix

Once a fix is prepared, we will:

1. Release a patch version
2. Credit the reporter (unless anonymity is requested)
3. Publish a security advisory

## Scope

The following are considered in-scope for security reports:

- Remote code execution vectors
- Data leakage or privacy bypasses
- SQL injection (via FTS4 queries)
- Path traversal in file operations
- Unsafe handling of model packages or hidden experimental plugin loading
- Authentication/authorization bypass

## Data handled by the app

The local index can contain media paths, EXIF/GPS metadata, OCR text, face embeddings, semantic embeddings, thumbnails, and model state. JSON exports can include media records, FTS entries, face records, and embeddings. Treat these files as sensitive and do not share them publicly.

## Experimental extension boundary

External APK/Dex plugin loading is hidden and experimental, and is **not** a supported end-user extension mechanism. Do not import plugin APKs from untrusted sources. If you find a path that enables, bypasses, or escalates the experimental loader, report it as a security issue.

## Best Practices for Users

- LocalAlbum performs media indexing and supported AI inference **on-device**. Network access is used only for model downloads and remote model-catalog requests initiated by the app.
- Protect JSON exports and imported model files as private data.
- The app requests media access to index user-selected local media and an optional notification permission to display long-running scan progress.
- Model files (ONNX/TFLite/PyTorch) are executed locally through the bundled runtimes; download models only from the documented project source.
