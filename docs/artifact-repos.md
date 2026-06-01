# Artifact Repository Backends & Credentials

**Status:** Proposed — phased. This doc defines a unified credential +
transport layer for every artifact backend jk talks to. **Phase 1
foundation** (credential model, `~/.m2/settings.xml` parsing, `Http.put`) is
built and tested; the resolver wiring, object-store transports, and OCI are
staged below.

**Scope:** How jk authenticates to and transfers artifacts from/to artifact
repositories — for both Maven-style artifacts and (where supported) OCI
images. Distinct from [forge-auth.md](./forge-auth.md), which covers git
**repo** access + the forge **API**; this doc covers the **package/artifact**
plane. The two meet at package registries (below).

## Two planes, one credential story

A provider like GitHub serves jk in two ways:

1. **Git + forge API** — clone/fetch, and the REST API (issues, releases).
   Handled by `dev.jkbuild.forge` (OAuth device flow, tokens).
2. **Package/artifact registry** — GitHub Packages (Maven), GitLab Package
   Registry, etc. These are *artifact repositories* that happen to
   authenticate with the **same forge token**.

Then there are pure artifact repositories with no git relationship at all:
Sonatype Nexus, JFrog Artifactory, a plain HTTP/WebDAV server, and
object-storage buckets (S3, MinIO, GCS, Azure Blob).

The goal: **one consistent way to get credentials for any of them**, reusing
the forge token automatically when the backend is a forge's package registry.

## Backend taxonomy

| Backend | URL scheme | Transport | Auth |
|---|---|---|---|
| Maven Central / public | `https://` | HTTP GET | none |
| Nexus / Artifactory / private HTTP / WebDAV | `https://`, `http://` | HTTP GET/PUT | Basic or Bearer |
| GitHub Packages (Maven) | `https://maven.pkg.github.com/…` | HTTP GET/PUT | **forge token** (bridge) |
| GitLab Package Registry | `https://<host>/api/v4/projects/…/packages/maven` | HTTP GET/PUT | **forge token** (bridge) |
| AWS S3 / MinIO | `s3://bucket/prefix` | HTTPS + SigV4 | AWS keys / chain |
| Google Cloud Storage | `gs://bucket/prefix` | HTTPS (S3-compatible XML API) + HMAC | GCS HMAC keys |
| Azure Blob Storage | `azblob://account/container` | HTTPS + SharedKey/SAS | account key / SAS |
| Local | `file://` | filesystem | n/a |
| OCI image registry | `oci://` / registry refs | Docker registry protocol | Bearer token dance |

**Key simplification:** Nexus, Artifactory, generic HTTP, and WebDAV are
*not* distinct transports — they're all "authenticated HTTP Maven repo". They
differ only in credential style (Basic vs Bearer). Object stores are the real
new transports, and they share a signing-based shape.

## Repository model

`RepositorySpec` (today `(name, url)`) gains optional auth metadata. The TOML
keeps secrets *out* of committed files — inline values support `${ENV}`
interpolation, and the preferred forms reference env/store/settings rather
than literals:

```toml
[repositories.central]
url = "https://repo.maven.apache.org/maven2/"     # public, no auth

[repositories.corp-nexus]
url = "https://nexus.corp.example/repository/maven-releases/"
# credentials resolved by id "corp-nexus" via the chain below; or:
auth = "basic"                                     # hint; default inferred

[repositories.ghp]
url = "https://maven.pkg.github.com/jkbuild/jk"
# no creds here — auto-bridged to the GitHub forge token

[repositories.s3cache]
url = "s3://my-bucket/maven"
region = "us-east-1"                               # S3 / object-store knobs
# AWS creds from the default chain, or [repositories.s3cache] keys
```

The scheme selects the transport; the repo `name`/`id` is the key for
credential lookup (matching Maven's `settings.xml` `<server><id>` convention).

## Credential resolution chain

`RepoCredentialResolver.resolve(repoId, url, inline)` walks sources in
precedence order and returns a `RepoCredential` (`Anonymous | Basic | Bearer
| <cloud>`). **Explicit configuration wins; the forge bridge is the
convenience fallback** so it can never shadow a credential the user set on
purpose:

1. **Inline jk.toml** — `[repositories.<name>]` `username`/`password`/`token`,
   with `${ENV}` interpolation. Convenient; raw secrets discouraged.
2. **Environment variables** — `JK_REPO_<NAME>_TOKEN`, or
   `JK_REPO_<NAME>_USERNAME` + `_PASSWORD` (name upper-cased, non-alphanumerics
   → `_`). CI-friendly, never committed.
3. **jk credential store** — `~/.jk/repo-credentials/<id>` (the `jk repo login`
   flow, mirroring forge `TokenStore`), `0600`.
4. **`~/.m2/settings.xml`** — `<servers>` matched by `<id>` == repo name. Lets
   teams already on Maven work with zero reconfiguration.
5. **Forge bridge** — for a known package-registry host
   (`maven.pkg.github.com` → GitHub; a GitLab host → GitLab), borrow the token
   a prior `jk auth login` stored, via `ForgeAuth.resolveSilently`. Makes
   package-registry access "just work" after logging in once. *(Per-registry
   header conventions need verification — see below.)*
6. **Cloud-native chains** (object stores only) — AWS default chain (env,
   `~/.aws/credentials` + `AWS_PROFILE`, instance/container roles); GCS ADC;
   Azure default. Used when no explicit cloud keys were supplied above.

## Transport SPI

```java
interface RepoTransport {
    boolean handles(URI repoUrl);                 // by scheme
    Optional<byte[]> get(URI artifactUrl, RepoCredential cred);
    PutResult put(URI artifactUrl, byte[] body, RepoCredential cred);
}
```

- **`HttpTransport`** — covers Maven Central, Nexus, Artifactory, WebDAV, and
  the package registries. Wraps `Http` (now with `put`); renders
  `RepoCredential` to an `Authorization` header (`Basic`/`Bearer`). WebDAV
  needs `MKCOL` for intermediate collections on some servers — handled lazily
  on `put`.
- **`S3Transport`** — `s3://` and S3-compatible (`gs://` via the GCS XML API,
  MinIO via an endpoint override). Hand-rolled **AWS SigV4** over `Http` (no
  AWS SDK — keeps the GraalVM native image small). Endpoint/region are knobs.
- **`AzureBlobTransport`** — `azblob://`. SharedKey signing (different from
  SigV4) or a SAS token.
- **`FileTransport`** — `file://`, for local/offline mirrors.

`MavenRepo` (resolve) and `MavenPublisher` (publish) both route through a
transport selected by scheme instead of assuming HTTP.

## OCI image registries

jk's `image` module (Jib-core) pushes OCI images. Registry auth is its own
protocol (the Docker bearer-token dance, `~/.docker/config.json`, cloud
credential helpers like ECR/GCR/ACR). It reuses this doc's **credential
sources** where they overlap (env, store, cloud chains) but needs a distinct
`OciRegistryAuth` that speaks the token-exchange protocol. Staged last.

## Security

- Committed `jk.toml` should never hold raw secrets — prefer the forge
  bridge, env vars, the jk store, or `settings.xml`; inline supports `${ENV}`
  interpolation so even the inline form can avoid literals.
- The jk credential store is `0600`/`0700` like forge `TokenStore`.
- Tokens/passwords are never logged; `--verbose` redacts them.

## Phased plan

**Phase 1 — foundation (built, tested):**
- `RepoCredential` (`:core`) — sealed `Anonymous | Basic | Bearer`.
- `MavenSettings` (`:core`) — parse `~/.m2/settings.xml` `<servers>`.
- `Http.put(URI, byte[], headers)` (`:io`) — shared retry/offline policy.

**Phase 2 — HTTP auth end-to-end (complete):**
- ✅ `RepoCredentialResolver` (inline / env / store / settings.xml + forge
  bridge), `RepoCredentialStore` (`~/.jk/repo-credentials/`), `AuthHeaders`
  (credential → `Authorization` header).
- ✅ `jk repo login` / `jk repo logout` (secret read from stdin; `--username`
  → Basic, else Bearer).
- ✅ Resolve auth: `MavenRepo` takes an optional `RepoCredential` and sends the
  header on every fetch; wired in `RepoGroupBuilder` (build/resolve) and
  `CacheSync` (`jk sync`). Public repos → `ANONYMOUS`, so Maven Central is
  unaffected.
- ✅ Publish auth: `MavenPublisher` accepts a `RepoCredential` (Basic/Bearer);
  `jk publish` resolves it (explicit `--user`/`--password` and `PUBLISH_*` env
  still win, then the chain by matched repo name / host bridge).
- ✅ Inline `[repositories.<name>]` `token` / `username` / `password` parsed by
  `JkBuildParser`, with `${ENV}` interpolation (unset var → parse error).

**Phase 3 — object storage (S3 + GCS + file:// done; Azure next):**
- ✅ `S3Transport` (`s3://`, path-style) with hand-rolled `SigV4Signer`
  (verified against the AWS `aws-sig-v4-test-suite` `get-vanilla` vector),
  `AwsCredentialChain` (env → `~/.aws/credentials`+`config` profiles; region
  override), `AWS_ENDPOINT_URL` override for MinIO/S3-compatible, and unsigned
  fallback for public buckets. Registered in `RepoTransports.forUrl`.
- ✅ `gs://` via the GCS S3-compatible XML API — the same `S3Transport`
  pointed at `storage.googleapis.com` with HMAC keys (AWS env chain), region
  `auto`. ⚠️ Confirm the GCS V4 region/service against a real bucket before
  relying on it.
- ✅ `file://` via `FileTransport` — local directory tree as a Maven repo
  (offline mirrors, tests, air-gapped); reads/writes the filesystem directly.
- ⬜ `AzureBlobTransport` (SharedKey / SAS — genuinely different signing).
- ⬜ Per-repo region/endpoint in `[repositories.<name>]` (today via the AWS
  env); virtual-host addressing (path-style only for now).

**Phase 4 — OCI registries:**
- `OciRegistryAuth` (Docker token protocol, `config.json`, cloud helpers),
  wired into the `image` module.

## Package-registry auth shapes (forge bridge)

The forge bridge now emits the shape each registry expects:

- **GitHub Packages** (`maven.pkg.github.com`) — HTTP **Basic** with the
  account **login** as username and the token as password. The login is
  resolved via the GitHub API (`GET /user`, `ForgeIdentity`), cached, and
  offline-safe; if it can't be resolved we fall back to Bearer (no worse than
  before). This is the common private-registry case and now works after a
  single `jk auth login github`.
- **GitLab Package Registry** — **Bearer**. The device-flow login yields an
  OAuth access token, which GitLab's package API accepts. (A *personal* access
  token used via env/store/`settings.xml` may instead want the `PRIVATE-TOKEN`
  header — add a `RepoCredential.Header` variant if that case comes up.)
- **Gitea/Forgejo** — Bearer; still worth a smoke test against a real instance.

## Verification flags (confirm before shipping each)
- **GCS via S3 XML API**: requires HMAC keys and the `storage.googleapis.com`
  endpoint; confirm signing version (SigV4 vs the legacy V2).
- **Azure SharedKey**: canonicalized-header signing is fiddly; SAS tokens may
  be the simpler first cut.

## References
- Maven `settings.xml` `<servers>` schema.
- AWS Signature Version 4 signing process.
- GitHub Packages / GitLab Package Registry (Maven) auth docs.
- OCI Distribution Spec; Docker Registry token authentication.
