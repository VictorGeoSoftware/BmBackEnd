package com.bm.backend.services

import com.bm.backend.models.TeachProposalValidationConfirmation
import com.bm.backend.models.TeachProposalImportIdentity
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import com.bm.backend.repositories.ports.TeachProposalValidationStore
import com.bm.backend.repositories.ports.TeachProposalValidationDeadline

// Test compositions use synthetic verifier UIDs. Syntax is not authorization: the guard and matching batch UID are mandatory.
private val syntheticValidationActor = Regex("synthetic-[A-Za-z0-9-]{1,54}")

internal class TeachProposalValidationService private constructor(
    private val imports: TeachProposalImportService,
    private val store: TeachProposalValidationStore,
) {
    companion object {
        fun mount(imports: TeachProposalImportService, store: TeachProposalValidationStore): TeachProposalValidationService? {
            if (store.identity != imports.identity || store.identity.schemaVersion != 2) return null
            return try {
                imports.verifyValidationProjection()
                store.verify()
                TeachProposalValidationService(imports, store)
            } catch (_: TeachProposalStoreUnavailable) { null }
              catch (_: TeachProposalValidationDeadline) { null }
        }
    }

    fun isBoundTo(service: TeachProposalImportService?) = imports === service
    fun isBoundTo(registry: TeachActivationRegistry, identity: TeachProposalImportIdentity) =
        imports.isBoundTo(registry) && store.identity == identity && identity.schemaVersion == 2

    fun acceptsActor(uid: String) = syntheticValidationActor.matches(uid)

    fun confirm(actor: String, confirmation: TeachProposalValidationConfirmation, remaining: () -> Long, checkpoint: () -> Unit) =
        store.confirm(actor, confirmation.ref, remaining, checkpoint)
}
