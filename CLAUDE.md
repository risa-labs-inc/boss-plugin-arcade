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
├── CreditsService.kt         # credits economy client (arcade_my_credits/charge/request/admin)
├── CreditsUi.kt              # balance chip, out-of-credits card, request dialog, admin panel
├── game2048/
│   ├── Game2048Logic.kt      # pure rules (port of the HTML logic block)
│   ├── Game2048ViewModel.kt  # state machine; 105ms slide → settle → veil; cross-sitting resume
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
├── battleship/
│   ├── BattleshipLogic.kt     # pure rules: fleet, placement, straight-run checks
│   ├── BattleshipNotifier.kt  # plugin-level poll -> toast when a game awaits you
│   ├── BattleshipTime.kt      # "5 min ago" wording for the async wait
│   ├── BattleshipService.kt   # arcade_bs_* RPC client (no "read their fleet" call)
│   ├── BattleshipViewModel.kt # lobby/placement/board phases, turn polling
│   ├── BattleshipSoundPlayer.kt # synthesized shot sounds (hit/miss/sunk, both sides)
│   ├── BattleshipGrid.kt      # shared 10x10 board + fleet roster
│   └── BattleshipScreen.kt    # lobby, opponent picker, placement, play board
├── wordle/
│   ├── WordleWords.kt        # embedded answer + guess dictionaries, daily pick
│   ├── WordleLogic.kt        # pure rules: evaluation, key hints, points
│   ├── WordleViewModel.kt    # daily state machine, per-guess persistence
│   ├── WordleGrid.kt         # 6x5 board, tile flip/shake animations
│   ├── WordleKeyboard.kt     # on-screen QWERTY with verdict coloring
│   ├── WordleChrome.kt       # header, toast, result veil + countdown
│   └── WordleScreen.kt       # assembly + physical keyboard input
└── poker/
    ├── PokerViewModel.kt     # owns the embedded-browser handle for the poker web app
    ├── PokerScreen.kt        # header + browser Content(), loading + no-browser fallback
    └── PokerAgentService.kt  # MCP poker client: console-SSO auth + edge-function ops over JDK HTTP
```

2048 auto-saves the run after every settled move (`save.2048.<user>` via
`putJson`) and resumes it on open across tab closes and app restarts; the save
is cleared on game over and overwritten by New game. Resuming records no
`start` analytics event — it is the same run continuing.

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
- Backend, in this order (see README for why the order is load-bearing): the
  platform migration `BossConsole/.../20260908010000_org_visibility.sql`, then
  `supabase/arcade_scope.sql` (Arcade's aliases for it), then
  `supabase/arcade_schema.sql` (`arcade_scores`; RLS is insert-own and
  read-only-what-the-visibility-rule-allows, NOT read-authenticated, plus the
  `arcade_submit_score` / `arcade_personal_best` / `arcade_leaderboard` RPCs),
  then `supabase/arcade_battleship.sql`, then `supabase/arcade_grants_audit.sql`
  after any of them. A deployed project runs `arcade_events_migration.sql`
  instead of `arcade_schema.sql`; it carries the same read policy.
- Telemetry: every row carries an `ArcadeEvent` — `open` (tab opened), `start`
  (run began), `progress` (mid-run best sync), `final` (run ended). **A row is
  not a run: count `event = 'start'`,** or just read the `arcade_usage_daily`
  view - but query it as `postgres`/`service_role`, because it is
  `security_invoker` and so inherits the visibility read policy: as
  `authenticated` it silently reports only the caller's visible slice. `final` can fire twice for one 2048 run (win, then game over), and
  `progress` fires every 2s while a 2048 run is live. `legacy` means "nobody
  said what this row was" — either written before this model landed, or by a
  pre-0.1.15 client whose 2-arg RPC call takes the default. Legacy rows count
  for the leaderboard and never as runs; do not try to classify them.
  Only `final` scores take the durable replay path; losing an `open`/`start`
  costs one telemetry point and is not worth a storage write per event.
- Wordle's shared daily word is client-derived (hash of the UTC epoch day over
  the embedded answer list) — no server, so every machine agrees; the day's
  guesses persist to plugin storage per user so a board can't be replayed.

**Arcade credits** (`CreditsService` + `CreditsUi`): every run costs play-money
credits, held server-side (weekly floor 10,000; admins approve top-up requests
in-app). The charging policy is *cached pre-check + optimistic background
charge*: `tryStartRun(game)` never touches the network — it gates on the cached
`snapshot`, optimistically deducts, and fires `arcade_charge_run` on the plugin
scope; an `insufficient_credits` response only corrects the cache, so it is the
NEXT run that blocks (via the tab-level `InsufficientCreditsCard` over whatever
screen refused). The charge hook is the same place each game records
`ArcadeEvent.START` — one charge per run start, restarts included; 2048's
cross-sitting resume charges nothing (same run continuing), Wordle charges on
the day's first accepted guess, and Battleship (multiplayer) splits the hook:
`canStartRun` gates the fleet submit, `chargeRun` fires only in `onSuccess`, so
each player pays for their own seat once the server accepts the match. Poker
charges nothing — buy-ins in the web app are its cost. **Degrade open is the
invariant**: null provider, signed out, credits schema not deployed, offline,
or any RPC/parse failure leaves `snapshot` null → credits UI hidden, every game
free, nothing thrown into the host (LinkageError included). Never let a credits
failure block a game. Costs shown before the first charge are the embedded
`DEFAULT_COST`; the server's `cost` field overrides per game once a charge
answers. `arcade_is_admin` returns a bare boolean, not JSON.

**Casino ambience** (`AmbienceSynth.kt` + `CasinoAmbience.kt`): procedurally
synthesized casino-floor audio — a warm chord pad, a lowpassed room murmur and
sparse chip-clink/card-riffle accents, all streamed in small buffers (no audio
files). DEFAULT OFF; the opt-in is the speaker toggle beside the credits chip,
persisted under `ambience.enabled`. One `CasinoAmbiencePlayer` lives on
`ArcadeServices` (plugin level) and playback demand is a refcounted set of tabs
whose HOME screen is currently composed — several open tabs can never
double-play, and entering any game fades the ambience out (~3s fade-in, ~0.9s
fade-out; games are silent by design, poker's web app has its own audio). Every
javax.sound call is runCatching-wrapped: a missing/busy device flips the toggle
to a muted "unavailable" state (a click retries) and never throws into the
host. The audio thread is a daemon thread, not a pluginScope coroutine, so the
watchdog's scope swap can't strand it; `dispose()` stops it and closes the line.

**Who may see whom**: the rule is NOT Arcade's. It lives at the platform layer
in `BossConsole/supabase/migrations/20260908010000_org_visibility.sql` as
`public.org_visible_users()` - you, plus everyone sharing an active membership
in an organisation `public.org_is_vetted()` accepts (not `is_system`, and
`join_policy <> 'open'`). That excludes the catch-all `boss` org every account
joins on signup, 153 members across 20 email domains; in practice the board is
your `risa` colleagues. Poker's chat, lobby and leaderboard call the same
function. `supabase/arcade_scope.sql` only aliases it.

**A new game's read path must filter with the SET form,
`where user_id in (select vu from public.arcade_visible_users() vu)` - NOT with
`arcade_may_see(user_id)`.** The per-target form is for write paths only (it is
revoked from `authenticated` for that reason). As a per-row qual the same rule
gets pushed below a `distinct on` and evaluated once per underlying row: 2.7s
across 29k score rows for 45 players, against 37ms for the set. Never re-derive
either form at a call site. Five sites each asked a weaker version of this
question - `arcade_players` asked only `<> auth.uid()`, the two boards asked
nothing at all - which published the whole roster to every account in the
project, and, because the project's default privileges grant EXECUTE to `anon`
on every new function in schema `public`, to unauthenticated callers holding the
anon key that ships in the public BossConsole repo. `revoke ... from public`
does NOT undo that grant: every revoke must name `anon` explicitly, and
`arcade_grants_audit.sql` is the sweep that proves it. The display-name rule
(`arcade_display_name`) is likewise one function; it was seven near-copies, three
of which disagreed about the metadata key. The email local part is still its last
resort because no current player has any other name - which is exactly why the
`arcade_may_see` gate has to hold.

Battleship is the odd one out: async head-to-head rather than a scored run, so
it has no leaderboard entry and its own `arcade_bs_standings` (W/L) instead.
**The server is the authority and the client is assumed hostile** — fleets are
readable only by their owner (RLS), fleet legality is re-validated in
`arcade_bs_validate_fleet` on every submit, and hit/miss is resolved inside
`arcade_bs_fire`. `BattleshipLogic` duplicates those rules purely so the
placement UI can refuse an illegal fleet without a round trip; never treat it as
the source of truth. See `supabase/arcade_battleship.sql`.

`BattleshipNotifier` runs from `register()` on `pluginScope`, NOT from the tab —
a challenge has to be announceable when no Arcade tab is open, which is exactly
when it would otherwise go unseen. It polls `arcade_bs_my_matches` every 60s.

**It is off by default and must stay that way.** BOSS is a work tool and a
Battleship turn has no urgency — turns are hours apart by design, and the Arcade
card's badge carries the same information for free. The plugin API offers no
ambient surface outside the tab (no tray, no badge, no dashboard slot;
`SettingsProvider` only *opens* the settings dialog), so the only channel that
reaches an unattended player is an interrupt — which is a reason to require
consent, not a licence to use it. The opt-in lives in the Battleship lobby and is
read every poll, so toggling takes effect without a restart.

Three layers keep even an opted-in player from being spammed: each announcement
is keyed on the match's `updated_at` and persisted, so a state is announced once
and a restart does not replay old news; `QUIET_GAP_MS` floors the interval
between toasts at 30 min; and backlogs above two collapse into one summary.
Note the ordering in `checkOnce` — a match is only marked "told" after a toast
actually shows, or news suppressed by the quiet gap would be swallowed for good.

`NotificationProvider` is an in-app toast only, so a challenge sent while BOSS is
closed is seen at next launch, not at send time.

Outgoing unanswered challenges are capped at 3 server-side. One player opened
nine in 90 seconds, which is how most games ended up idle.

Poker's MCP tools (`poker_lobby/state/sit/leave/act`) don't drive the embedded
web app — they play server-side through `PokerAgentService`, which re-implements
the web app's console-SSO flow: `poker_sso_code()` RPC (runs as the signed-in
user) → `sso_exchange` at the poker edge function → GoTrue `/auth/v1/verify` →
short-lived access token, cached in memory and re-minted on expiry or any 401
(no refresh tokens on purpose — codes are free). Mutating ops fetch the table
version first and retry once on `version_conflict`, reusing the same `actionId`
so the server's idempotency guard makes the retry safe. The service deliberately
touches only `SupabaseDataProvider` + JDK HTTP + kotlinx-serialization — nothing
that could LinkageError on an old console (the 0.1.22 lesson) — and every
failure returns an error string to the agent, never a throw into the host. The
embedded anon key is the project's PUBLIC anon key (ships in the web bundle);
visibility is `surfacePoker` in `ArcadeMcpTools`: best-effort (poker still plays
when no browser exists), first-use for reads, every call for mutations.

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
