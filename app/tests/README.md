# Launcher regression tests

These JUnit 4 tests use Robolectric 4.14.1 on Java 17 / Android API 34. `VisualTest` additionally uses Robolectric's native graphics runtime (`nativeruntime-dist-compat` 1.0.16). No environment identifiers or compatibility checks are modified.

Compile the app sources against Android API 34 plus the generated app R.java, then compile this directory on the same classpath as JUnit 4 and Robolectric. Run these classes:

- `CmdTest`: template data, warnings, Chat filtering and edited copy action.
- `FeedTest`: real-source feed pagination, world/skin filtering and search.
- `SeedTest`, `SeedPrefetchTest`: real-source seed parsing and seed card text.
- `UpgradeTest`: deterministic download, cache, source-worker, retry, history and service checks.
- `VisualTest`: native pixel renders using bundled assets and fixture manager states.

System properties:

- `launcher.root`: absolute repository root.
- `launcher.assetsApk`: absolute path to the unsigned resources APK from the app build (contains the current bundled icons/font).
- `launcher.renderDir`: output directory for native PNGs.

Robolectric dependencies can be fetched normally, or preloaded using its documented offline dependency directory. Tests using real public websites require internet and can fail when those sites are unavailable. The deterministic fixtures use reserved `.test` domains and an injected connection factory; they do not send traffic to those domains.

The renders and framework tests do not measure touch latency, scrolling frame rate, OEM battery behavior or Minecraft imports on a physical phone. Process death is modeled by restoring persisted history, not by claiming force-stop survival. The download manager visual fixture creates a real app-managed sparse file so its stored-byte summary matches its completed row.
