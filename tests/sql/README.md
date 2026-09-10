# Arcade SQL regressions

Run `npm ci --ignore-scripts && npm test` in this directory. The tests execute the
actual fresh/upgrade SQL and grant sweep in an in-memory PostgreSQL instance
(PGlite), with real authenticated/anonymous roles. No URL, service credentials,
Docker or live database is used.

The small external-platform contract fixture supplies the visible-user set and
owner-only display-name helper; BossConsole #423 owns/tests the actual org rule.
Fixtures verify scoped score/picker/leaderboard reads, anonymous denial, and
recovery from accidentally re-granted internal helpers and unknown overloads.
CI runs these tests on PRs independently of the plugin's main-only release job.

Apply operational SQL files in full, not selected statements. The grant sweep
and upgrade run in transactions. Its audits must return zero rows; a failed effective-privilege or required-RPC
postcondition aborts the transaction. Optional modules may be absent, but a module
with installed tables must retain its declared client signatures. Inherited
privileges or ownership preventing revocation need operator investigation.
No production deployment or incident-state verification is implied by these tests.
