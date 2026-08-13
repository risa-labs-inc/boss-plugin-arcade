-- BOSS Arcade: scores table + RPCs used by the plugin's LeaderboardService.
-- Run once in the BOSS Supabase project (SQL editor or as a migration).
--
-- This file is the schema as it should exist in a FRESH project. An already-
-- deployed project gets there via arcade_events_migration.sql instead — that
-- one preserves history and marks pre-existing rows 'legacy'.
--
-- Design notes:
-- * One row per telemetry event, not per run — see `event` below. Leaderboards
--   take MAX(score) over the scoring events.
-- * RLS: players insert only their own rows; any authenticated user can read.
-- * arcade_leaderboard is SECURITY DEFINER only to read display names from
--   auth.users; adapt the join if you'd rather use a public profiles table.

create table if not exists public.arcade_scores (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  game text not null,
  score integer not null check (score >= 0 and score < 100000000),
  -- open     - an Arcade tab was opened     (score 0, game = 'arcade')
  -- start    - a run began                  (score 0)
  -- progress - mid-run personal-best sync   (score > 0)
  -- final    - a run ended                  (score >= 0; 0 = scoreless loss)
  -- legacy   - pre-migration OR written by a client that did not say;
  --            unclassifiable, excluded from run counts
  event text not null default 'legacy'
    check (event in ('legacy', 'open', 'start', 'progress', 'final')),
  created_at timestamptz not null default now()
);

create index if not exists arcade_scores_game_score_idx
  on public.arcade_scores (game, score desc);
create index if not exists arcade_scores_user_game_idx
  on public.arcade_scores (user_id, game);
create index if not exists arcade_scores_event_created_idx
  on public.arcade_scores (event, created_at desc);

alter table public.arcade_scores enable row level security;

drop policy if exists arcade_scores_insert_own on public.arcade_scores;
create policy arcade_scores_insert_own on public.arcade_scores
  for insert to authenticated
  with check (user_id = auth.uid());

drop policy if exists arcade_scores_read_all on public.arcade_scores;
create policy arcade_scores_read_all on public.arcade_scores
  for select to authenticated
  using (true);

-- Record one telemetry event for the calling user. p_event defaults to 'legacy'
-- so a two-argument call from an older client is counted for the leaderboard
-- but never mistaken for a finished run.
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

-- The calling user's all-time best for a game (0 when none). Ignores the
-- zero-score bookkeeping rows; 'progress' counts, because the whole point of
-- the mid-run sync is that the board must not depend on reaching game over.
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

-- Top N: best score per user, with a human-readable name. p_since limits the
-- window (e.g. Monday 00:00 UTC for the weekly race); null = all-time.
-- NOTE: the signature has changed before (p_since, then p_event on submit);
-- drop stale overloads first or PostgREST calls become ambiguous.
drop function if exists public.arcade_leaderboard(text, integer);
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
    coalesce(
      u.raw_user_meta_data ->> 'full_name',
      u.raw_user_meta_data ->> 'name',
      split_part(u.email, '@', 1)
    ) as display_name,
    b.best_score,
    b.achieved_at
  from best b
  join auth.users u on u.id = b.user_id
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

revoke all on function public.arcade_leaderboard(text, integer, timestamptz) from public;
grant execute on function public.arcade_leaderboard(text, integer, timestamptz) to authenticated;
revoke all on function public.arcade_submit_score(text, integer, text) from public;
grant execute on function public.arcade_submit_score(text, integer, text) to authenticated;
revoke all on function public.arcade_personal_best(text) from public;
grant execute on function public.arcade_personal_best(text) to authenticated;
grant select on public.arcade_usage_daily to authenticated;
