# BOSS Arcade Plugin

Quick competitive games inside BOSS, with team leaderboards. First game: **2048** —
a native Compose port of the original single-file HTML game, keeping its feel
(100 ms tile slides, merge pops, spawn rise, score float, win/over veils).

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.arcade`
- **Type**: tab (open from the new-tab menu → Arcade)
- **Games**: 2048 (arrow keys / WASD). The home screen is a picker, so new games
  slot in as additional screens sharing the same leaderboard plumbing.

## How scores work

- Identity comes from the host (`authDataProvider`) — no extra login.
- Every finished run (game over, win, or closing the tab mid-run) is submitted
  via `supabaseDataProvider.rpc` to `arcade_submit_score`. The leaderboard is
  best-score-per-player (`arcade_leaderboard` RPC, top 10).
- Personal best is remembered per user: max of local plugin storage and the
  remote best, so it follows you across machines and logins.
- Everything degrades gracefully: signed out or no Supabase provider → the game
  still plays, leaderboard shows a friendly message.
- Scores are client-submitted and trivially spoofable — this is for fun, not
  for anything that needs integrity.

## One-time backend setup

Apply `supabase/arcade_schema.sql` to the BOSS Supabase project (SQL editor or
a migration). It creates `arcade_scores` (RLS: insert-own / read-authenticated)
and three RPCs: `arcade_submit_score`, `arcade_personal_best`,
`arcade_leaderboard`. The leaderboard function reads display names from
`auth.users` (security definer); switch the join to your profiles table if
preferred.

## MCP tool

`arcade_leaderboard` — read-only; lets in-terminal agents post standings
(surfaced as `mcp__boss__arcade_leaderboard`).

## Build & local test

```bash
./gradlew buildPluginJar   # → build/libs/boss-plugin-arcade-<version>.jar
cp build/libs/boss-plugin-arcade-*.jar ~/.boss/plugins/      # prod host
cp build/libs/boss-plugin-arcade-*.jar ~/.boss_debug/plugins/ # dev-mode host
```

Locally it compiles against `../boss-plugin-api/build/libs/boss-plugin-api-1.0.64.jar`
(CI downloads the pinned jar instead). Reload from Toolbox or restart the app.
