-- BOSS Arcade: scores table + RPCs used by the plugin's LeaderboardService.
-- Run once in the BOSS Supabase project (SQL editor or as a migration).
--
-- Design notes:
-- * One row per finished run; leaderboard takes MAX(score) per user.
-- * RLS: players insert only their own rows; any authenticated user can read.
-- * arcade_leaderboard is SECURITY DEFINER only to read display names from
--   auth.users; adapt the join if you'd rather use a public profiles table.

create table if not exists public.arcade_scores (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  game text not null,
  score integer not null check (score > 0 and score < 100000000),
  created_at timestamptz not null default now()
);

create index if not exists arcade_scores_game_score_idx
  on public.arcade_scores (game, score desc);
create index if not exists arcade_scores_user_game_idx
  on public.arcade_scores (user_id, game);

alter table public.arcade_scores enable row level security;

drop policy if exists arcade_scores_insert_own on public.arcade_scores;
create policy arcade_scores_insert_own on public.arcade_scores
  for insert to authenticated
  with check (user_id = auth.uid());

drop policy if exists arcade_scores_read_all on public.arcade_scores;
create policy arcade_scores_read_all on public.arcade_scores
  for select to authenticated
  using (true);

-- Record a finished run for the calling user.
create or replace function public.arcade_submit_score(p_game text, p_score integer)
returns void
language sql
security invoker
as $$
  insert into public.arcade_scores (user_id, game, score)
  values (auth.uid(), p_game, p_score);
$$;

-- The calling user's all-time best for a game (0 when none).
create or replace function public.arcade_personal_best(p_game text)
returns integer
language sql
stable
security invoker
as $$
  select coalesce(max(score), 0)
  from public.arcade_scores
  where game = p_game and user_id = auth.uid();
$$;

-- Top N: best score per user, with a human-readable name.
create or replace function public.arcade_leaderboard(p_game text, p_limit integer default 10)
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

revoke all on function public.arcade_leaderboard(text, integer) from public;
grant execute on function public.arcade_leaderboard(text, integer) to authenticated;
revoke all on function public.arcade_submit_score(text, integer) from public;
grant execute on function public.arcade_submit_score(text, integer) to authenticated;
revoke all on function public.arcade_personal_best(text) from public;
grant execute on function public.arcade_personal_best(text) to authenticated;
