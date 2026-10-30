# AGENTS.md

CloudStream extension for `animeon.cc`. One module: `animeon-provider`; plugin entry point registers `AnimeOnProvider`.

## Build and release

- Do not run Gradle locally in this Termux workspace: there is no JDK/Android SDK. Builds run on GitHub Actions.
- Push to `master` triggers `.github/workflows/build.yml`. Markdown-only commits are ignored by `paths-ignore: '*.md'`.
- CI runs `./gradlew make makePluginsJson`, then `./gradlew ensureJarCompatibility`, and force-pushes `*.cs3`, `*.jar`, and `plugins.json` to the `builds` branch.
- Bump `version` in `animeon-provider/build.gradle.kts` for every user-visible change; CloudStream updates by this version.
- Watch builds with `gh run list` and `gh run watch <id> --exit-status`; inspect failures with `gh run view <id> --log-failed`.
- JitPack `-SNAPSHOT` dependencies can fail transiently (404/401). Retry the job with `gh run rerun <id> --failed`; do not change dependency versions just to work around an outage.
- After a green build, verify `plugins.json` advertises the new version and its hashes match the downloaded `.cs3`/`.jar`. The mutable `builds` CDN can briefly serve a stale artifact.

## Layout

- `settings.gradle.kts` auto-includes every direct subdirectory containing `build.gradle.kts`; no manual `include()` is needed for a new plugin module.
- Root `build.gradle.kts` holds shared Android/Kotlin config. Keep JVM target at 11; 1.8 breaks on inline bytecode from the CloudStream library.
- `repo.json` is the user-facing repository URL and points at `builds/plugins.json`. Never publish build artifacts on `master`.
- `animeon-provider/src/main/kotlin/recloudstream/AnimeOnPlugin.kt`: `@CloudstreamPlugin` + `registerMainAPI(AnimeOnProvider())`.
- `animeon-provider/src/main/kotlin/recloudstream/AnimeOnProvider.kt`: home, search, title loading, and link extraction.
- The `builds` branch is CI-owned. Do not edit it or push artifacts there manually.

## Provider gotchas

- `newEpisode(url)` calls `fixUrl(url)` by default. For non-URL `Episode.data`, use `newEpisode(url = data, fix = false)`. Otherwise CloudStream prefixes it with `https://animeon.cc/` and `loadLinks` cannot decode it. This exact bug broke v6 playback.
- Episode data uses `animeon-v1\n<voiceover>\t<url>...`. Keep the decoder compatible with v4/v5 JSON and the old plain-URL format; it also strips the legacy `https://animeon.cc/` prefix.
- Search uses `/api/search?q=`. Title episodes live in escaped Next.js Flight JSON (`\"key\":\"value\"`), not ordinary JSON. Keep the `ANIME_MARKER`-based `load` parser; Jsoup alone will not find episodes.
- `loadLinks` tries Kodik directly (`POST /ftor`, Caesar + Base64 decode) and falls back to `loadExtractor` for other hosts. Return `true` only after a link was emitted.
- The home row key is `popular_v6`; bump it when home parsing or cached home behavior changes.
- `animeon.cc` is often Cloudflare-blocked from CLI IPs. Verify with the app or saved HTML, not only with `curl`.

## Verification

- There are no unit tests or lint tasks. A green CI build is the only automated check.
- Minimum manual check after installing: home row renders, search returns titles, a title opens with episodes, and Play emits at least one link.
- A docs-only change will not trigger CI, so do not wait for a workflow run after committing only Markdown.
