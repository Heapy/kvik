# Remote compilation cache

Pass `--remote-cache PATH` to `build`, `test` or `dev`. The JSON file selects a trusted cache provider. REAPI (the default) works with servers such as bazel-remote:

```json
{
  "schemaVersion": 1,
  "url": "https://cache.example.org",
  "instance": "kvik",
  "trustedWriters": true,
  "mode": "read",
  "tokenEnvironment": "KVIK_CACHE_TOKEN"
}
```

`mode` defaults to `read`. Trusted writers can select `read-write`. `instance` and `tokenEnvironment` are optional; tokens are read from the named environment variable and are excluded from action descriptions and logs. HTTPS is required except for loopback test servers. The schema is [remote-cache.json](../schemas/v1/remote-cache.json).

The eligible actions are typed JVM compilations without compiler plugins on macOS ARM64 and Linux x64. Native and compiler-plugin actions retain their local policies. Tests execute on every request. Runtime composition, tools and all declared compilation inputs remain part of each action key. Host platform is also part of the key: Linux and macOS compilation entries are separate.

Lookups check the local cache first. Missing, unavailable or invalid remote entries fall back to local compilation. Uploads follow successful scheduler completion and publish metadata after output blobs. All network workers belong to the invocation and are joined before exit.

Publication uploads output contents, output trees, and REAPI Action, Command and input-directory metadata. Input file contents (including dependency archives) are not uploaded: their paths and digests remain part of the action identity, while restoration needs only the outputs. This protocol supports remote caching; it does not provision inputs for remote execution.

`--offline` disables remote requests and credential lookup. Keep `--remote-cache PATH` to use the same action identities and reuse previously restored local entries. Endpoint, credentials and read/write mode do not affect compilation identity. Transfers are bounded to 16 MiB per blob and 128 MiB per operation; actions exceeding those limits still execute locally.

Foreground sessions forward only the configured credential to their preparation worker. Compiler workers and applications keep their own declared environments. Each requested operation rereads the transport configuration; a changed credential environment replaces the idle preparation worker. Configuration edits alone do not trigger a reload, so request `r` or `t` after changing this file.

## Gradle HTTP provider

Use `provider: "gradle-http"` for the [Gradle HTTP cache protocol](https://docs.gradle.org/current/userguide/build_cache.html#sec:build_cache_configure_remote). For the configured BuildFetch project:

```json
{
  "schemaVersion": 1,
  "provider": "gradle-http",
  "url": "https://cache.eu-central-a.buildfetch.com/b1CXAN/gradle/",
  "instance": "kvik",
  "trustedWriters": true,
  "mode": "read",
  "username": "token-auth",
  "tokenEnvironment": "BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN"
}
```

The token supplies the HTTP Basic password. The provider is disabled when that environment variable is absent or empty, and `--offline` never reads credentials. Select `read-write` explicitly for trusted CI writers; setting `CI` alone does not enable writes. URLs may have a base path but cannot contain credentials, query strings, fragments or traversal. Redirects are rejected to avoid forwarding credentials or changing a PUT into a GET.

The provider uses the same action identity, portability approvals, local CAS and scheduler as REAPI. Each GET/PUT transfers one deterministic gzip member containing a versioned Kvik archive with its full action key, output manifest, executable flags and file contents. HTTP addresses use the first 32 hex characters of SHA-256 over the `kvik-http-v2` namespace, `instance` and full action key. BuildFetch requires a 32-character key and gzip payload; full SHA-256 action and file identities are still verified inside the archive, so an address collision cannot authorize mismatched outputs. This transport uses its own payload format. Existing `kvik-http-v1` entries become cache misses; the action identity and local CAS format are unchanged. Input files and dependencies are not uploaded. The reader bounds compressed and expanded sizes, rejects malformed, truncated, concatenated or trailing gzip data, then validates all output paths, sizes and SHA-256 digests before publishing blobs locally; the ordinary output materializer then exposes results atomically. Symlinks and special files are unsupported.

Limits remain 16 MiB per output file and 128 MiB per transfer operation, with a 1 MiB manifest limit. Publication confirms a successful PUT with a GET of the same bytes; both transfers share that budget. A server returning a 2xx error page without retaining the entry cannot produce a successful publication result. A missing entry (404) executes locally. Invalid archives, failed authorization, timeouts and transport failures produce cache diagnostics and allow local execution. HTTP 413 skips publication while retaining the successful local result. Tests still execute freshly after compilation reuse. Transfers run in invocation-owned workers and support cancellation; there is no cache daemon.

The manually dispatched **BuildFetch HTTP cache** workflow uses the repository secret `BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN`. It runs a cold writer and compiler-free reader on distinct Linux x64 runners against the shared HTTPS endpoint. A namespace derived from the workflow run and attempt prevents a previous run from satisfying the cold-writer check. Only the tools image and writer baseline pass through GitHub artifacts; compilation outputs come from BuildFetch. The regular Linux workflow also exercises HTTP with a local authenticated fixture, including foreground sessions, read-only restoration, offline reuse and outage fallback.

## Checking reuse between machines

`tools/test_remote_cache_cross_machine.py` splits the existing JVM fixture into
independent writer and reader invocations against a provisioned REAPI or Gradle HTTP cache. Use
the `library`/`consumer` fixture created by `tools/test_build_cli.py`, including its
locked dependency blobs. Both hosts need the same source/configuration/lock inputs,
runtime composition and tool identities; installation paths may differ. Supply
each host's installation paths through its own `--tools` file. Start with an empty
remote namespace and keep the same endpoint and namespace for both phases.
Use two macOS ARM64 hosts or two Linux x64 hosts. On Linux, the harness requires
read access to `/sys/class/dmi/id/product_uuid` to distinguish hardware; container
IDs and hostnames are insufficient.

On machine A:

```sh
python3 tools/test_remote_cache_cross_machine.py writer \
  --runtime /path/to/kvik.kexe --fixture /path/to/fixture \
  --tools /path/to/tools.json --remote-cache /path/to/remote.json \
  --output /path/to/new-writer-directory
```

Transfer `baseline.json` from that output directory to machine B, then run:

```sh
python3 tools/test_remote_cache_cross_machine.py reader \
  --runtime /path/to/kvik.kexe --fixture /another/path/to/fixture \
  --tools /path/to/tools.json --remote-cache /path/to/remote.json \
  --baseline /path/to/baseline.json --output /path/to/new-reader-directory
```

The writer uses read/write mode; the reader uses read-only mode. Other endpoint
and authentication settings follow the supplied configuration. Credentials remain
in the named environment variable. The baseline binds the endpoint and namespace,
and a writer with failed lookups or publication cannot provide a passing baseline.
Both invocations create a new project and seed
only verified locked dependency blobs, never local action records or compilation
outputs. The writer must compile all three fixture compilations; the reader must
restore all three remotely with identical action keys and output manifests. Both
must execute tests freshly.

The baseline and reader result also retain `performance` for the provisioned test
invocation; the reader includes `writerPerformance` when its baseline provides it.
Measurements include wall time, host CPU/memory capacity and software version,
input capture/hashing, action outcomes and timings, remote requests/bytes/operation
time, measured adapter tool-verification time when available, and child CPU/peak RSS. They come from the public `run-stats` query
after validating the committed journal. Provisioning, fixture copying and post-run
validation are outside invocation wall time. Each phase is one sample on its own
host: operation times can overlap, child peak RSS is not total concurrent memory,
unavailable CPU/model or memory capacity is recorded as null, and a
cold-writer/remote-reader pair does not establish a general speedup.

The reader compares hashed hardware identifiers and rejects the writer's host by
default. For local harness development, `--allow-same-host` permits a same-host
reader and explicitly records that the cross-machine gate did not pass. A local
validation cannot establish cross-machine portability. A real cross-machine pass
requires a shared HTTPS endpoint; loopback servers are only for local validation.

## Compare four build phases

Prepare the standard two-module JVM fixture with provisioned tools, then run the
comparison using the same runtime:

```sh
python3 tools/test_build_cli.py --java-home /absolute/jdk \
  --konan-home /absolute/kotlin-native --runtime /absolute/kvik.kexe --prepare-only
python3 tools/test_build_performance_cli.py --runtime /absolute/kvik.kexe \
  --fixture /absolute/printed/fixture --host-name 'my build host'
```

The harness measures a cold build, local compilation reuse, a separate checkout
with an empty action cache and unavailable compilers, and a consumer source edit.
All four execute the same test freshly. The first three must produce identical
compilation manifests; the edit must change the consumer output while preserving
the library's output and cache key. A strict HTTP fixture records actual requests,
including the absence of network activity on local hits.

One JSON result under `.kvik/tests/build-performance-cli/` retains host and tool
identities, invocation wall time, input hashing, tool-verification measurements,
action outcomes, transfer bytes and test results. Setup, dependency acquisition,
edits and post-run queries are excluded from invocation time. This is one sample
per phase on a same-host loopback service, without performance thresholds or a
cross-machine speed claim. Linux host CI runs the same comparison and exports
its summary and phase logs.

## GitHub Actions cross-machine check

The **Cross-machine compilation cache** workflow runs on pull requests, pushes to
`main`, and manual dispatches. Its writer and reader use separate `ubuntu-24.04`
runners. No external cache URL or credentials are needed: GitHub
[workflow artifacts](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts)
transport a snapshot of the cache server's storage between the jobs.

The writer builds the tool image once and transfers that image to the reader.
The image contains tools, not the repository or consumer build outputs. Each job
independently prepares its native Kvik runtime and resolves the fixture;
preparation does not compile the consumer project. The projects live under
different absolute paths, and neither job restores a local Kvik action cache.

The writer starts an invocation-owned bazel-remote server, compiles all three
fixture actions, runs the test, then stops the server before archiving its storage.
The baseline binds the archive's digest. The reader verifies that digest, restores
the server snapshot, and starts a new local server. Its fresh project contains only
locked dependencies: all three compilations must be fetched through REAPI, and
the test must execute freshly. Reader compiler installation paths are deliberately
unavailable, so a cache miss cannot silently compile locally. The backend is
stopped on success or failure.

The gate compares machine UUID hashes, input/tool/runtime identities and output
manifests. Same-machine execution, incompatible environments, archive corruption,
local hits, compilation fallback, or missing fresh test execution fail the job.
The reader result records `scope: cross-machine-cache-snapshot` and
`crossMachineSnapshotPassed: true` only on success. It retains
`crossMachineGatePassed: false`: this workflow verifies JVM cache portability
across Linux x64 runners, while the shared HTTPS service gate above remains a
separate integration test. Native and cross-architecture reuse are outside its scope.
