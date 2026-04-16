# Backend Project Instructions

## Architecture and Design
- Follow Clean Architecture boundaries (routes -> services/use-cases -> repositories/external adapters -> models).
- Apply SOLID principles in every new change.
- Keep business rules out of transport/infrastructure layers.

## Data Models
- Every new data model must be created in its own dedicated file.
- Avoid grouping newly introduced models into large monolithic model files.

## Change Scope
- Prefer focused, minimal changes.
- Preserve existing behavior unless explicitly required by feature needs.
