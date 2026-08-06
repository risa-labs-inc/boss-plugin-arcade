# CLAUDE.md

## Project Overview

**BOSS Arcade** (`ai.rever.boss.plugin.dynamic.arcade`) is a dynamic tab plugin
for the BOSS desktop application: quick competitive games with team
leaderboards. First game: 2048 (a native Compose port of an HTML original).

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.arcade`
- **Main Class**: `ai.rever.boss.plugin.dynamic.arcade.ArcadeDynamicPlugin`
- **Type**: tab

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build             # Full build
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user tests manually.
- After building, copy the JAR to `~/.boss/plugins/` (or `~/.boss_debug/plugins/`
  for a dev-mode host).
- All Kotlin files end with a newline.

## Architecture

```
src/main/kotlin/ai/rever/boss/plugin/dynamic/arcade/
├── ArcadeDynamicPlugin.kt    # entry point; builds ArcadeServices, registers tab + MCP
├── ArcadeTab.kt              # TabTypeInfo/TabInfo/TabComponentWithUI + screen nav
├── ArcadeTheme.kt            # shared pastel palette (ported from the HTML game)
├── ArcadeWidgets.kt          # buttons, plainClickable
├── ArcadeHomeScreen.kt       # game picker
├── LeaderboardService.kt     # Supabase RPC client (arcade_* functions)
├── LeaderboardOverlay.kt     # top-10 overlay
├── game2048/
│   ├── Game2048Logic.kt      # pure rules (port of the HTML logic block)
│   ├── Game2048ViewModel.kt  # state machine; 105ms slide → settle → veil timing
│   ├── Game2048Board.kt      # board + animated tiles
│   ├── Game2048Chrome.kt     # header, score chips, veil
│   └── Game2048Screen.kt     # assembly + keyboard input
└── mirrordash/
    ├── MirrorDashEngine.kt    # real-time simulation (port of the HTML update loop)
    ├── MirrorDashViewModel.kt # phase machine + best/leaderboard bookkeeping
    ├── MirrorDashRenderer.kt  # Compose Canvas frame rendering
    ├── MirrorDashScreen.kt    # withFrameNanos loop + input
    └── MirrorDashOverlays.kt  # HUD, start/pause/over cards
```

Key patterns:
- Providers from `PluginContext` are nullable — the game must always work with
  auth/supabase/storage absent (leaderboard degrades to a message).
- Tile identity: `TileData.id` is stable across moves so Compose animates
  position; merges double values at settle time, not slide time.
- Score submission goes through `pluginScope` (survives tab close); game-phase
  coroutines use the tab component's scope (cancelled on destroy).
- Backend: `supabase/arcade_schema.sql` (RLS insert-own/read-authenticated,
  `arcade_submit_score` / `arcade_personal_best` / `arcade_leaderboard` RPCs).

## Version Management

`build.gradle.kts` is the single source of truth; `processResources` syncs it
into `plugin.json` at build time (guarded with `inputs.property`). Never
hand-edit the version in `plugin.json`.

## CI/CD

Pushes to `main` trigger `.github/workflows/build.yml`, which delegates to the
shared release workflow in `risa-labs-inc/BossConsole-Releases` (version bump →
GitHub release → Plugin Store publish).
