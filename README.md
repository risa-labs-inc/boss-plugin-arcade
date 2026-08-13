# BOSS Arcade Plugin

Quick competitive games inside BOSS, with team leaderboards. The games are
native Compose ports that preserve the mechanics, timing, and feel of their
original single-file HTML versions.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.arcade`
- **Type**: tab (open from the new-tab menu → Arcade)
- **Games**:
  - **2048** — arrow keys / WASD.
  - **Mirror Dash** — one-tap survival runner (tap/Space to reverse two mirrored
    sparks, dodge gates, collect shards for combo). Native Compose Canvas port
    of the original HTML canvas game; the WebAudio beeps did not carry over.
  - **Typing Sprint** — 60 seconds of typing; score = WPM × accuracy (correct
    characters only, paste-proof). The clock starts on the first keystroke.
  - **Sky Stack** — tap/Space to drop alternating isometric blocks, trim misses,
    and chain perfect landings while the backdrop climbs from dusk to space.
    The game screen captures keyboard focus on entry, so Space works before the
    first mouse click. Native synthesized tones preserve the original landing,
    perfect-combo, and game-over sounds. After a run, **View Full Tower** zooms
    the entire final stack into view and can save it as a shareable SVG or PNG.
  - **Wordle** — the daily word game. Everyone gets the same word (a hash of
    the UTC epoch day picks from the original's 2,315 answers — no server
    involved), one board per day, and solving in fewer guesses scores more
    points (7 − guesses). Guesses persist locally, so closing the tab never
    grants a retry, and the result card copies the classic emoji share grid.
  - **Battleship** — async head-to-head against a teammate. Challenge someone
    and place your fleet; they accept, place theirs, and you alternate one shot
    at a time whenever each of you gets a minute — no need to be online
    together. The Arcade card badges how many games are waiting on your move.
    Your fleet is only ever readable by you: placements live behind row-level
    security and every shot is resolved on the server, so the opponent's board
    genuinely cannot be read from the client. Battleship keeps win/loss
    standings rather than a high-score leaderboard. You can have at most three
    unanswered challenges out at once — spraying challenges is not the same as
    having opponents. Notifications are **off by default**: a Battleship turn is
    never urgent, so the Arcade does not interrupt your work uninvited, and the
    count on the Arcade card tells you what's waiting whenever you next look.
    Turn them on from the Battleship lobby and you'll get a toast — at most one
    every 30 minutes, with a button straight to the board. Toasts are in-app, so
    a challenge sent while BOSS is closed shows up at your next launch.

  The home screen is a picker, so new games slot in as additional screens
  sharing the same leaderboard plumbing (each game is a `game` key in
  `arcade_scores` — no backend change needed per game).

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

## MCP tools

Surfaced to in-terminal agents as `mcp__boss__arcade_*`:

- `arcade_leaderboard` — top scores per player for a game (2048, mirror-dash,
  sky-stack, typing-sprint, or wordle).
- `arcade_2048_state` / `arcade_2048_move` / `arcade_2048_new_game` /
  `arcade_2048_keep_going` — read and play the live 2048 board in the open
  Arcade tab; the agent's moves animate on the user's screen. Requires an
  Arcade tab with 2048 open. Mirror Dash and Sky Stack are reflex/real-time, so
  they expose no play tools.
- `arcade_wordle_state` / `arcade_wordle_guess` — read and play today's live
  Wordle board; the agent's guesses flip on the user's screen and burn the
  user's shared daily board, so agents should only play when asked.

The home screen also shows an "On the board" strip (top-3 podium, player count,
latest score per game) so the picker itself advertises the competition.

## Build & local test

```bash
./gradlew buildPluginJar   # → build/libs/boss-plugin-arcade-<version>.jar
cp build/libs/boss-plugin-arcade-*.jar ~/.boss/plugins/      # prod host
cp build/libs/boss-plugin-arcade-*.jar ~/.boss_debug/plugins/ # dev-mode host
```

Locally it compiles against `../boss-plugin-api/build/libs/boss-plugin-api-1.0.64.jar`
(CI downloads the pinned jar instead). Reload from Toolbox or restart the app.
