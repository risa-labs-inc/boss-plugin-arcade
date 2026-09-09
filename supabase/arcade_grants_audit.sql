-- BOSS Arcade: grant lockdown + audit.
--
-- WHY THIS FILE EXISTS
--
-- Every arcade_* SECURITY DEFINER function ends its own file with a
-- `revoke all ... from public; grant execute ... to authenticated` pair. That
-- pair is load-bearing and it is easy to lose: PostgreSQL grants EXECUTE on a
-- newly created function to PUBLIC by default, `create or replace` keeps the
-- existing grants but `drop` + `create` resets them to that default, and the
-- SQL editor is usually driven by pasting one function body at a time - which
-- is a drop-and-create for any signature change.
--
-- The observable result on the production project (2026-09-08) was that
-- arcade_leaderboard and arcade_bs_standings could be executed by the `anon`
-- role, i.e. by anyone holding the anon key - which is compiled into the
-- public BossConsole repo. Both return real display names, so the roster of
-- everyone who has opened the Arcade was readable without an account.
--
-- Run this file after ANY arcade_*.sql change. It is idempotent.

-- 1. Take EXECUTE away from PUBLIC and anon on every arcade_* function,
--    including overloads and internal helpers.
do $$
declare
  fn regprocedure;
begin
  for fn in
    select p.oid::regprocedure
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname like 'arcade\_%'
  loop
    execute format('revoke all on function %s from public', fn);
    execute format('revoke all on function %s from anon', fn);
  end loop;
end $$;

-- 2. Hand EXECUTE back to `authenticated`, by name, only for the functions the
--    plugin actually calls. Anything not on this list stays unreachable over
--    PostgREST - which is what an internal helper such as
--    arcade_bs_validate_fleet should be.
do $$
declare
  fn regprocedure;
  callable constant text[] := array[
    'arcade_submit_score', 'arcade_personal_best', 'arcade_leaderboard',
    'arcade_players',
    -- NOT optional: the arcade_scores read policy calls this, and an RLS
    -- policy expression is evaluated as the querying role. Omit it and every
    -- authenticated SELECT on arcade_scores fails with `permission denied for
    -- function arcade_visible_users`, while audits 4a/4b still report healthy -
    -- the sweep would say all-clear over broken reads.
    'arcade_visible_users',
    'arcade_bs_challenge', 'arcade_bs_accept', 'arcade_bs_decline',
    'arcade_bs_fire', 'arcade_bs_my_matches', 'arcade_bs_match_detail',
    'arcade_bs_standings',
    'arcade_my_credits', 'arcade_charge_run', 'arcade_request_credits',
    'arcade_is_admin', 'arcade_admin_requests', 'arcade_admin_resolve'
  ];
begin
  for fn in
    select p.oid::regprocedure
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname = any (callable)
  loop
    execute format('grant execute on function %s to authenticated', fn);
  end loop;
end $$;

-- 3. Same for the tables and views: no arcade_* relation is readable by anon.
do $$
declare
  rel regclass;
begin
  for rel in
    select c.oid::regclass
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relname like 'arcade\_%'
      and c.relkind in ('r', 'v', 'm', 'p')
  loop
    execute format('revoke all on %s from anon', rel);
    execute format('revoke all on %s from public', rel);
  end loop;
end $$;

-- 4. AUDIT. Both queries must return zero rows. Wire them into CI (or a
--    pg_cron check) rather than trusting that step 1 was remembered: grantee 0
--    is the PUBLIC pseudo-role, which every role including anon inherits.
--
-- 4a. Functions still executable without an account:
select
  p.oid::regprocedure                                as function,
  coalesce(r.rolname, 'PUBLIC')                      as grantee,
  a.privilege_type
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
left join pg_roles r on r.oid = a.grantee
where n.nspname = 'public'
  and p.proname like 'arcade\_%'
  and (a.grantee = 0 or r.rolname = 'anon')
order by 1, 2;

-- 4b. Relations still readable without an account:
select
  c.oid::regclass                                    as relation,
  coalesce(r.rolname, 'PUBLIC')                      as grantee,
  a.privilege_type
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
left join pg_roles r on r.oid = a.grantee
where n.nspname = 'public'
  and c.relname like 'arcade\_%'
  and c.relkind in ('r', 'v', 'm', 'p')
  and (a.grantee = 0 or r.rolname = 'anon')
order by 1, 2;
