# Web Push (PowerAppWeb PWA)

## Why the backend stores web tokens

BmApp (Android) receives price updates and force logouts through FCM **topics**:
the device calls `subscribeToTopic("price_updates")` and
`subscribeToTopic("force_logout")` itself, and the backend sends to those topics
(`FirebasePriceUpdatesNotifier`, `FirebaseForceLogoutNotifier`). It never sends
its token to the backend.

The FCM JS SDK used by PowerAppWeb **cannot subscribe to topics from the
browser**. A web token can only join a topic from the server, using the Admin SDK
`subscribeToTopic(tokens, topic)`. So the PWA registers its token here, and the
backend keeps it.

## API (granted users only, `requireGrantedFirebaseUser`)

| Method | Path | Body | Behaviour |
| --- | --- | --- | --- |
| `POST` | `/api/v1/push/web-tokens` | `{"token": String (1..4096), "platform": "ios" \| "android" \| "desktop"}` | Upserts by token, setting `uid`/`email` from the ID token (never the body) and `platform`. A token that moves to another account is reassigned. `200 {"success":true}`; `400` on a blank/too-long token or unknown platform. |
| `DELETE` | `/api/v1/push/web-tokens` | `{"token": String}` | Deletes the row only if it belongs to the caller's uid. `200` even when nothing was deleted. |

The PWA calls `DELETE` on manual logout. A forced logout makes no call; the
grant deletion wipe (`GrantedUsersService.deleteGrant`) removes every token of
the account by email, in the same transaction as the other user tables.

## Storage

`web_push_tokens` (migration `V14__web_push_tokens.sql`): `token` unique, `uid`,
`email` (indexed), `platform`, `created_at`, `updated_at`.

Tokens are never logged in full: only the first 12 characters.

## Delivery (`price_updates`)

`WebPushPriceUpdatesNotifier` sends every price update to **all stored tokens**
with `sendEachForMulticast` (chunks of 500). It sits next to the unchanged
Android topic notifier inside `CompositePriceUpdatesNotifier`, behind the same
`DebouncingPriceUpdatesNotifier`; a failing channel never blocks the other.

- **Multicast, not topics.** `web_push_tokens` is the single source of truth: a
  deleted row (logout, grant wipe) stops delivery at once, with no topic
  subscriptions to keep in sync.
- **Data-only.** Data `type=price_updates`, `payload=<PriceUpdatesNotification
  JSON>` (same as Android), `WebpushConfig` headers `Urgency: high`,
  `TTL: 86400`, no `notification` block. The PWA service worker (`/sw.js`)
  always shows its own notification ("Tarifas actualizadas", opens
  `/proposals`): browsers require a visible notification per push (iOS revokes
  silent subscriptions; Chrome shows a generic one).
- **Pruning.** Tokens whose result is `UNREGISTERED`, `SENDER_ID_MISMATCH` or
  `INVALID_ARGUMENT` are deleted. Other errors (`UNAVAILABLE`, `INTERNAL`,
  `QUOTA_EXCEEDED`) keep the row. Logs show counts and masked tokens only.
- No tokens → no FCM web call.

`force_logout` is **not** sent to web tokens: the grant deletion wipes them
before the force-logout send. The PWA re-checks `/user-data` on tab return.
