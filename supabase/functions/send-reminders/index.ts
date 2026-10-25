// Flux — optional server-side reminder delivery through Firebase Cloud Messaging.
//
// Deploy with:
//   supabase functions deploy send-reminders --no-verify-jwt
//
// Required secrets:
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY,
//   FCM_PROJECT_ID, GOOGLE_SERVICE_ACCOUNT_JSON
// Optional:
//   CRON_SECRET (send the same value in the x-cron-secret header)

import { createClient } from "npm:@supabase/supabase-js@2";
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const JSON_HEADERS = { "Content-Type": "application/json" };

let cachedToken: { value: string; expiresAt: number } | null = null;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: { ...JSON_HEADERS } });
  }

  const cronSecret = Deno.env.get("CRON_SECRET");
  if (cronSecret && req.headers.get("x-cron-secret") !== cronSecret) {
    return json({ error: "unauthorized" }, 401);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const projectId = Deno.env.get("FCM_PROJECT_ID");
  const serviceAccountJson = Deno.env.get("GOOGLE_SERVICE_ACCOUNT_JSON");

  if (!supabaseUrl || !serviceKey) {
    return json({ error: "SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required" }, 500);
  }
  if (!projectId || !serviceAccountJson) {
    return json({ error: "FCM_PROJECT_ID and GOOGLE_SERVICE_ACCOUNT_JSON are required" }, 503);
  }

  const supabase = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { error: enqueueError } = await supabase.rpc("enqueue_due_reminder_notifications", {
    p_window_seconds: 120,
  });
  if (enqueueError) {
    return json({ error: `enqueue failed: ${enqueueError.message}` }, 500);
  }

  const now = new Date().toISOString();
  const { data: rows, error: outboxError } = await supabase
    .from("notification_outbox")
    .select("id,owner_id,event_id,reminder_id,occurrence_start,title,body,attempts")
    .eq("status", "pending")
    .lte("scheduled_for", now)
    .order("scheduled_for", { ascending: true })
    .limit(200);

  if (outboxError) {
    return json({ error: `outbox query failed: ${outboxError.message}` }, 500);
  }
  if (!rows || rows.length === 0) {
    return json({ enqueued: 0, sent: 0, failed: 0, skipped: 0 });
  }

  const ownerIds = [...new Set(rows.map((row) => row.owner_id))];
  const { data: devices, error: deviceError } = await supabase
    .from("device_installations")
    .select("owner_id,fcm_token")
    .in("owner_id", ownerIds)
    .not("fcm_token", "is", null);

  if (deviceError) {
    return json({ error: `device query failed: ${deviceError.message}` }, 500);
  }

  const tokensByOwner = new Map<string, string[]>();
  for (const device of devices ?? []) {
    if (!device.fcm_token) continue;
    const list = tokensByOwner.get(device.owner_id) ?? [];
    list.push(device.fcm_token);
    tokensByOwner.set(device.owner_id, list);
  }

  let accessToken: string;
  try {
    accessToken = await googleAccessToken(serviceAccountJson);
  } catch (error) {
    return json({ error: `google auth failed: ${error instanceof Error ? error.message : error}` }, 500);
  }

  let sent = 0;
  let failed = 0;
  let skipped = 0;
  const staleTokens = new Set<string>();

  for (const row of rows) {
    const tokens = tokensByOwner.get(row.owner_id) ?? [];
    if (tokens.length === 0) {
      skipped++;
      await markRow(supabase, row.id, "skipped", row.attempts + 1, "No registered devices");
      continue;
    }

    let rowSent = false;
    const errors: string[] = [];

    for (const token of tokens) {
      if (staleTokens.has(token)) continue;
      const response = await sendFcm(projectId, accessToken, token, row);
      if (response.ok) {
        rowSent = true;
        continue;
      }

      const text = await response.text();
      if (response.status === 404 || text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT")) {
        staleTokens.add(token);
        await supabase
          .from("device_installations")
          .update({ fcm_token: null, updated_at: new Date().toISOString() })
          .eq("fcm_token", token);
      }
      errors.push(`${response.status}: ${text.slice(0, 300)}`);
    }

    if (rowSent) {
      sent++;
      await markRow(supabase, row.id, "sent", row.attempts + 1, errors.length ? errors.join(" | ") : null, true);
    } else {
      failed++;
      await markRow(supabase, row.id, "failed", row.attempts + 1, errors.join(" | ") || "Delivery failed");
    }
  }

  return json({ enqueued: rows.length, sent, failed, skipped });
});

async function markRow(
  supabase: SupabaseClient,
  id: string,
  status: string,
  attempts: number,
  lastError: string | null,
  sentAt = false,
): Promise<void> {
  await supabase
    .from("notification_outbox")
    .update({
      status,
      attempts,
      last_error: lastError,
      sent_at: sentAt ? new Date().toISOString() : null,
    })
    .eq("id", id);
}

async function sendFcm(
  projectId: string,
  accessToken: string,
  token: string,
  row: {
    event_id: string | null;
    reminder_id: string | null;
    occurrence_start: string;
    title: string | null;
    body: string | null;
  },
): Promise<Response> {
  const occurrence = new Date(row.occurrence_start).getTime();
  return await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST",
    headers: {
      ...JSON_HEADERS,
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify({
      message: {
        token,
        data: {
          event_id: row.event_id ?? "",
          reminder_id: row.reminder_id ?? "",
          occurrence_start: String(Number.isFinite(occurrence) ? occurrence : 0),
          title: row.title ?? "Flux",
          body: row.body ?? "Upcoming event",
        },
        android: { priority: "high" },
      },
    }),
  });
}

async function googleAccessToken(serviceAccountJson: string): Promise<string> {
  if (cachedToken && cachedToken.expiresAt > Date.now() + 60_000) {
    return cachedToken.value;
  }

  const account = JSON.parse(serviceAccountJson) as {
    client_email: string;
    private_key: string;
    token_uri?: string;
  };
  const tokenUri = account.token_uri ?? "https://oauth2.googleapis.com/token";
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${
    b64url(JSON.stringify({
      iss: account.client_email,
      scope: FCM_SCOPE,
      aud: tokenUri,
      iat: now,
      exp: now + 3600,
    }))
  }`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToBytes(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const assertion = `${unsigned}.${b64url(new Uint8Array(signature))}`;

  const response = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`token endpoint returned ${response.status}: ${await response.text()}`);
  }

  const payload = await response.json() as { access_token: string; expires_in: number };
  cachedToken = {
    value: payload.access_token,
    expiresAt: Date.now() + Math.max(60, payload.expires_in - 60) * 1000,
  };
  return cachedToken.value;
}

function pemToBytes(pem: string): Uint8Array {
  const base64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function b64url(value: string | Uint8Array): string {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}
