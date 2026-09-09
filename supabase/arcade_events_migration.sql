-- BOSS Arcade migration 002: make arcade_scores say what actually happened.
--
-- Why: before this, every row was an undifferentiated "score". 2048 throttle-
-- submits a new personal best every 2s *during* a run (Game2048ViewModel
-- .scheduleBestSubmit), so `count(*)` for 2048 counted score syncs, not runs —
-- it over-reported play by roughly 100x. Nothing recorded a tab open, and a
-- lost Wordle recorded nothing at all, so reach was under-reported.
--
-- After this, `event` distinguishes:
--   open     - an Arcade tab was opened          (score 0, game = 'arcade')
--   start    - a run began                       (score 0)
--   progress - mid-run personal-best sync        (score > 0)
--   final    - a run ended                       (score >= 0; 0 = scoreless loss)
--   legacy   - written before this migration, OR by a client that did not say
--              which event it was. NOT classifiable, exclude from run counts.
--              Distinct-player and daily-active counts over legacy rows are
--              still sound.
--
-- Safe to apply while old clients are still deployed: they call
-- arcade_submit_score with two named args, PostgREST fills p_event from its
-- default, and their rows land as 'legacy' — counted for the leaderboard,
-- never counted as runs. That matters: a pre-0.1.15 client syncs a 2048 best
-- every 2s, so labelling those 'final' would re-create the very overcount this
-- migration exists to remove.

alter table public.arcade_scores add column if not exists event text;
update public.arcade_scores set event = 'legacy' where event is null;
alter table public.arcade_scores alter column event set default 'legacy';
alter table public.arcade_scores alter column event set not null;

alter table public.arcade_scores drop constraint if exists arcade_scores_event_check;
alter table public.arcade_scores add constraint arcade_scores_event_check
  check (event in ('legacy', 'open', 'start', 'progress', 'final'));

-- score 0 is now legal: open/start carry no score, and a scoreless loss is a
-- real outcome worth recording. The leaderboard filters score > 0 instead.
alter table public.arcade_scores drop constraint if exists arcade_scores_score_check;
alter table public.arcade_scores add constraint arcade_scores_score_check
  check (score >= 0 and score < 100000000);

create index if not exists arcade_scores_event_created_idx
  on public.arcade_scores (event, created_at desc);

-- The read policy, repeated from arcade_schema.sql because THIS is the file an
-- already-deployed project runs. Without it production keeps
-- `arcade_scores_read_all using (true)`: every other limb of the visibility fix
-- lands and the 29k-row per-user activity feed stays readable by every account
-- in the project. Idempotent - both drops are unconditional.
--
-- Depends on public.arcade_visible_users (arcade_scope.sql), so that file and
-- the platform migration it aliases must be applied before this one.
drop policy if exists arcade_scores_read_all on public.arcade_scores;
drop policy if exists arcade_scores_read_visible on public.arcade_scores;
create policy arcade_scores_read_visible on public.arcade_scores
  for select to authenticated
  using (user_id in (select vu from public.arcade_visible_users() vu));

-- Old 2-arg overload must go or PostgREST calls become ambiguous.
drop function if exists public.arcade_submit_score(text, integer);
create or replace function public.arcade_submit_score(
  p_game text,
  p_score integer,
  p_event text default 'legacy'
)
returns void
language sql
security invoker
as $$
  insert into public.arcade_scores (user_id, game, score, event)
  values (
    auth.uid(),
    p_game,
    greatest(coalesce(p_score, 0), 0),
    -- A client that does not say what this row is gets 'legacy', not 'final'.
    -- Pre-0.1.15 clients call with two args and would otherwise label their
    -- every-2s 2048 sync a finished run. 'legacy' still counts for the
    -- leaderboard, it just never counts as a run.
    case
      when p_event in ('open', 'start', 'progress', 'final', 'legacy') then p_event
      else 'legacy'
    end
  );
$$;

-- Scoring reads ignore the zero-score bookkeeping rows. 'progress' still counts:
-- the whole point of the mid-run sync is that the board must not depend on a
-- player reaching game over.
create or replace function public.arcade_personal_best(p_game text)
returns integer
language sql
stable
security invoker
as $$
  select coalesce(max(score), 0)
  from public.arcade_scores
  where game = p_game
    and user_id = auth.uid()
    and score > 0
    and event in ('legacy', 'progress', 'final');
$$;

drop function if exists public.arcade_leaderboard(text, integer, timestamptz);
create or replace function public.arcade_leaderboard(
  p_game text,
  p_limit integer default 10,
  p_since timestamptz default null
)
returns table (user_id uuid, display_name text, best_score integer, achieved_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  with best as (
    select distinct on (s.user_id)
      s.user_id, s.score as best_score, s.created_at as achieved_at
    from public.arcade_scores s
    where s.game = p_game
      and s.score > 0
      and s.event in ('legacy', 'progress', 'final')
      and (p_since is null or s.created_at >= p_since)
    order by s.user_id, s.score desc, s.created_at asc
  )
  select
    b.user_id,
    public.arcade_display_name(b.user_id) as display_name,
    b.best_score,
    b.achieved_at
  from best b
  -- The visibility rule lives in arcade_scope.sql. Do not inline a subset of
  -- it here: this board is one of five sites that ask the same question, and
  -- they drifted apart last time. The set form, not arcade_may_see(b.user_id),
  -- because a per-row qual on the DISTINCT ON key is pushed below the dedup
  -- and then runs once per score row instead of once per player.
  where b.user_id in (select vu from public.arcade_visible_users() vu)
  order by b.best_score desc, b.achieved_at asc
  limit least(greatest(coalesce(p_limit, 10), 1), 50);
$$;

-- Honest usage rollup. security_invoker so it inherits the table's RLS rather
-- than handing every caller a view that reads as its owner.
create or replace view public.arcade_usage_daily
with (security_invoker = true) as
select
  (created_at at time zone 'UTC')::date          as day,
  game,
  count(*) filter (where event = 'start')        as runs,
  count(*) filter (where event = 'open')         as tab_opens,
  count(distinct user_id)                        as players,
  count(distinct user_id) filter (where event = 'start') as players_who_played,
  max(score) filter (where event in ('progress', 'final')) as top_score
from public.arcade_scores
where event <> 'legacy'
group by 1, 2;

-- All-time per-game picture, honest about what is countable. Excludes 'legacy'
-- (pre-migration rows and pre-0.1.15 clients, which cannot be classified) and
-- the 'arcade' pseudo-game used for tab opens.
create or replace view public.arcade_overview
with (security_invoker = true) as
select
  game,
  count(*) filter (where event = 'start')                          as runs,
  count(distinct user_id) filter (where event = 'start')           as players,
  max(score) filter (where event in ('progress', 'final'))         as top_score,
  min(created_at) filter (where event = 'start')                   as first_run,
  max(created_at)                                                  as last_activity
from public.arcade_scores
where event <> 'legacy'
  and game <> 'arcade'
group by game;

revoke all on function public.arcade_leaderboard(text, integer, timestamptz) from public, anon;
grant execute on function public.arcade_leaderboard(text, integer, timestamptz) to authenticated;
revoke all on function public.arcade_submit_score(text, integer, text) from public, anon;
grant execute on function public.arcade_submit_score(text, integer, text) to authenticated;
revoke all on function public.arcade_personal_best(text) from public, anon;
grant execute on function public.arcade_personal_best(text) to authenticated;
grant select on public.arcade_usage_daily to authenticated;
grant select on public.arcade_overview to authenticated;
