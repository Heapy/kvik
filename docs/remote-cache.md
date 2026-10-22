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

The initial eligible actions are typed JVM compilations without compiler plugins on macOS ARM64. Native and compiler-plugin actions retain their local policies. Tests execute on every request. Runtime composition, tools and all declared compilation inputs remain part of each action key.

Lookups check the local cache first. Missing, unavailable or invalid remote entries fall back to local compilation. Uploads follow successful scheduler completion and publish metadata after output blobs. All network workers belong to the invocation and are joined before exit.

`--offline` disables remote requests and credential lookup. Keep `--remote-cache PATH` to use the same action identities and reuse previously restored local entries. Endpoint, credentials and read/write mode do not affect compilation identity. Transfers are bounded to 16 MiB per blob and 128 MiB per operation; actions exceeding those limits still execute locally.

Foreground sessions forward only the configured credential to their preparation worker. Compiler workers and applications keep their own declared environments. Each requested operation rereads the transport configuration; a changed credential environment replaces the idle preparation worker. Configuration edits alone do not trigger a reload, so request `r` or `t` after changing this file.
