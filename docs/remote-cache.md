# Remote compilation cache

Pass `--remote-cache PATH` to `build`, `test` or `dev`. The JSON file configures a trusted REAPI cache, such as bazel-remote:

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

## Checking reuse between machines

`tools/test_remote_cache_cross_machine.py` splits the existing JVM fixture into
independent writer and reader invocations against a provisioned REAPI cache. Use
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

The reader compares hashed hardware identifiers and rejects the writer's host by
default. For local harness development, `--allow-same-host` permits a same-host
reader and explicitly records that the cross-machine gate did not pass. A local
validation cannot establish cross-machine portability. A real cross-machine pass
requires a shared HTTPS endpoint; loopback servers are only for local validation.

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
