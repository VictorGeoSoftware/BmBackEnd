# BmApp user tiers

BmApp access and BmWeb administration are independent authorization concepts.

- `admin_users` contains the small set of company operators allowed to use
  BmWeb and `/admin/*` endpoints.
- `granted_users` contains accounts allowed to use BmApp and stores their
  product tier.
- Admin status does not imply a PREMIUM BmApp tier, and PREMIUM never grants
  BmWeb administration rights.

## Tiers and capabilities

All existing and newly granted accounts default to `BASIC`. Existing BmApp
features remain available to both tiers.

| Tier | Additional capabilities |
|---|---|
| `BASIC` | None |
| `PREMIUM` | `COMPREHENSIVE_COMPARATOR_PDF` |

The comprehensive PDF is the first planned premium feature. Until that document
format is implemented, both tiers continue receiving the current comparator PDF.

The backend database is authoritative. Firebase custom claims are not used for
tiers, so an upgrade or downgrade takes effect on the next API request.

## API

`GET /api/v1/me/access` requires a Firebase token and an active BmApp grant. It
returns the current tier and capabilities:

```json
{
  "tier": "PREMIUM",
  "capabilities": ["COMPREHENSIVE_COMPARATOR_PDF"]
}
```

BmWeb administrators can assign a tier when creating a grant. Omitting `tier`
defaults to `BASIC`:

```http
POST /api/v1/admin/granted-users
```

```json
{
  "email": "user@example.com",
  "tier": "BASIC"
}
```

Tier changes are deliberately non-destructive:

```http
PATCH /api/v1/admin/granted-users/user%40example.com/tier
```

```json
{
  "tier": "PREMIUM"
}
```

This operation must remain separate from grant deletion, which performs a full
account-data wipe and revokes Firebase sessions.
