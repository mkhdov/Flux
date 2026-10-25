# Flux

Flux is a native Android calendar built around a fast daily timeline and an offline-first
data model. Everything works without an account; signing in adds multi-device sync,
attachments and optional push delivery.

## Highlights

- **Daily timeline** with scroll, fling, pinch-to-zoom, drag-to-move and drag-to-resize.
- **Weekly strip** and a **month grid** with event bars and drag-to-date.
- **Quick add** (“Dentist tomorrow 3pm”, “Lunch Fri 12:30-13:30”).
- Recurring events with **this / this and future / all** editing and deletion scopes.
- Reminders, calendar default reminders, completed events and per-event colors.
- Attachments up to 50 MB each (10 per event) with resumable uploads.
- Home-screen agenda widget with transparent, white and dark styles.
- Local-first Room database, persistent outbox, revision checks and conflict review.
- Explicit **local-only** mode that never requires a server.

## Requirements

- Android Studio / Android SDK with **compileSdk 37**, JDK 17+ (JDK 25 is used in CI).
- Device or emulator running Android 8.0+ (`minSdk 26`).
- Optional: a Supabase project and a Firebase project for sync and push.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Backend configuration

Set these Gradle properties (for example in `~/.gradle/gradle.properties`, not in git):

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<anon-key>

FIREBASE_APPLICATION_ID=1:1234567890:android:abcdef
FIREBASE_API_KEY=<firebase-api-key>
FIREBASE_PROJECT_ID=<firebase-project-id>
FIREBASE_SENDER_ID=1234567890
```

Without Supabase values the app still builds and runs in local-only mode. Without Firebase
values server push stays disabled; on-device alarms continue to work.

## Supabase setup

1. Create a Supabase project.
2. Open the SQL editor and run [`supabase/schema.sql`](supabase/schema.sql).
   It creates the tables, indexes, RLS policies, `apply_mutation` / `pull_changes` /
   `ack_sync_cursor` RPCs, the private `attachments` bucket and its storage policies, and
   adds `sync_changes` to the Realtime publication.
3. Create users from **Authentication → Users**. Flux has no sign-up screen; accounts are
   admin-provisioned. Disable public sign-ups in the Auth settings.
4. Optionally deploy the reminder function:

   ```bash
   supabase functions deploy send-reminders --no-verify-jwt
   supabase secrets set \
     SUPABASE_URL=https://<project-ref>.supabase.co \
     SUPABASE_SERVICE_ROLE_KEY=<service-role-key> \
     FCM_PROJECT_ID=<firebase-project-id> \
     GOOGLE_SERVICE_ACCOUNT_JSON='<service-account-json>' \
     CRON_SECRET=<random-secret>
   ```

5. To run it every minute, enable `pg_cron` and `pg_net`, store the project URL and cron
   secret in Vault, and schedule an HTTP POST to `/functions/v1/send-reminders` with the
   `x-cron-secret` header. Keep the service-role key server-side; never ship it in the app.

`send-reminders` currently expands reminders for **one-off events**. Recurring occurrences
are scheduled on the device, which remains the primary reminder path.

## Architecture

```text
app/src/main/java/com/mkhdov/flux/
  data/local/        Room entities, DAOs, AppDatabase
  data/model/        DaySnapshot, EventInstance
  data/remote/       SupabaseApi, RemoteMapper
  data/repo/         AuthManager, CalendarRepository
  sync/              SyncEngine, SyncWorker, RealtimeClient, SyncStatus
  notifications/     scheduling, receivers, FCM service
  attachments/       local copies, resumable upload worker
  ui/                main timeline, month view, editor, calendars, settings, auth
  widget/            agenda widget, configuration, update manager
  util/              time, recurrence, quick-add parsing, hashing
```

- Local Room is the source of truth; the UI never waits for the network.
- Events use client-generated UUIDs. Every table carries `revision` and soft-delete
  `deleted_at`.
- Mutations are appended to a persistent `outbox`; `SyncEngine` pushes them through
  `apply_mutation`, pulls changes through `pull_changes`, and acknowledges the cursor.
- `apply_mutation` is idempotent per operation id, serialises writes per row, enforces
  parent ownership and returns `{ok, revision}` or `{ok:false, conflict:true, revision}`.
- Conflicts are surfaced in Sync Status; “keep local” re-queues the change with a negative
  base revision, which bypasses the revision check.
- Realtime is only a signal to run a normal cursor pull.
- Attachments are copied locally first; uploads use the Supabase TUS endpoint and resume
  after interruption. The bucket is private and paths are scoped to the user id.

## Editing scopes

For a repeating event the editor asks whether to change **this event**, **this and future
events**, or **all events in the series**. Overrides are stored as a separate event plus an
`event_exceptions` row; deleting one occurrence records a cancelled exception. Reminder
lists on overrides are copied only when the caller does not supply a list, so clearing all
reminders is preserved.

## Tests

Unit tests cover quick-add parsing (dates, weekday abbreviations, ranges, overnight ranges,
durations) and recurrence-rule helpers. Run them with:

```bash
./gradlew :app:testDebugUnitTest
```

The Supabase schema has been exercised against PostgreSQL 16 with stubbed `auth`/`storage`
schemas: insert, update, stale-revision conflict, forced update, idempotent retry, soft
delete and reminder enqueueing.

## Known limitations

- No real Supabase/Firebase credentials ship with this repository, so sync, push and
  storage are not verified end-to-end here.
- Server push only expands non-recurring events; recurring reminders are local.
- There is no in-app account creation or password reset UI.
- Widget refresh is best-effort; Android controls exact update timing.
