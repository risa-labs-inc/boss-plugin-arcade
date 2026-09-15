# Split-pane layout and gameplay checks

Home reduces decorative space in narrow or short panes, keeping game costs and
actions reachable through wrapping and scrolling. The six native games and
shared dialogs are covered by production Compose fixtures:

| Surface | Behavior and regression coverage |
| --- | --- |
| 2048 | Measured square board; side-by-side controls in wide short panes; scroll recovery; pane-level win/loss actions; saved-run continuation and restart; tiny-width input guard. |
| Typing Sprint | Wrapping statistics/actions; scrolling passage/results; input and completed results survive resizing; restart returns to playable input. |
| Wordle | Fitted keyboard and vector Enter/Backspace controls; wrapping help/results; win/loss coverage; current guess retained; below 280dp pane width, readable recovery replaces gameplay and keys are ignored. |
| Battleship | Wrapping controls/rosters; scrolling lobby, placement, boards and opponent picker; square grids; below 240dp available board width, recovery replaces tiny cells; navigation resets scroll while resizing preserves it. |
| Mirror Dash | Scrollable menu/pause/result cards; readable HUD controls; fixed simulation width and uniformly scaled rendering preserve collision geometry during resizing. |
| Sky Stack | Scrollable menus and tower actions; projected moving-block path fits the available field; full-tower overview has no forced minimum scale; unusable overview dimensions show recovery. |
| Shared dialogs | Leaderboard close actions, credit requests/errors/insufficient balances, and long admin queues remain reachable. |
| Poker | Browser-unavailable message and URL scroll. The live embedded poker game is outside these fixtures. |

The core logical-dp matrix is 1000×700, 600×500, 420×300, 300×500,
900×240 and 300×240. Additional checks cover the 600×400 layout boundary,
180×160 recovery, Wordle's 260/280dp threshold and selected 1.5 font-scale
cases. Not every game/state is exercised at every possible size or font setting.
Tall content may require scrolling; passing reachability checks does not mean
an entire board and every control fit simultaneously in a 240dp-high pane.

Run all checks and build the plugin without starting BOSS:

```sh
./gradlew test buildPluginJar
```

The existing build supports either the local plugin API jar or `CI=true` with
the pinned API release jar at `build/downloaded-deps/boss-plugin-api.jar`.
The expanded run passed 108 tests, zero failures/errors/skips. Screen tests use
fake services and real production composables; engine tests verify resize
collision invariants and projected corner bounds. Screenshots are written to
`build/responsive-screenshots/` and were visually inspected. The stronger 2048
result tests fail against the first version of this PR and pass after its
pane-level overlay correction.

Fixtures do not call live accounts/backends, modify real game storage, play
audio, write the system clipboard or export user files. A deliberate fake
Battleship shot failure verifies routing without claiming real multiplayer
success. Typing DONE is reached through the existing finish-on-dispose path,
not by waiting through a real 60-second countdown.

Before release, verify actual host vertical/horizontal/nested splits, moving
running games between panes/windows, focus, density changes and restored
layouts. Poker's real browser table, live multiplayer/network success and
platform integration remain unverified. Other BOSS plugins are separate
repositories and are not fixed by this change.

All six native games and the shared credit overlays are constrained by the
`ArcadeBackground` pane boundary. Mirror Dash and Sky Stack also retain their
own root clips. Pixel regressions render all six real screens in an inset pane
surrounded by a contrasting host area at simulated density 1 and 2. They check
initial content, 2048 merge/win transitions, Wordle input/rejection animation,
Typing DONE, Battleship's opponent picker, shared credit dialogs, and an
explicit oversized-child paint probe. Existing direct canvas tests retain
seeded offscreen gates/fragments. Removing only the shared clip caused the
actual 2048 win transition to paint 4,920 pixels outside its pane at density 1;
the corrected suite passes. These surrounding-pixel assertions test containment
that cropped game-only screenshots cannot establish. They do not replace
physical-display or live host checks.
