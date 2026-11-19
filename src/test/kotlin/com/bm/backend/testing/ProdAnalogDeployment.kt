package com.bm.backend.testing

import java.util.UUID

internal class ProdAnalogDeployment(val id: UUID, val ordinal: Long, val binding: ProdAnalogBinding,
                                    val target: String, val candidate: ProdAnalogCandidate)
