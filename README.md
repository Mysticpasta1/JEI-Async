# JEI-Optimized

A performance-optimized fork of [JustEnoughItems (JEI)](https://github.com/mezz/JustEnoughItems) for Minecraft 1.21.1 (NeoForge).

Based on the async loading work from [JEI-Async](https://github.com/Mysticpasta1/JEI-Async).

## Features

### Background Async Loading
JEI loads entirely on a background thread instead of blocking the main thread during world join. You can start playing immediately while JEI initializes in the background.

- **Auto-fallback**: Plugins incompatible with async loading are automatically detected and re-executed on the main thread, ensuring compatibility with all mods.
- **Per-phase error isolation**: Incompatible plugins are tracked per registration phase, so a plugin that fails during subtype registration can still succeed in recipe registration.
- **Batched main-thread execution**: When multiple plugins need main-thread fallback, they are batched together to minimize synchronization overhead.

### Loading Progress Overlay
A progress overlay is displayed during background loading, showing the current phase (e.g., "Loading ingredients...", "Loading categories & recipes...", "Building runtime...").

### Deferred Recipe Index Building
Recipe index construction (mapping ingredients to recipes for focus-based lookups) is deferred to a background thread and runs in parallel with GUI construction, significantly reducing visible loading time.

### Non-blocking Recipe Queries
When the recipe index is still building, recipe lookups return empty results instead of freezing the render thread. An action bar message (`[JEI] Recipe index is still building...`) notifies the player. Once the index is complete, recipes display normally.

### GZIP Search String Cache
The search string cache file is compressed with GZIP, reducing disk usage from ~7.7MB to ~1.5MB in large modpacks.

### Ingredient List Cache Pre-building
The sorted ingredient list is pre-built on the background thread during filter construction, reducing the freeze when first opening inventory.

### Load Complete Sound
An experience orb pickup sound plays when JEI finishes loading.

## Recommended JVM Flags

For large modpacks, JEI's search index building is heavily affected by GC pressure. **ZGC** dramatically reduces loading time (e.g., G1GC: 39s → ZGC: 11s in a 300+ mod pack).

```
-XX:+UseZGC -XX:+ZGenerational
```

**Important:** Remove any existing G1GC arguments (`-XX:+UseG1GC` and related options) when switching to ZGC.

## Installation

1. Download the latest release JAR
2. Place it in your `mods/` folder (replace any existing JEI JAR)
3. Do not run both this mod and standard JEI simultaneously

## Building from Source

```bash
./gradlew :NeoForge:build -x test
```

The output JAR will be in `NeoForge/build/libs/`.

## Credits

- [mezz/JustEnoughItems](https://github.com/mezz/JustEnoughItems) — Original JEI
- [Mysticpasta1/JEI-Async](https://github.com/Mysticpasta1/JEI-Async) — Original async loading implementation for 1.20.1
