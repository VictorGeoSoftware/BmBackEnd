package com.bm.backend.testing

import com.bm.backend.models.TeachRegisteredSkillRevision
import com.bm.backend.services.projectTeachPromotion

internal class ProdAnalogCandidate(val exact: TeachRegisteredSkillRevision) {
    val canonical = projectTeachPromotion(exact, exact.manifest)
}
