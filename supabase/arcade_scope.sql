-- BOSS Arcade: who may see whom, and what name to show.
--
-- THE PROBLEM THIS SOLVES
--
-- Every Arcade function that shows a leaderboard, a standings row or an
-- opponent picker has to answer the same question - "may the caller learn that
-- this person exists, and their name?" - and before this file each one answered
-- a weaker version of it locally:
--
--   arcade_leaderboard    no filter at all: every player, to every caller
--   arcade_bs_standings   no filter at all
--   arcade_players        `s.user_id <> auth.uid()`, i.e. "everyone but me"
--   arcade_bs_challenge   `exists (select 1 from auth.users ...)`, i.e. anyone
--
-- Each looks sufficient inside its own file. Together they published the roster
-- of everyone who has opened the Arcade to every account in the project - and
-- signup is open, so "every account" is not a closed set. They also reached
-- unauthenticated callers, because this project's default privileges make a new
-- function anon-callable at birth (see arcade_grants_audit.sql).
--
-- THE RULE IS NOT OURS
--
-- The visibility rule itself lives at the platform layer, in
-- BossConsole/supabase/migrations/20260908010000_org_visibility.sql, as
-- `public.org_visible_users()`: you, plus everyone sharing an active membership
-- in a vetted organisation (not `is_system`, not open-join - so not the
-- catch-all `boss` org that every account joins on signup). Poker table chat
-- uses the same function. Arcade must not keep a second copy of it; the two
-- functions below are a named alias and a single-target wrapper, nothing more.
--
-- REQUIRES that platform migration to be applied first.

-- Arcade's name for the platform rule. Read paths use THIS (the set form): as a
-- per-row predicate the same rule gets pushed below the `distinct on` in
-- arcade_leaderboard and evaluated once per score row - 2.7s across 29k rows
-- for 45 players, versus 37ms for the set.
create or replace function public.arcade_visible_users()
returns setof uuid
language sql
stable
security definer
set search_path = public
as $$
  select public.org_visible_users();
$$;

-- The same rule for a single target, for write paths (arcade_bs_challenge),
-- where there is one target and the set would be built once anyway. Defined in
-- terms of the set so there is still exactly one rule - do not reimplement the
-- organisation join here, or in a call site.
create or replace function public.arcade_may_see(p_other uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select p_other is not null
     and exists (
       select 1 from public.arcade_visible_users() vu where vu = p_other
     );
$$;

-- Arcade's name for the platform display-name rule
-- (public.user_display_name, BossConsole 20260908010000_org_visibility.sql).
-- An alias, not a second definition: poker's leaderboard and lobby call the
-- same function, and the copies disagreeing about 'full_name' vs
-- 'display_name' is why one person could appear under two names on two boards.
create or replace function public.arcade_display_name(p_user uuid)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select public.user_display_name(p_user);
$$;

-- Superseded by public.org_is_vetted in the platform migration. Dropped rather
-- than left in place, so there is no second definition of "is this org a trust
-- boundary" for a future call site to find and use.
drop function if exists public.arcade_is_scoping_org(uuid);

-- Grants.
--
-- `revoke ... from public` does NOT remove an explicit grant to `anon`, and this
-- project's default privileges grant EXECUTE on every new function in schema
-- public to BOTH anon and authenticated - so a revoke that names only `public`
-- never took the anon grant away, which is how these functions came to be
-- callable unauthenticated. Name anon explicitly, and run
-- arcade_grants_audit.sql afterwards to prove it across every arcade_*.
--
-- arcade_visible_users needs `authenticated` EXPLICITLY, not by inheritance: the
-- arcade_scores read policy calls it, and an RLS policy expression is evaluated
-- as the querying role. It is safe to expose - it returns only the caller's own
-- visible set. arcade_may_see and arcade_display_name are called only from
-- inside SECURITY DEFINER functions, which run as owner, so they need no client
-- grant at all.
revoke all on function public.arcade_visible_users() from public, anon;
revoke all on function public.arcade_may_see(uuid) from public, anon, authenticated;
revoke all on function public.arcade_display_name(uuid) from public, anon, authenticated;
grant execute on function public.arcade_visible_users() to authenticated;

-- The call sites live in their own files and each depends on this one:
--   arcade_schema.sql            arcade_leaderboard, arcade_scores read policy
--   arcade_events_migration.sql  arcade_leaderboard (migration path)
--   arcade_battleship.sql        arcade_players, arcade_bs_standings,
--                                arcade_bs_challenge, arcade_bs_my_matches,
--                                arcade_bs_match_detail
-- Apply this file BEFORE any of them.
