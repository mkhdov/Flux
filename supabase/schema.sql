-- Flux — Supabase schema, RLS, RPCs, Storage and Realtime setup.
-- Run this file once in the Supabase SQL editor (or via `supabase db push`).
-- The Android client is local-first; every row carries a client UUID and a revision.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Profiles
-- ---------------------------------------------------------------------------

create table if not exists public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    email text,
    display_name text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

drop policy if exists "Profiles are readable by owner" on public.profiles;
create policy "Profiles are readable by owner"
    on public.profiles for select to authenticated
    using (id = (select auth.uid()));

drop policy if exists "Profiles are editable by owner" on public.profiles;
create policy "Profiles are editable by owner"
    on public.profiles for update to authenticated
    using (id = (select auth.uid()))
    with check (id = (select auth.uid()));

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    insert into public.profiles (id, email, display_name)
    values (
        new.id,
        new.email,
        coalesce(new.raw_user_meta_data ->> 'name', split_part(coalesce(new.email, ''), '@', 1))
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- Calendar data
-- ---------------------------------------------------------------------------

create table if not exists public.calendars (
    id uuid primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    name text not null,
    color text not null default '#4285F4',
    visible boolean not null default true,
    archived boolean not null default false,
    is_default boolean not null default false,
    timezone text not null default 'UTC',
    default_reminder_minutes integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    revision bigint not null default 0
);

create table if not exists public.events (
    id uuid primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    calendar_id uuid not null references public.calendars (id) on delete cascade,
    title text not null default 'Untitled',
    description text,
    location text,
    start_at timestamptz not null,
    end_at timestamptz not null,
    all_day boolean not null default false,
    timezone text not null default 'UTC',
    color text,
    rrule text,
    series_id uuid,
    recurrence_parent_id uuid,
    original_start timestamptz,
    completed boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    revision bigint not null default 0
);

create table if not exists public.event_reminders (
    id uuid primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    event_id uuid not null references public.events (id) on delete cascade,
    minutes_before integer not null default 0,
    channel text not null default 'local',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    revision bigint not null default 0
);

create table if not exists public.event_exceptions (
    id uuid primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    event_id uuid not null references public.events (id) on delete cascade,
    occurrence_start timestamptz not null,
    cancelled boolean not null default false,
    override_event_id uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    revision bigint not null default 0
);

create table if not exists public.event_attachments (
    id uuid primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    event_id uuid not null references public.events (id) on delete cascade,
    file_name text not null,
    mime_type text not null default 'application/octet-stream',
    size_bytes bigint not null default 0,
    storage_path text,
    upload_status text not null default 'LOCAL',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    revision bigint not null default 0
);

create index if not exists calendars_owner_idx on public.calendars (owner_id) where deleted_at is null;
create index if not exists events_owner_start_idx on public.events (owner_id, start_at) where deleted_at is null;
create index if not exists events_calendar_idx on public.events (calendar_id) where deleted_at is null;
create index if not exists events_series_idx on public.events (series_id) where series_id is not null;
create index if not exists reminders_event_idx on public.event_reminders (event_id) where deleted_at is null;
create index if not exists exceptions_event_idx on public.event_exceptions (event_id) where deleted_at is null;
create index if not exists attachments_event_idx on public.event_attachments (event_id) where deleted_at is null;

alter table public.calendars enable row level security;
alter table public.events enable row level security;
alter table public.event_reminders enable row level security;
alter table public.event_exceptions enable row level security;
alter table public.event_attachments enable row level security;

drop policy if exists "Calendars are owned by user" on public.calendars;
create policy "Calendars are owned by user" on public.calendars
    for all to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

drop policy if exists "Events are owned by user" on public.events;
create policy "Events are owned by user" on public.events
    for all to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

drop policy if exists "Reminders are owned by user" on public.event_reminders;
create policy "Reminders are owned by user" on public.event_reminders
    for all to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

drop policy if exists "Exceptions are owned by user" on public.event_exceptions;
create policy "Exceptions are owned by user" on public.event_exceptions
    for all to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

drop policy if exists "Attachments are owned by user" on public.event_attachments;
create policy "Attachments are owned by user" on public.event_attachments
    for all to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

grant select, insert, update, delete on public.calendars to authenticated;
grant select, insert, update, delete on public.events to authenticated;
grant select, insert, update, delete on public.event_reminders to authenticated;
grant select, insert, update, delete on public.event_exceptions to authenticated;
grant select, insert, update, delete on public.event_attachments to authenticated;

-- ---------------------------------------------------------------------------
-- Sync infrastructure
-- ---------------------------------------------------------------------------

create table if not exists public.sync_changes (
    seq bigserial primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    entity_table text not null,
    entity_id uuid not null,
    operation text not null,
    payload jsonb not null,
    revision bigint not null default 0,
    operation_id uuid,
    changed_at timestamptz not null default now()
);

create index if not exists sync_changes_owner_seq_idx on public.sync_changes (owner_id, seq);
create unique index if not exists sync_changes_operation_idx on public.sync_changes (operation_id) where operation_id is not null;

create table if not exists public.sync_cursors (
    owner_id uuid not null references auth.users (id) on delete cascade,
    device_id text not null,
    cursor bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (owner_id, device_id)
);

create table if not exists public.sync_operations (
    operation_id uuid primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    result jsonb not null,
    created_at timestamptz not null default now()
);

create table if not exists public.audit_logs (
    id bigserial primary key,
    owner_id uuid not null references auth.users (id) on delete cascade,
    entity_table text not null,
    entity_id uuid not null,
    operation text not null,
    operation_id uuid,
    payload jsonb,
    created_at timestamptz not null default now()
);

create index if not exists audit_logs_owner_idx on public.audit_logs (owner_id, created_at desc);

create table if not exists public.device_installations (
    owner_id uuid not null references auth.users (id) on delete cascade,
    device_id text not null,
    device_name text,
    platform text not null default 'android',
    app_version text,
    fcm_token text,
    last_seen timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (owner_id, device_id)
);

create index if not exists device_installations_token_idx on public.device_installations (fcm_token) where fcm_token is not null;

create table if not exists public.notification_outbox (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users (id) on delete cascade,
    event_id uuid references public.events (id) on delete cascade,
    reminder_id uuid references public.event_reminders (id) on delete cascade,
    occurrence_start timestamptz not null,
    scheduled_for timestamptz not null,
    title text,
    body text,
    status text not null default 'pending',
    attempts integer not null default 0,
    last_error text,
    sent_at timestamptz,
    created_at timestamptz not null default now(),
    unique (reminder_id, occurrence_start)
);

create index if not exists notification_outbox_due_idx on public.notification_outbox (status, scheduled_for);

alter table public.sync_changes enable row level security;
alter table public.sync_cursors enable row level security;
alter table public.sync_operations enable row level security;
alter table public.audit_logs enable row level security;
alter table public.device_installations enable row level security;
alter table public.notification_outbox enable row level security;

drop policy if exists "Changes are readable by owner" on public.sync_changes;
create policy "Changes are readable by owner" on public.sync_changes
    for select to authenticated
    using (owner_id = (select auth.uid()));

drop policy if exists "Cursors are owned by user" on public.sync_cursors;
create policy "Cursors are owned by user" on public.sync_cursors
    for all to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

drop policy if exists "Installations are owned by user" on public.device_installations;
create policy "Installations are owned by user" on public.device_installations
    for all to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

grant select on public.sync_changes to authenticated;
grant select, insert, update, delete on public.sync_cursors to authenticated;
grant select, insert, update, delete on public.device_installations to authenticated;

-- sync_operations, audit_logs and notification_outbox are service-role only.
revoke all on public.sync_operations from anon, authenticated;
revoke all on public.audit_logs from anon, authenticated;
revoke all on public.notification_outbox from anon, authenticated;

-- ---------------------------------------------------------------------------
-- apply_mutation — idempotent, revision-checked mutation endpoint
-- ---------------------------------------------------------------------------

create or replace function public.apply_mutation(
    p_operation_id uuid,
    p_entity text,
    p_operation text,
    p_data jsonb,
    p_base_revision bigint
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_op_owner uuid;
    v_result jsonb;
    v_id uuid;
    v_payload jsonb;
    v_existing_revision bigint;
    v_exists boolean := false;
    v_revision bigint;
    v_set text;
    v_columns text;
begin
    if v_uid is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    if p_entity not in ('calendars', 'events', 'event_reminders', 'event_exceptions', 'event_attachments') then
        raise exception 'unsupported entity: %', p_entity using errcode = '22023';
    end if;

    if p_operation not in ('UPSERT', 'DELETE') then
        raise exception 'unsupported operation: %', p_operation using errcode = '22023';
    end if;

    if p_data is null or p_data ->> 'id' is null then
        raise exception 'payload id is required' using errcode = '22023';
    end if;

    v_id := (p_data ->> 'id')::uuid;

    select result, owner_id into v_result, v_op_owner
    from public.sync_operations
    where operation_id = p_operation_id;

    if found then
        if v_op_owner <> v_uid then
            raise exception 'operation id belongs to another user' using errcode = '42501';
        end if;
        return v_result;
    end if;

    v_payload := p_data || jsonb_build_object('owner_id', v_uid);

    if v_payload ->> 'created_at' is null then
        v_payload := jsonb_set(v_payload, '{created_at}', to_jsonb(now()), true);
    end if;
    if v_payload ->> 'updated_at' is null then
        v_payload := jsonb_set(v_payload, '{updated_at}', to_jsonb(now()), true);
    end if;

    -- Validate ownership of the parent row (security definer bypasses RLS).
    if p_entity = 'events' then
        if v_payload ->> 'calendar_id' is null or not exists (
            select 1 from public.calendars c
            where c.id = (v_payload ->> 'calendar_id')::uuid and c.owner_id = v_uid
        ) then
            raise exception 'calendar not found' using errcode = '23503';
        end if;
    elsif p_entity in ('event_reminders', 'event_exceptions', 'event_attachments') then
        if v_payload ->> 'event_id' is null or not exists (
            select 1 from public.events e
            where e.id = (v_payload ->> 'event_id')::uuid and e.owner_id = v_uid
        ) then
            raise exception 'event not found' using errcode = '23503';
        end if;
    end if;

    if p_entity = 'event_exceptions' and v_payload ->> 'override_event_id' is not null then
        if not exists (
            select 1 from public.events e
            where e.id = (v_payload ->> 'override_event_id')::uuid and e.owner_id = v_uid
        ) then
            raise exception 'override event not found' using errcode = '23503';
        end if;
    end if;

    -- Serialise concurrent mutations for the same row.
    perform pg_advisory_xact_lock(hashtextextended(v_uid::text || ':' || v_id::text, 0));

    execute format('select revision from public.%I where id = $1 and owner_id = $2', p_entity)
        into v_existing_revision
        using v_id, v_uid;
    v_exists := v_existing_revision is not null;

    if v_exists then
        if p_base_revision >= 0 and v_existing_revision <> p_base_revision then
            v_result := jsonb_build_object(
                'ok', false,
                'conflict', true,
                'revision', v_existing_revision
            );
            insert into public.sync_operations (operation_id, owner_id, result)
            values (p_operation_id, v_uid, v_result)
            on conflict (operation_id) do nothing;
            return v_result;
        end if;
        v_revision := v_existing_revision + 1;
    else
        v_revision := 0;
    end if;

    v_payload := jsonb_set(v_payload, '{revision}', to_jsonb(v_revision), true);

    if p_operation = 'DELETE' and v_payload ->> 'deleted_at' is null then
        v_payload := jsonb_set(v_payload, '{deleted_at}', to_jsonb(now()), true);
    end if;

    if v_exists then
        select string_agg(
                   format(
                       '%I = ($1->>%L)::%s',
                       c.column_name,
                       c.column_name,
                       case c.data_type
                           when 'timestamp with time zone' then 'timestamptz'
                           when 'timestamp without time zone' then 'timestamp'
                           when 'character varying' then 'text'
                           else c.data_type
                       end
                   ),
                   ', '
               )
        into v_set
        from information_schema.columns c
        where c.table_schema = 'public'
          and c.table_name = p_entity
          and c.column_name not in ('id', 'owner_id', 'created_at')
          and v_payload ? c.column_name;

        execute format('update public.%I set %s where id = $2 and owner_id = $3', p_entity, v_set)
            using v_payload, v_id, v_uid;
    else
        select string_agg(format('%I', c.column_name), ', ')
        into v_columns
        from information_schema.columns c
        where c.table_schema = 'public'
          and c.table_name = p_entity
          and v_payload ? c.column_name;

        execute format(
            'insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I, $1)',
            p_entity,
            v_columns,
            v_columns,
            p_entity
        ) using v_payload;
    end if;

    v_result := jsonb_build_object('ok', true, 'revision', v_revision);

    insert into public.sync_changes (
        owner_id, entity_table, entity_id, operation, payload, revision, operation_id
    ) values (
        v_uid, p_entity, v_id, p_operation, v_payload, v_revision, p_operation_id
    );

    insert into public.audit_logs (owner_id, entity_table, entity_id, operation, operation_id, payload)
    values (v_uid, p_entity, v_id, p_operation, p_operation_id, v_payload);

    insert into public.sync_operations (operation_id, owner_id, result)
    values (p_operation_id, v_uid, v_result)
    on conflict (operation_id) do update set result = excluded.result;

    return v_result;
end;
$$;

revoke all on function public.apply_mutation(uuid, text, text, jsonb, bigint) from public, anon;
grant execute on function public.apply_mutation(uuid, text, text, jsonb, bigint) to authenticated;

-- ---------------------------------------------------------------------------
-- pull_changes / ack_sync_cursor
-- ---------------------------------------------------------------------------

create or replace function public.pull_changes(p_cursor bigint, p_limit integer)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_limit integer := greatest(1, least(coalesce(p_limit, 200), 500));
begin
    if v_uid is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    return coalesce((
        select jsonb_agg(
            jsonb_build_object(
                'seq', c.seq,
                'entity_table', c.entity_table,
                'entity_id', c.entity_id,
                'operation', c.operation,
                'payload', c.payload,
                'revision', c.revision,
                'changed_at', c.changed_at
            ) order by c.seq
        )
        from (
            select *
            from public.sync_changes
            where owner_id = v_uid and seq > coalesce(p_cursor, 0)
            order by seq
            limit v_limit
        ) c
    ), '[]'::jsonb);
end;
$$;

revoke all on function public.pull_changes(bigint, integer) from public, anon;
grant execute on function public.pull_changes(bigint, integer) to authenticated;

create or replace function public.ack_sync_cursor(p_device_id text, p_cursor bigint)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_device_id is null or length(trim(p_device_id)) = 0 then
        raise exception 'device id is required' using errcode = '22023';
    end if;

    insert into public.sync_cursors (owner_id, device_id, cursor, updated_at)
    values (v_uid, p_device_id, greatest(0, coalesce(p_cursor, 0)), now())
    on conflict (owner_id, device_id) do update
        set cursor = greatest(public.sync_cursors.cursor, excluded.cursor),
            updated_at = now();
end;
$$;

revoke all on function public.ack_sync_cursor(text, bigint) from public, anon;
grant execute on function public.ack_sync_cursor(text, bigint) to authenticated;

-- ---------------------------------------------------------------------------
-- Optional server push helper (called by the send-reminders Edge Function)
-- One-off events only; recurring occurrences are scheduled locally on device.
-- ---------------------------------------------------------------------------

create or replace function public.enqueue_due_reminder_notifications(p_window_seconds integer default 120)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_count integer;
begin
    insert into public.notification_outbox (
        owner_id, event_id, reminder_id, occurrence_start, scheduled_for, title, body
    )
    select
        e.owner_id,
        e.id,
        r.id,
        e.start_at,
        e.start_at - make_interval(mins => greatest(0, r.minutes_before)),
        e.title,
        'Your event is coming up'
    from public.event_reminders r
    join public.events e on e.id = r.event_id
    where r.deleted_at is null
      and e.deleted_at is null
      and e.rrule is null
      and e.start_at - make_interval(mins => greatest(0, r.minutes_before))
          between now() - interval '1 minute'
              and now() + make_interval(secs => greatest(10, least(coalesce(p_window_seconds, 120), 900)))
    on conflict (reminder_id, occurrence_start) do nothing;

    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

revoke all on function public.enqueue_due_reminder_notifications(integer) from public, anon, authenticated;
grant execute on function public.enqueue_due_reminder_notifications(integer) to service_role;

-- ---------------------------------------------------------------------------
-- Storage: private attachments bucket
-- ---------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit)
values ('attachments', 'attachments', false, 52428800)
on conflict (id) do update
    set public = false,
        file_size_limit = 52428800;

drop policy if exists "Attachment objects are readable by owner" on storage.objects;
create policy "Attachment objects are readable by owner"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    );

drop policy if exists "Attachment objects are insertable by owner" on storage.objects;
create policy "Attachment objects are insertable by owner"
    on storage.objects for insert to authenticated
    with check (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    );

drop policy if exists "Attachment objects are updatable by owner" on storage.objects;
create policy "Attachment objects are updatable by owner"
    on storage.objects for update to authenticated
    using (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    )
    with check (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    );

drop policy if exists "Attachment objects are deletable by owner" on storage.objects;
create policy "Attachment objects are deletable by owner"
    on storage.objects for delete to authenticated
    using (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = (select auth.uid()::text)
    );

-- ---------------------------------------------------------------------------
-- Realtime signal for sync_changes
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
       and not exists (
           select 1
           from pg_publication_tables
           where pubname = 'supabase_realtime'
             and schemaname = 'public'
             and tablename = 'sync_changes'
       )
    then
        alter publication supabase_realtime add table public.sync_changes;
    end if;
end;
$$;
