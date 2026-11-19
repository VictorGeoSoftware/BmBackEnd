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
