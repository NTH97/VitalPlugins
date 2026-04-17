# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VitalPlugins is a standalone multi-module Gradle project that builds individual RuneLite-style plugins against VitalShell's RuneLite fork and the VitalAPI JNI bridge. Each plugin lives in its own subproject and is built into its own JAR.

Current plugins: `walker`, `autologin`. Both were copied (with package rename) from `VitalShell/runelite-client/src/main/java/net/runelite/client/plugins/` — the originals remain in place in VitalShell.

## Build

### Prerequisites

- JDK 11 (Adoptium Temurin recommended)
- Gradle 8.8+ (wrapper included)
- `runelite-api` and `runelite-client` published to Maven Local from VitalShell:
  ```bash
  cd ../VitalShell
  ./gradlew publishRuneliteApiPublicationToMavenLocal
  ./gradlew :client:publishRunelite-clientPublicationToMavenLocal
  ```
  (The runelite-client artifact is published as `net.vital:client:1.12.20-SNAPSHOT`.)
- `vital-api` — either:
  - Published to Maven Local: `cd ../VitalAPI && ./gradlew publishToMavenLocal`, **or**
  - Resolvable from GitHub Releases with `gpr.token` in `gradle.properties` or `GITHUB_TOKEN` env var (Ivy fallback)

### Commands

```bash
./gradlew build                          # Build all plugin JARs
./gradlew :walker:build                  # Build only walker
./gradlew :autologin:build               # Build only autologin
```

Outputs:
- `walker/build/libs/walker-0.1.0.jar`
- `autologin/build/libs/autologin-0.1.0.jar`

## Architecture

```
VitalPlugins/ (root)
├── settings.gradle.kts     # Subproject registry + dependencyResolutionManagement (repos, version catalog)
├── build.gradle.kts        # Shared subprojects { } config: Java 11, compileOnly deps, manifest provider/license
├── gradle/
│   └── libs.versions.toml  # Version catalog
├── walker/
│   ├── walker.gradle.kts   # Per-plugin: version, manifest attrs
│   └── src/main/java/net/vital/plugins/walker/
└── autologin/
    ├── autologin.gradle.kts
    └── src/main/java/net/vital/plugins/autologin/
```

Package root is `net.vital.plugins.<plugin>`. Group is `net.vital.plugins`.

## Dependencies (all `compileOnly`)

| Artifact | Source |
|----------|--------|
| `net.vital:runelite-api:1.12.20-SNAPSHOT` | Maven Local (VitalShell) or GitHub Releases (Ivy, token-auth) |
| `net.vital:client:1.12.20-SNAPSHOT` | Maven Local (VitalShell) or GitHub Releases (Ivy, token-auth) |
| `net.vital:vital-api:1.0.0` | Maven Local (VitalAPI) or GitHub Releases (Ivy, token-auth) |
| `com.google.inject:guice:4.1.0` (`no_aop` classifier) | Maven Central |
| `javax.annotation:javax.annotation-api:1.3.2` | Maven Central |
| `org.projectlombok:lombok:1.18.30` | Maven Central (also `annotationProcessor`) |
| `org.jetbrains:annotations:23.0.0` | Maven Central |

## Repositories (configured in root `build.gradle.kts` under `subprojects { repositories { ... } }`)

- `mavenLocal()` — scoped to `net.vital`
- `https://repo.runelite.net` — scoped to `net.runelite` (upstream RuneLite libs pulled transitively by `net.vital:client`: `rlawt`, `flatlaf`, `discord`, etc.)
- `mavenCentral()` — excludes `net.vital`, `net.runelite`
- GitHub Packages `https://maven.pkg.github.com/Vitalflea/VitalAPI` — scoped to `net.vital:vital-api`. Auth: `gpr.user`/`gpr.token` gradle properties, or `GITHUB_ACTOR`/`GITHUB_TOKEN` env vars. PAT needs `read:packages`.
- GitHub Packages `https://maven.pkg.github.com/Vitalflea/VitalShell` — scoped to `net.vital:runelite-api` and `net.vital:client`. Same auth.

## Adding a New Plugin

1. Create directory `<plugin-name>/src/main/java/net/vital/plugins/<plugin-name>/`
2. Add `<plugin-name>/<plugin-name>.gradle.kts` (copy `walker.gradle.kts` and adjust `PluginName` / `PluginDescription` / `version`)
3. Add the subproject to `settings.gradle.kts` `include(...)` list
4. Drop plugin source into the package directory

## Code Style

Matches VitalShell: **tabs**, Allman braces (opening brace on next line), always use braces on control flow, UTF-8.

## Workflow

- Always commit after every change (push only when explicitly requested)
