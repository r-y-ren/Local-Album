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
- Plugin sandbox escape
- Authentication/authorization bypass

## Best Practices for Users

- LocalAlbum processes all data **locally** on-device. No data is transmitted to remote servers.
- Review plugin APKs before loading them. Only install plugins from trusted sources.
- The app requests only the minimum necessary Android permissions (storage access for media indexing).
- Model files (ONNX/TFLite) are executed entirely on-device via ONNX Runtime / TensorFlow Lite / PyTorch Mobile.
