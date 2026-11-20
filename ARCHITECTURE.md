# Backend architecture notes

## Enséñame — synthetic QA activation pilot

`teachMeAccessRoutes` owns Firebase identity, current admin access and nomination.
Its shared workbook upload pipeline owns admission, connection cancellation and the
request deadline. An optional internal `TeachSkillActivationService` mounts the
activation route and additional preview wire only with the existing QA guard and
preview opt-ins. The regular application never supplies this capability.

`TeachSkillActivationService` owns the exact V2 registry, server preview evidence
and confirmation rules. `TeachActivationStore` is its persistence boundary; it is
not a proposals, import or pricing repository. The only implementation and
composition are in `src/test`. A disposable PostgreSQL fixture provisions the
identity/manifest and two separately permissioned test databases. Only its QA
writer is accessible from the test Netty server; the fixture privately owns the
simulated PROD sentinel. No operational datasource or Flyway wiring participates.

The authorization recheck happens outside SQL. Row/uniqueness constraints implement
compare-and-swap, and a single transaction ties the pointer change to its event.
No-op confirmations do not write. An independent reader can observe only a
committed pointer/event pair; a closed HTTP connection is not a rollback signal.

This catalog has no consumer in regular Excel or PDF import and no promotion or
hosted-release integration. See [constraints](CONSTRAINTS.md) and [verification](README.md#enséñame-synthetic-qa-activation-harness).

## Enséñame — synthetic supplier selection

An independently optional `TeachSupplierSkillSelectionService` joins the same QA
guard and upload pipeline, without operational composition. It combines the
declared basename with a four-coordinate literal/visibility projection in the
bounded workbook inspector. Only a unique identity reaches `TeachActiveSkillReader`,
whose fixture-only JDBC implementation verifies the store identity and reads the
supplier pointer within one read-only repeatable-read snapshot. The service
resolves that pointer against the exact entry in `TeachActivationRegistry`. It
does not call activation, extract a proposal, or apply a revision.

The QA reader is a separate login from the activation writer and receives neither
an audit capability nor a sentinel datasource. It runs outside Netty's event loop
under the shared deadline/admission and closes SQL resources before releasing the
upload permit. The restart test reconstructs the reader and registry instead of
relying on a prior pointer, receipt, or key.

## Enséñame — synthetic Excel proposal imports

The optional import capability uses the existing QA guard, four authorization
workers and four upload permits for POST and list GET. It passes the exact
manifest-bound selection to the price applicator without retargeting. The
import-only projection checks the synthetic table and optional identity sheet;
V2 preview and activation still require their unchanged teaching pins.

`TeachProposalImportStore` is independent of the catalog, PDF and the operational
pricing repository. PostgreSQL composition and provisioning live only in `src/test`.
The disposable proposal database has an append-only writer and a separate list
login without access to the private attempt key or fingerprint. Each access
verifies database, role, store identity, catalog identity, manifest, QA environment
and schema version. The catalog and simulated PROD sentinel remain isolated.

After preparation, the guard checks the same UID again before storage. A unique
actor/attempt claim serializes duplicate writers. One transaction appends the batch
and three cited proposal rows with exact SQL decimals, or the whole write rolls
back. Committed attempts replay without selection; an independent read-only
snapshot lists only the authenticated actor's committed batches.

## Enséñame — validate a committed synthetic QA import

`TeachProposalValidationService` mounts only with the same verified import
capability and the schema-v2 proposals descriptor. The shared QA route verifies
the current principal before reading JSON and rechecks the same token and UID
immediately before confirmation or replay. It uses the existing admission,
deadline and bounded IO dispatcher. No application startup supplies the service.

`TeachProposalValidationStore` is an append/replay boundary, not a catalog reader.
Its test-only adapter verifies the writer and store binding on every connection.
It resolves the immutable committed batch by UUID and verified UID, then inserts
an event with a composite foreign key. Uniqueness serializes confirmations; a
fresh READ COMMITTED statement can read a competing transaction's original
event. The existing list reader joins actor and reference in the same read-only
snapshot only for the explicit enriched projection. Neither path retries against
another store or uses the active revision; the ordinary list wire is unchanged.

## Enséñame — simulated promotion of an exact QA revision

The fixture binds `TeachProductionPromotionService` to the same validation/import
composition and immutable V2 registry, plus a separately attested store and
ephemeral fake identity. The SQL join proves committed evidence for the current
UID; the active pointer is not consulted. The canonical artifact is an explicit,
value-checked data projection of the exact registry entry, not serialization of
the entry or projection from imported proposals.

`TeachPromotionStore` owns scoped read, reservation and finalization; the
fixture implements them with limited PostgreSQL roles. The duplicate composite
uniqueness on the validation event is provisioned only in the promotion fixture
so the new audit's composite FK also enforces ownership. `TeachPromotionProvider`
offers only exact lookup and atomic create-if-absent. Reauthorization and fake
I/O occur outside SQL. Separate transactions commit the reservation and CAS
finalization; the fake retains its state across Netty recomposition. A read or
an already-open replay does not contact the fake. Before disclosing any opened
receipt (GET, replay or new completion), the route rechecks the same token,
UID, admin status and nomination after SQL has closed. A denial does not
undo a completed reservation, fake effect or finalization.

## Enséñame — consume an exact release in the production analogue

This vertical is composed only in `src/test`. A separate loopback Netty server
accepts analogue import and list; it receives neither a QA pointer nor a PR
provider. It uses a fresh in-memory TLS identity; Pages checks the injected leaf
pin on both the credential-free preflight and the socket carrying the request.
A fixture-only HTTP/1 decoder rejects conflicting or noncanonical framing
before it can normalize headers or admit a body. The separate deployment
controller checks the simulated protected-base merge and the in-process
executor's success for its own attempt and destination.
Only its deploy role can append a manifest, under a supplier transaction lock.
Ordinal is assigned at deployment start; replay of the same committed event is
read-only, and a late lower ordinal cannot displace the head.

A resolver with an independent immutable copy of the approved synthetic V2 pins
reads the newest committed success before checking integrity. The canonical
promotion bytes are evidence, not executable JSON. It verifies the complete
artifact tuple and SHA-256 against the exact runtime revision, applies the
existing bounded deterministic rule, and never falls back to history or QA.
The actor-scoped writer atomically persists the batch and three rows with a
foreign key to that pinned success. A separate list role reads only that actor's
committed rows; replays recover the original receipt even after supersession.
The disposable PostgreSQL database and all four roles are separate from QA,
the protected sentinel and the PDF store.
