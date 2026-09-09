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
  - **Poker** — live multiplayer No-Limit Hold'em. Unlike the other games, it's
    not a Compose port: the card embeds the poker web app via the host's
    browser service, so the table (and its own leaderboards) lives in the web
    app and there is no `arcade_scores` entry. If the embedded browser isn't
    available, the screen shows the table URL to open externally instead.
    In-terminal agents can also play at the table as you, through the
    `poker_*` MCP tools below.

  The home screen is a picker, so new games slot in as additional screens
  sharing the same leaderboard plumbing (each game is a `game` key in
  `arcade_scores` — no backend change needed per game).

## Arcade credits

Every game run costs Arcade credits — play money (✦), tracked server-side per
user with a weekly floor of 10,000 (balances refill up to the floor every
Monday). The home screen shows your balance as a chip and each game card wears
its price tag; poker charges nothing at run start because its buy-ins are the
cost, handled inside the poker web app.

- **Charging is latency-free by design**: starting a run checks only the
  *cached* balance and fires the real `arcade_charge_run` in the background,
  optimistically deducting locally. A charge that comes back
  `insufficient_credits` doesn't interrupt the run that already began — the
  corrected balance simply blocks the *next* one, with an in-game card showing
  balance, cost, and a "Request credits" button.
- **Top-ups are requested in-app** (amount + note, from the chip or the
  blocking card; one open request at a time) and approved by admins right in
  the Arcade: the Admin panel on the home screen lists requests, pending first,
  with approve (amount editable) / deny.
- **Multiplayer**: each Battleship player is charged by their own client at
  their own match start (challenge sent / challenge accepted).
- **Degrade open, always**: signed out, no Supabase provider, credits schema
  not deployed yet, or any credits RPC failing — the credits UI hides and every
  game plays free. A credits outage can never lock anyone out.

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

Apply these to the BOSS Supabase project (SQL editor or a migration), in order.
The order is load-bearing, not tidiness: with `check_function_bodies` at its
default `on`, a file that calls a predicate the previous file has not created
yet fails at CREATE time.

0. **Prerequisite, and it lives in another repo:**
   `BossConsole/supabase/migrations/20260908010000_org_visibility.sql`, which
   defines `public.org_visible_users()`, `public.org_is_vetted()` and
   `public.user_display_name()`. Arcade does not own the visibility rule - poker
   shares it - so step 1 is only an alias over these and will not create
   without them.
1. `supabase/arcade_scope.sql` - Arcade's names for that rule
   (`arcade_visible_users`, the set form every read path uses;
   `arcade_may_see`, the single-target form for write paths;
   `arcade_display_name`).
2. `supabase/arcade_schema.sql` - `arcade_scores` (RLS: insert-own, and read
   only rows for users the visibility rule accepts) plus
   `arcade_submit_score`, `arcade_personal_best`, `arcade_leaderboard`.
3. `supabase/arcade_battleship.sql` - matches, fleets, shots and the
   `arcade_bs_*` RPCs.
4. `supabase/arcade_grants_audit.sql` - run this LAST, and again after any
   change to the files above. It takes EXECUTE away from `anon` and PUBLIC
   across every `arcade_*` object, grants back only what the plugin calls, and
   then proves it with two audit queries that must return no rows.

An already-deployed project runs `supabase/arcade_events_migration.sql` in
place of step 2; it carries the same read policy, for the same reason.

Step 4 is not optional housekeeping. This project's default privileges grant
EXECUTE on every new function in schema `public` to `anon`, so a SECURITY
DEFINER function is callable by anyone holding the anon key from the moment it
is created - and the anon key ships inside the public BossConsole repo. A
`revoke ... from public` does not remove an explicit grant to `anon`.

Leaderboards and the opponent picker show only players the caller shares a
vetted organisation with, not everyone who has opened the Arcade. An account in
no vetted organisation - which is what open self-signup produces - sees a board
of one and an empty opponent picker. That is the intended outcome, and it is
indistinguishable at the UI from having no colleagues yet.

One consequence worth knowing before you read numbers off them: the
`arcade_usage_daily` and `arcade_overview` views are `security_invoker`, so they
inherit that same read policy. Queried as `authenticated` they now report the
caller's visible slice, with nothing to distinguish that from the true total.
For real totals query them as `postgres` or `service_role`, which bypass RLS.

The credits economy needs its own RPCs (`arcade_my_credits`,
`arcade_charge_run`, `arcade_request_credits`, `arcade_is_admin`,
`arcade_admin_requests`, `arcade_admin_resolve`), landed by a separate SQL
migration. Until they exist the plugin detects the errors and plays free with
the credits UI hidden, so the client can ship ahead of the schema.

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
- `poker_lobby` / `poker_state` / `poker_sit` / `poker_leave` / `poker_act` —
  play live multiplayer poker AS the signed-in user. Auth is the console-SSO
  flow (mint a one-time code via `poker_sso_code`, exchange + verify for a
  short-lived access token; re-run on expiry), and the moves go straight to the
  poker edge function over HTTP — no browser needed to play. The first poker
  tool call surfaces the Poker screen in an Arcade tab (opening one if needed)
  and mutating tools re-surface it, so the user watches the agent's play live
  via the web app's realtime updates. Etiquette is baked into the tool
  descriptions: act only when `poker_state` says `yourTurn`, and never sit at
  a table unless the user asked to play.

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
