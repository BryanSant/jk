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

`RepoCredentialResolver.resolve(repo)` walks sources in precedence order and
returns a `RepoCredential` (`Anonymous | Basic | Bearer | <cloud>`):

1. **Forge bridge** — if the host is a known package-registry host
   (`maven.pkg.github.com` → GitHub, a GitLab `…/packages/maven` path →
   GitLab), borrow the token from `ForgeAuth.resolveSilently(kind, forgeHost)`.
   This is what makes package-registry access "just work" after `jk auth
   login`. *(Per-registry header conventions need verification — see below.)*
2. **Environment variables** — `JK_REPO_<NAME>_USERNAME` / `_PASSWORD` /
   `_TOKEN` (name upper-cased, non-alphanumerics → `_`). CI-friendly, never
   committed.
3. **Inline jk.toml** — `[repositories.<name>]` `username`/`password`/`token`,
   with `${ENV}` interpolation. Convenient; raw secrets discouraged.
4. **jk credential store** — `~/.jk/repo-credentials/<id>` (a `jk repo login`
   flow, mirroring forge `TokenStore`), `0600`.
5. **`~/.m2/settings.xml`** — `<servers>` matched by `<id>` == repo name. Lets
   teams already on Maven work with zero reconfiguration.
6. **Cloud-native chains** (object stores only) — AWS default chain (env,
   `~/.aws/credentials` + `AWS_PROFILE`, instance/container roles); GCS ADC;
   Azure default. Used when no explicit cloud keys were supplied above.

(Precedence is the proposed default; the forge bridge is first so logging in
once to GitHub immediately unlocks GitHub Packages. Open to reordering.)

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

**Phase 2 — HTTP auth end-to-end (next):**
- `RepoCredentialResolver` (sources 1–5 + forge bridge).
- `HttpTransport`; wire credentials into `MavenRepo` (resolve) and
  `MavenPublisher` (publish). Delivers Nexus/Artifactory/WebDAV + GitHub/GitLab
  package registries.
- `[repositories.<name>]` auth fields in `JkBuildParser`; `jk repo login`.

**Phase 3 — object storage:**
- `S3Transport` with hand-rolled SigV4; AWS default credential chain; endpoint
  override for MinIO; `gs://` via GCS XML API + HMAC.
- `AzureBlobTransport` (SharedKey / SAS).

**Phase 4 — OCI registries:**
- `OciRegistryAuth` (Docker token protocol, `config.json`, cloud helpers),
  wired into the `image` module.

## Verification flags (confirm before shipping each)

- **Package-registry headers**: GitHub Packages Maven wants HTTP **Basic**
  (username + PAT-as-password), not Bearer; GitLab accepts `Private-Token` /
  `Deploy-Token` / `Job-Token` headers or Basic. The bridge must emit the
  right shape per registry — verify against current docs (same caution as the
  forge device-flow endpoints).
- **GCS via S3 XML API**: requires HMAC keys and the `storage.googleapis.com`
  endpoint; confirm signing version (SigV4 vs the legacy V2).
- **Azure SharedKey**: canonicalized-header signing is fiddly; SAS tokens may
  be the simpler first cut.

## References
- Maven `settings.xml` `<servers>` schema.
- AWS Signature Version 4 signing process.
- GitHub Packages / GitLab Package Registry (Maven) auth docs.
- OCI Distribution Spec; Docker Registry token authentication.
