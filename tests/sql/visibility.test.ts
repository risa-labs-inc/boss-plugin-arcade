import { PGlite } from '@electric-sql/pglite';
import { readFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { test } from 'node:test';

// Contract fixture for the external BossConsole platform. The platform's own
// suite tests org membership; this suite tests real Arcade SQL/RLS against the
// resulting visible-user set, with no Supabase connection or credentials.
async function database() {
  const db = new PGlite();
  await db.exec(`
    create role anon; create role authenticated; create role service_role bypassrls;
    create schema auth; create schema realtime; create schema extensions;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid$$;
    create table realtime.messages(extension text);
    alter table realtime.messages enable row level security;
    create function realtime.topic() returns text language sql stable as
      $$select current_setting('realtime.topic', true)$$;
    grant usage on schema public, auth, realtime to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
    alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
    alter default privileges in schema public grant execute on functions to anon, authenticated, service_role;
    create function public.org_visible_users() returns setof uuid language sql stable security definer as
      $$select unnest(string_to_array(current_setting('test.visible_users', true), ',')::uuid[])$$;
    revoke all on function public.org_visible_users() from public, anon;
    grant execute on function public.org_visible_users() to authenticated;
    create function public.user_display_name(p_user uuid) returns text language sql stable security definer as
      $$select coalesce(raw_user_meta_data->>'display_name', 'Player') from auth.users where id = p_user$$;
    revoke all on function public.user_display_name(uuid) from public, anon, authenticated;
  `);
  return db;
}

const me = '00000000-0000-0000-0000-000000000001';
const colleague = '00000000-0000-0000-0000-000000000002';
const outsider = '00000000-0000-0000-0000-000000000003';
const table = '10000000-0000-0000-0000-000000000001';

const arcadeDir = fileURLToPath(new URL('../../supabase/', import.meta.url));
for (const path of ['fresh', 'upgrade']) test(`merged Arcade visibility (${path})`, async () => {
  const db = await database();
  try {
    for (const name of ['arcade_scope.sql', 'arcade_schema.sql', 'arcade_battleship.sql']) {
      await db.exec(await readFile(resolve(arcadeDir!, name), 'utf8'));
    }
    if (path === 'upgrade') {
      // Simulate the old permissive read policy, then execute the actual upgrade.
      await db.exec(`drop policy arcade_scores_read_visible on arcade_scores;
        create policy arcade_scores_read_all on arcade_scores for select to authenticated using (true)`);
      await db.exec(await readFile(resolve(arcadeDir!, 'arcade_events_migration.sql'), 'utf8'));
    }
    // Recreated helpers receive authenticated default grants; an unexpected
    // overload must not receive the real RPC's allowlisted privilege.
    await db.exec(`grant execute on function arcade_display_name(uuid) to authenticated;
      create function arcade_players(text) returns text language sql as $$select 'internal'::text$$`);
    const audit = await readFile(process.env.ARCADE_AUDIT_PATH ?? resolve(arcadeDir!, 'arcade_grants_audit.sql'), 'utf8');
    await db.exec(audit);
    await db.exec(audit); // Running the complete file twice must work.

    await db.exec(`
      insert into auth.users(id,raw_user_meta_data) values
        ('${me}','{"display_name":"Self"}'), ('${colleague}','{"display_name":"Colleague"}'),
        ('${outsider}','{"display_name":"Outsider"}');
      insert into arcade_scores(user_id,game,score,event) values
        ('${me}','test',10,'final'), ('${colleague}','test',20,'final'), ('${outsider}','test',999,'final');
      select set_config('request.jwt.claim.sub','${me}',false);
      select set_config('test.visible_users','${me},${colleague}',false);
      set role authenticated;
    `);
    assert.equal((await db.query('select * from arcade_scores')).rows.length, 2);
    assert.deepEqual((await db.query<{user_id: string}>("select * from arcade_leaderboard('test')")).rows.map(r => r.user_id), [colleague, me]);
    assert.deepEqual((await db.query<{user_id: string}>('select * from arcade_players()')).rows.map(r => r.user_id), [colleague]);
    await assert.rejects(db.query(`select arcade_display_name('${outsider}')`), /permission denied/);
    await assert.rejects(db.query("select arcade_players('internal')"), /permission denied/);
    await assert.rejects(db.query("select arcade_bs_validate_fleet('[]'::jsonb)"), /permission denied/);
    await assert.rejects(db.query(`select arcade_may_see('${outsider}')`), /permission denied/);
    await db.exec(`select set_config('test.visible_users','${me}',false)`);
    assert.equal((await db.query('select * from arcade_players()')).rows.length, 0);
    await db.exec('reset role; set role anon;');
    await assert.rejects(db.query("select * from arcade_leaderboard('test')"), /permission denied/);
    await assert.rejects(db.query('select * from arcade_scores'), /permission denied/);
  } finally { await db.close(); }
});


for (const failure of ['missing RPC', 'inherited internal access']) test(`audit aborts on ${failure}`, async () => {
  const db = await database();
  try {
    for (const name of ['arcade_scope.sql', 'arcade_schema.sql', 'arcade_battleship.sql'])
      await db.exec(await readFile(resolve(arcadeDir, name), 'utf8'));
    if (failure === 'missing RPC') {
      await db.exec('drop function arcade_players(integer)');
    } else {
      await db.exec(`create role inherited_reader; grant inherited_reader to authenticated;
        grant execute on function arcade_display_name(uuid) to inherited_reader`);
    }
    await assert.rejects(db.exec(await readFile(resolve(arcadeDir, 'arcade_grants_audit.sql'), 'utf8')),
      /Arcade grant audit failed/);
    await db.exec('rollback');
  } finally { await db.close(); }
});
