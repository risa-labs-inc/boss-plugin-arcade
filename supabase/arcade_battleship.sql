-- BOSS Arcade: async head-to-head Battleship.
--
-- THE SECURITY PROPERTY THIS FILE EXISTS TO ENFORCE: a player must never be
-- able to read their opponent's fleet. The client talks to PostgREST with the
-- player's own JWT, so anyone can issue arbitrary queries against these tables
-- — RLS is the only thing standing between a curious teammate and the answer
-- key. Hence:
--   * fleets are SELECT-able only by their owner (never by the opponent),
--   * shots are inserted only through arcade_bs_fire, which resolves the shot
--     SECURITY DEFINER-side and returns hit/miss/sunk and nothing more,
--   * fleets are validated server-side on submission, because a client that
--     could submit four ships instead of five would be unsinkable.
--
-- Flow: A challenges B and places their fleet in the same call (status
-- 'pending') -> B accepts and places theirs (status 'active', A shoots first)
-- -> alternating shots until one fleet is gone (status 'finished').

create table if not exists public.arcade_matches (
  id uuid primary key default gen_random_uuid(),
  game text not null default 'battleship',
  player_a uuid not null references auth.users (id) on delete cascade,
  player_b uuid not null references auth.users (id) on delete cascade,
  -- pending  - challenged, opponent has not accepted
  -- active   - both fleets placed, someone's turn
  -- finished - one fleet fully sunk
  -- declined - opponent said no
  status text not null default 'pending'
    check (status in ('pending', 'active', 'finished', 'declined')),
  turn_user_id uuid references auth.users (id) on delete set null,
  winner_id uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (player_a <> player_b)
);

create index if not exists arcade_matches_player_a_idx on public.arcade_matches (player_a, status);
create index if not exists arcade_matches_player_b_idx on public.arcade_matches (player_b, status);

-- One row per player per match. `ships` is
-- [{"id":"carrier","cells":[0,1,2,3,4]}, ...] with cells as row*10+col.
create table if not exists public.arcade_battleship_fleets (
  match_id uuid not null references public.arcade_matches (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  ships jsonb not null,
  created_at timestamptz not null default now(),
  primary key (match_id, user_id)
);

create table if not exists public.arcade_battleship_shots (
  id uuid primary key default gen_random_uuid(),
  match_id uuid not null references public.arcade_matches (id) on delete cascade,
  shooter_id uuid not null references auth.users (id) on delete cascade,
  cell integer not null check (cell >= 0 and cell <= 99),
  result text not null check (result in ('miss', 'hit', 'sunk')),
  created_at timestamptz not null default now(),
  unique (match_id, shooter_id, cell)
);

create index if not exists arcade_bs_shots_match_idx
  on public.arcade_battleship_shots (match_id, created_at);

alter table public.arcade_matches enable row level security;
alter table public.arcade_battleship_fleets enable row level security;
alter table public.arcade_battleship_shots enable row level security;

-- Matches: readable by their two participants. All writes go through the RPCs.
drop policy if exists arcade_matches_read_own on public.arcade_matches;
create policy arcade_matches_read_own on public.arcade_matches
  for select to authenticated
  using (auth.uid() in (player_a, player_b));

-- Fleets: YOUR OWN ONLY. This is the whole game. Note there is deliberately no
-- policy granting the opponent read access, not even once the match is over.
drop policy if exists arcade_bs_fleets_read_own on public.arcade_battleship_fleets;
create policy arcade_bs_fleets_read_own on public.arcade_battleship_fleets
  for select to authenticated
  using (user_id = auth.uid());

-- Shots: both participants see every shot — you need your own history and to
-- see where you have been shot at. Shots reveal nothing hidden: the result of
-- a shot is public information the moment it is fired.
drop policy if exists arcade_bs_shots_read_participants on public.arcade_battleship_shots;
create policy arcade_bs_shots_read_participants on public.arcade_battleship_shots
  for select to authenticated
  using (
    exists (
      select 1 from public.arcade_matches m
      where m.id = match_id and auth.uid() in (m.player_a, m.player_b)
    )
  );

-- A fleet is exactly five ships of known sizes, each a straight contiguous run
-- inside the 10x10 grid, none overlapping. Rejecting a bad fleet at submission
-- is what stops "submit four ships and never lose".
create or replace function public.arcade_bs_validate_fleet(p_ships jsonb)
returns boolean
language plpgsql
immutable
as $$
declare
  expected constant jsonb :=
    '{"carrier":5,"battleship":4,"cruiser":3,"submarine":3,"destroyer":2}';
  ship jsonb;
  sorted int[];
  all_cells int[] := '{}';
  n int;
  i int;
  straight_row boolean;
  straight_col boolean;
begin
  if p_ships is null or jsonb_typeof(p_ships) <> 'array'
     or jsonb_array_length(p_ships) <> 5 then
    return false;
  end if;

  if (select count(distinct s->>'id') from jsonb_array_elements(p_ships) s) <> 5 then
    return false;
  end if;

  for ship in select * from jsonb_array_elements(p_ships) loop
    if not (expected ? (ship->>'id')) then
      return false;
    end if;
    n := (expected->>(ship->>'id'))::int;

    if jsonb_typeof(ship->'cells') <> 'array' then
      return false;
    end if;
    select array_agg(v::int order by v::int) into sorted
    from jsonb_array_elements_text(ship->'cells') v;

    if sorted is null or array_length(sorted, 1) <> n then
      return false;
    end if;
    if sorted[1] < 0 or sorted[n] > 99 then
      return false;
    end if;

    -- Horizontal: consecutive AND on one row, so a run must not wrap 9 -> 10.
    -- Vertical: a step of exactly 10 keeps the column by construction.
    straight_row := true;
    straight_col := true;
    for i in 2..n loop
      if sorted[i] <> sorted[i - 1] + 1 or (sorted[i] / 10) <> (sorted[i - 1] / 10) then
        straight_row := false;
      end if;
      if sorted[i] <> sorted[i - 1] + 10 then
        straight_col := false;
      end if;
    end loop;
    if not (straight_row or straight_col) then
      return false;
    end if;

    all_cells := all_cells || sorted;
  end loop;

  -- 5+4+3+3+2 = 17 distinct cells, so any overlap shows up as a shortfall.
  return (select count(distinct c) from unnest(all_cells) c) = 17;
end;
$$;

-- Challenge someone, placing your fleet in the same call.
create or replace function public.arcade_bs_challenge(p_opponent uuid, p_ships jsonb)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  new_id uuid;
begin
  if me is null then raise exception 'not signed in'; end if;
  if p_opponent is null or p_opponent = me then raise exception 'pick someone else'; end if;
  if not exists (select 1 from auth.users where id = p_opponent) then
    raise exception 'no such player';
  end if;
  if not public.arcade_bs_validate_fleet(p_ships) then
    raise exception 'invalid fleet';
  end if;
  -- One live game per pair, or a bored player could bury someone in challenges.
  if exists (
    select 1 from public.arcade_matches
    where status in ('pending', 'active')
      and ((player_a = me and player_b = p_opponent)
        or (player_a = p_opponent and player_b = me))
  ) then
    raise exception 'you already have a game running with that player';
  end if;

  insert into public.arcade_matches (player_a, player_b, status)
  values (me, p_opponent, 'pending')
  returning id into new_id;

  insert into public.arcade_battleship_fleets (match_id, user_id, ships)
  values (new_id, me, p_ships);

  return new_id;
end;
$$;

-- Accept a challenge, placing your fleet. The challenger shoots first.
create or replace function public.arcade_bs_accept(p_match uuid, p_ships jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  m public.arcade_matches;
begin
  select * into m from public.arcade_matches where id = p_match for update;
  if not found then raise exception 'no such match'; end if;
  if m.player_b <> me then raise exception 'that challenge is not yours to accept'; end if;
  if m.status <> 'pending' then raise exception 'challenge is no longer open'; end if;
  if not public.arcade_bs_validate_fleet(p_ships) then
    raise exception 'invalid fleet';
  end if;

  insert into public.arcade_battleship_fleets (match_id, user_id, ships)
  values (p_match, me, p_ships);

  update public.arcade_matches
  set status = 'active', turn_user_id = m.player_a, updated_at = now()
  where id = p_match;
end;
$$;

create or replace function public.arcade_bs_decline(p_match uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
begin
  update public.arcade_matches
  set status = 'declined', updated_at = now()
  where id = p_match and player_b = me and status = 'pending';
  if not found then raise exception 'no open challenge to decline'; end if;
end;
$$;

-- Fire. SECURITY DEFINER so it can read the opponent's fleet; it returns only
-- the outcome of this one shot, which is public information anyway.
create or replace function public.arcade_bs_fire(p_match uuid, p_cell integer)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  m public.arcade_matches;
  foe uuid;
  foe_ships jsonb;
  ship jsonb;
  hit_ship jsonb := null;
  outcome text := 'miss';
  sunk_id text := null;
  my_shots int[];
  ship_cells int[];
  left_afloat int;
begin
  select * into m from public.arcade_matches where id = p_match for update;
  if not found then raise exception 'no such match'; end if;
  if me is null or me not in (m.player_a, m.player_b) then
    raise exception 'not a participant';
  end if;
  if m.status <> 'active' then raise exception 'match is not active'; end if;
  if m.turn_user_id <> me then raise exception 'not your turn'; end if;
  if p_cell is null or p_cell < 0 or p_cell > 99 then
    raise exception 'cell out of range';
  end if;
  if exists (
    select 1 from public.arcade_battleship_shots
    where match_id = p_match and shooter_id = me and cell = p_cell
  ) then
    raise exception 'you already fired at that cell';
  end if;

  foe := case when m.player_a = me then m.player_b else m.player_a end;
  select ships into foe_ships
  from public.arcade_battleship_fleets
  where match_id = p_match and user_id = foe;
  if foe_ships is null then raise exception 'opponent has no fleet'; end if;

  select coalesce(array_agg(cell), '{}') into my_shots
  from public.arcade_battleship_shots
  where match_id = p_match and shooter_id = me;
  my_shots := my_shots || p_cell;

  for ship in select * from jsonb_array_elements(foe_ships) loop
    if (ship->'cells') @> to_jsonb(p_cell) then
      hit_ship := ship;
      exit;
    end if;
  end loop;

  if hit_ship is not null then
    outcome := 'hit';
    select array_agg(v::int) into ship_cells
    from jsonb_array_elements_text(hit_ship->'cells') v;
    if (select count(*) from unnest(ship_cells) c where c = any(my_shots))
       = array_length(ship_cells, 1) then
      outcome := 'sunk';
      sunk_id := hit_ship->>'id';
    end if;
  end if;

  insert into public.arcade_battleship_shots (match_id, shooter_id, cell, result)
  values (p_match, me, p_cell, outcome);

  select count(*) into left_afloat
  from (
    select v::int as c
    from jsonb_array_elements(foe_ships) s,
         jsonb_array_elements_text(s->'cells') v
  ) t
  where t.c <> all(my_shots);

  if left_afloat = 0 then
    update public.arcade_matches
    set status = 'finished', winner_id = me, turn_user_id = null, updated_at = now()
    where id = p_match;
  else
    update public.arcade_matches
    set turn_user_id = foe, updated_at = now()
    where id = p_match;
  end if;

  return jsonb_build_object(
    'result', outcome,
    'sunk', sunk_id,
    'won', left_afloat = 0,
    'cell', p_cell
  );
end;
$$;

-- Your matches with the opponent's display name attached (SECURITY DEFINER
-- only to reach auth.users for names, exactly as arcade_leaderboard does).
create or replace function public.arcade_bs_my_matches()
returns table (
  match_id uuid,
  opponent_id uuid,
  opponent_name text,
  status text,
  i_am_challenger boolean,
  my_turn boolean,
  i_won boolean,
  updated_at timestamptz
)
language sql
stable
security definer
set search_path = public
as $$
  select
    m.id,
    case when m.player_a = auth.uid() then m.player_b else m.player_a end,
    coalesce(
      u.raw_user_meta_data ->> 'full_name',
      u.raw_user_meta_data ->> 'name',
      split_part(u.email, '@', 1)
    ),
    m.status,
    m.player_a = auth.uid(),
    -- coalesce, not a bare comparison: turn_user_id and winner_id are NULL for
    -- finished and unaccepted matches, and NULL would both reach the client as
    -- a null boolean and — because DESC sorts NULLs first — float finished
    -- games above the ones actually waiting on you.
    coalesce(m.turn_user_id = auth.uid(), false),
    coalesce(m.winner_id = auth.uid(), false),
    m.updated_at
  from public.arcade_matches m
  join auth.users u
    on u.id = case when m.player_a = auth.uid() then m.player_b else m.player_a end
  where auth.uid() in (m.player_a, m.player_b)
    and m.status <> 'declined'
  order by coalesce(m.turn_user_id = auth.uid(), false) desc, m.updated_at desc
  limit 50;
$$;

-- Everything one board needs, in a single round trip: the client polls this.
--
-- Returns the caller's OWN fleet and both players' shots. It must never return
-- the opponent's fleet — that is the one field that would break the game, so
-- the select list is written out explicitly rather than dumping a row.
create or replace function public.arcade_bs_match_detail(p_match uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  m public.arcade_matches;
  foe uuid;
begin
  select * into m from public.arcade_matches where id = p_match;
  if not found then raise exception 'no such match'; end if;
  if me is null or me not in (m.player_a, m.player_b) then
    raise exception 'not a participant';
  end if;
  foe := case when m.player_a = me then m.player_b else m.player_a end;

  return jsonb_build_object(
    'match_id', m.id,
    'status', m.status,
    'my_turn', coalesce(m.turn_user_id = me, false),
    'i_won', coalesce(m.winner_id = me, false),
    'finished', m.status = 'finished',
    'i_am_challenger', m.player_a = me,
    'opponent_id', foe,
    'opponent_name', (
      select coalesce(
        u.raw_user_meta_data ->> 'full_name',
        u.raw_user_meta_data ->> 'name',
        split_part(u.email, '@', 1)
      ) from auth.users u where u.id = foe
    ),
    'my_fleet', coalesce(
      (select f.ships from public.arcade_battleship_fleets f
       where f.match_id = p_match and f.user_id = me),
      '[]'::jsonb
    ),
    'my_shots', coalesce((
      select jsonb_agg(jsonb_build_object('cell', s.cell, 'result', s.result) order by s.created_at)
      from public.arcade_battleship_shots s
      where s.match_id = p_match and s.shooter_id = me
    ), '[]'::jsonb),
    'their_shots', coalesce((
      select jsonb_agg(jsonb_build_object('cell', s.cell, 'result', s.result) order by s.created_at)
      from public.arcade_battleship_shots s
      where s.match_id = p_match and s.shooter_id = foe
    ), '[]'::jsonb),
    -- Activity, so "waiting on them" can say HOW LONG and whether they are even
    -- at their desk. Without this the board is indistinguishable from a broken
    -- refresh: nothing changes and there is no way to tell that is the truth.
    'updated_at', m.updated_at,
    'their_last_shot_at', (
      select max(s.created_at) from public.arcade_battleship_shots s
      where s.match_id = p_match and s.shooter_id = foe
    ),
    -- Last time the opponent did anything anywhere in the Arcade. Scoped to
    -- Arcade activity on purpose — it answers "are they around?" without
    -- turning into general presence tracking.
    'opponent_last_seen', (
      select max(sc.created_at) from public.arcade_scores sc where sc.user_id = foe
    )
  );
end;
$$;

-- Who you can challenge: anyone who has ever touched the Arcade.
create or replace function public.arcade_players(p_limit integer default 50)
returns table (user_id uuid, display_name text)
language sql
stable
security definer
set search_path = public
as $$
  select distinct
    u.id,
    coalesce(
      u.raw_user_meta_data ->> 'full_name',
      u.raw_user_meta_data ->> 'name',
      split_part(u.email, '@', 1)
    )
  from public.arcade_scores s
  join auth.users u on u.id = s.user_id
  where s.user_id <> auth.uid()
  order by 2
  limit least(greatest(coalesce(p_limit, 50), 1), 200);
$$;

-- Win/loss standings. Battleship has no score, so it gets its own board rather
-- than being forced through arcade_leaderboard's MAX(score) shape.
--
-- SECURITY DEFINER, unlike the fleets it must never expose: this reads across
-- everyone's finished matches, which RLS deliberately hides from non-
-- participants. That is safe because a finished result — who beat whom — is
-- exactly the public part. It exposes no board, no fleet, no live match.
create or replace function public.arcade_bs_standings(p_limit integer default 20)
returns table (user_id uuid, display_name text, wins bigint, losses bigint, played bigint)
language sql
stable
security definer
set search_path = public
as $$
  with finished as (
    select player_a as uid, (winner_id = player_a) as won
    from public.arcade_matches
    where game = 'battleship' and status = 'finished'
    union all
    select player_b, (winner_id = player_b)
    from public.arcade_matches
    where game = 'battleship' and status = 'finished'
  ), tally as (
    select
      uid,
      count(*) filter (where won) as wins,
      count(*) filter (where not won) as losses,
      count(*) as played
    from finished
    group by uid
  )
  select
    t.uid,
    coalesce(
      u.raw_user_meta_data ->> 'full_name',
      u.raw_user_meta_data ->> 'name',
      split_part(u.email, '@', 1)
    ),
    t.wins,
    t.losses,
    t.played
  from tally t
  join auth.users u on u.id = t.uid
  order by t.wins desc, t.losses asc, t.played desc
  limit least(greatest(coalesce(p_limit, 20), 1), 50);
$$;

revoke all on function public.arcade_bs_challenge(uuid, jsonb) from public;
revoke all on function public.arcade_bs_accept(uuid, jsonb) from public;
revoke all on function public.arcade_bs_decline(uuid) from public;
revoke all on function public.arcade_bs_fire(uuid, integer) from public;
revoke all on function public.arcade_bs_my_matches() from public;
revoke all on function public.arcade_bs_match_detail(uuid) from public;
revoke all on function public.arcade_players(integer) from public;
revoke all on function public.arcade_bs_standings(integer) from public;
grant execute on function public.arcade_bs_challenge(uuid, jsonb) to authenticated;
grant execute on function public.arcade_bs_accept(uuid, jsonb) to authenticated;
grant execute on function public.arcade_bs_decline(uuid) to authenticated;
grant execute on function public.arcade_bs_fire(uuid, integer) to authenticated;
grant execute on function public.arcade_bs_my_matches() to authenticated;
grant execute on function public.arcade_bs_match_detail(uuid) to authenticated;
grant execute on function public.arcade_players(integer) to authenticated;
grant execute on function public.arcade_bs_standings(integer) to authenticated;
grant select on public.arcade_matches to authenticated;
grant select on public.arcade_battleship_fleets to authenticated;
grant select on public.arcade_battleship_shots to authenticated;
