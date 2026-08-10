# CLAUDE.md

## Project Overview

**BOSS Arcade** (`ai.rever.boss.plugin.dynamic.arcade`) is a dynamic tab plugin
for the BOSS desktop application: quick competitive games with team
leaderboards, implemented as native Compose ports of HTML originals.

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
├── mirrordash/
│   ├── MirrorDashEngine.kt    # real-time simulation (port of the HTML update loop)
│   ├── MirrorDashViewModel.kt # phase machine + best/leaderboard bookkeeping
│   ├── MirrorDashRenderer.kt  # Compose Canvas frame rendering
│   ├── MirrorDashScreen.kt    # withFrameNanos loop + input
│   └── MirrorDashOverlays.kt  # HUD, start/pause/over cards
├── skystack/
│   ├── SkyStackEngine.kt      # alternating-axis block simulation
│   ├── SkyStackViewModel.kt   # phase machine + best/leaderboard bookkeeping
│   ├── SkyStackRenderer.kt    # isometric blocks, sky, stars, offcuts, rings
│   ├── SkyStackScreen.kt      # frame loop + focus-safe keyboard/pointer input
│   ├── SkyStackOverlays.kt    # HUD, start/pause/over/whole-tower controls
│   ├── SkyStackSoundPlayer.kt # native synthesized HTML-equivalent tones
│   └── SkyStackTowerExport.kt # shareable full-tower SVG/PNG export
├── typingsprint/
│   ├── TypingPassages.kt        # passage pool
│   ├── TypingSprintViewModel.kt # 60s clock, WPM x accuracy scoring, submits
│   └── TypingSprintScreen.kt    # hidden-TextField input, per-char feedback
└── wordle/
    ├── WordleWords.kt        # embedded answer + guess dictionaries, daily pick
    ├── WordleLogic.kt        # pure rules: evaluation, key hints, points
    ├── WordleViewModel.kt    # daily state machine, per-guess persistence
    ├── WordleGrid.kt         # 6x5 board, tile flip/shake animations
    ├── WordleKeyboard.kt     # on-screen QWERTY with verdict coloring
    ├── WordleChrome.kt       # header, toast, result veil + countdown
    └── WordleScreen.kt       # assembly + physical keyboard input
```

Leaderboards have two windows: all-time and "this week" (Monday 00:00 UTC,
`LeaderboardService.weekStartIso()` → `arcade_leaderboard(p_since)`); both the
overlay and the home insights strip carry the toggle.

Key patterns:
- Providers from `PluginContext` are nullable — the game must always work with
  auth/supabase/storage absent (leaderboard degrades to a message).
- Tile identity: `TileData.id` is stable across moves so Compose animates
  position; merges double values at settle time, not slide time.
- Score submission goes through `pluginScope` (survives tab close); game-phase
  coroutines use the tab component's scope (cancelled on destroy).
- Backend: `supabase/arcade_schema.sql` (RLS insert-own/read-authenticated,
  `arcade_submit_score` / `arcade_personal_best` / `arcade_leaderboard` RPCs).
- Wordle's shared daily word is client-derived (hash of the UTC epoch day over
  the embedded answer list) — no server, so every machine agrees; the day's
  guesses persist to plugin storage per user so a board can't be replayed.

## Adding a new game (checklist)

1. **New package** `arcade/<game>/` — follow `game2048/` for turn-based games
   (pure logic object + ViewModel + Screen) or `mirrordash/` for real-time ones
   (engine + withFrameNanos loop + renderer). Keep the original game's feel:
   port timings/values 1:1.
2. **Leaderboard** — pick a game key (kebab-case, e.g. `"snake"`). No SQL
   changes: `arcade_scores` is game-keyed. Use
   `services.leaderboard.submitAsync(services.pluginScope, GAME, score)` for
   submits and `LeaderboardOverlay(leaderboard, GAME, onClose)` for display.
   Local best storage key convention: `"best.<game>.<userId|local>"`.
3. **Wire the tab** — add to `ArcadeScreen` enum; in `ArcadeTabComponent` add a
   lazily-created VM field (+ `onDisposed()` in `doOnDestroy`) and a nav case.
4. **Home screen** — add a `GameCard` in `ArcadeHomeScreen` and the game key to
   the list in `ArcadeHomeInsights`.
5. **MCP play tools** (turn-based games only) — extend `ArcadeMcpTools` +
   `ArcadeGameHost` so agent moves are visible on screen (see the 2048 tools).
   Real-time games get leaderboard-only.
6. **Docs** — README games list + this file's layout tree.
7. **Ship** — `./gradlew buildPluginJar` → copy JAR to `~/.boss/plugins/` (and
   `~/.boss_debug/plugins/`) for local test → push to `main` (auto bump,
   release, store publish). Pull after the bot's bump commit. If a push doesn't
   trigger the workflow, `gh workflow run build.yml` dispatches it manually.

## Version Management

`build.gradle.kts` is the single source of truth; `processResources` syncs it
into `plugin.json` at build time (guarded with `inputs.property`). Never
hand-edit the version in `plugin.json`.

## CI/CD

Pushes to `main` trigger `.github/workflows/build.yml`, which delegates to the
shared release workflow in `risa-labs-inc/BossConsole-Releases` (version bump →
GitHub release → Plugin Store publish).
