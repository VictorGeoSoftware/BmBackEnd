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
