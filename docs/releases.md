# Release layout

The layout `install.sh` and the client's engine-jar self-fetch (`EngineJarFetcher`) are built
against. The release pipeline (v1.0 roadmap) publishes to it; nothing else about it is live yet.

```
https://jkbuild.dev/releases/
├── latest/VERSION                 # tiny text file, e.g. "0.10.0" — the ONLY mutable thing
└── 0.10.0/                        # immutable once published
    ├── jk-linux-x86_64.xz
    ├── jk-linux-x86_64.zip        # fallback for hosts without xz
    ├── jk-macos-aarch64.xz
    ├── jk-macos-aarch64.zip
    ├── jk-windows-x86_64.exe.zip  # future PowerShell install script's artifact
    ├── jk-engine-0.10.0.jar       # platform-neutral; same filename it keeps in ~/.jk/lib/
    ├── SHA256SUMS                 # coreutils format, covers every artifact above
    └── SHA256SUMS.sig             # base64 Ed25519 signature over the exact SHA256SUMS bytes
```

Rules the layout encodes:

- **Arch in the artifact name, not the path** — one flat directory per version, the dominant
  convention (Go, Node, uv, deno, bun) and the shape GitHub Releases forces, so the same artifact
  set can be dual-published there unchanged. One `SHA256SUMS` covers the whole release, and a
  downloaded file is self-describing.
- **Naming vocabulary is `HostPlatform`'s** — `linux|macos|windows` × `x86_64|aarch64`. Exactly
  two archive formats exist: `.xz` (preferred on Linux/macOS) and `.zip` (the fallback when a
  host lacks `xz`, and the only format on Windows — PowerShell's `Expand-Archive` handles
  nothing else natively). No `.gz`, no uncompressed downloads.
- **Version directories are immutable; `latest/VERSION` is a pointer.** Consumers resolve the
  version once, then fetch everything from the frozen directory — a release published mid-install
  can never hand out a binary and an engine jar that disagree. The pipeline publishes the version
  directory completely, then flips `latest/VERSION` last.
- **The engine jar sits at the version root, no arch dir** — it's a plain JVM app
  (docs/engine.md "Two artifacts"). The released client downloads its own jar on first engine
  spawn (`releases/<its baked-in version>/jk-engine-<version>.jar`, verified against
  `SHA256SUMS`); there is deliberately no `jk engine fetch` verb, and `install.sh` never fetches
  the jar itself.

Consumers, for cross-checking any layout change: `install.sh` (target detection, `latest/VERSION`
resolution, binary URL), `EngineJarFetcher` in `:cli` (jar + checksum URLs; `JK_RELEASES_URL`
overrides the root — mirrors and tests), and the future Windows install script.

## Signing (engine-versioning-plan §4)

The pipeline signs `SHA256SUMS` with the Ed25519 release key; `SHA256SUMS.sig` is the
base64 of the raw signature over the file's exact bytes — verifiable with the JDK's
`Signature("Ed25519")` or `openssl pkeyutl -verify -pubin`. Verifiers check
signature-then-hash before materializing anything (`ReleaseVerifier`); a client with no
trusted keys (dev builds, pre-signing releases) proceeds on checksums alone. The current
public key ships baked into the client (`ReleaseVerifier.BUILT_IN_KEY`); a release may
announce a successor key, and `[release] trusted-keys` in config.toml overrides/extends
the set for enterprise mirrors and air-gapped hosts. Once a version is pinned in a
`jk.lock` (version + sha256), every later fetch verifies against the pin — the signature
gates first acquisition only.
