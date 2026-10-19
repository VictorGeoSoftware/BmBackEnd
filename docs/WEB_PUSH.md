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

## Not done yet

Stored tokens are **not** subscribed to `price_updates` / `force_logout`, and
nothing is sent to them. That is the `web-push-price-updates` feature in
PowerAppWeb, which also adds the service worker `push` handler (without it,
browsers show a generic notification and iOS revokes the subscription).
