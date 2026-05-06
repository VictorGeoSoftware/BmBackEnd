package com.bm.backend.testing

import com.bm.backend.models.ExtractedTables
import com.bm.backend.models.PriceTableResponse
import com.bm.backend.models.PriceTableResult
import com.bm.backend.models.TablaPrecioClasicaBase
import com.bm.backend.models.TablaPrecioClasicaUnica
import com.bm.backend.models.TablaPrecioPotencia
import com.bm.backend.models.TarifaRow
import com.bm.backend.models.TerminoDeEnergia
import com.bm.backend.models.TerminoDePotencia

/**
 * Test fixtures for building valid [PriceTableResponse] payloads.
 *
 * Values mirror real bills/proposals under `BM/Example bills` and `BM/Price proposals`
 * (€/kWh and €/kW·día magnitudes), keeping the structure the production
 * pipeline produces.
 */
object PriceTableFixtures {

    fun tarifa(
        name: String = "2.0TD",
        potenciaContratada: String? = "<=15 kW",
        p1: Double? = 0.123456,
        p2: Double? = 0.023456,
        p3: Double? = 0.012345,
        p4: Double? = null,
        p5: Double? = null,
        p6: Double? = null
    ): TarifaRow = TarifaRow(
        tarifa = name,
        potencia_contratada = potenciaContratada,
        P1 = p1,
        P2 = p2,
        P3 = p3,
        P4 = p4,
        P5 = p5,
        P6 = p6
    )

    fun result(
        fileName: String = "iberdrola-2024.pdf",
        companyName: String = "Iberdrola",
        tarifaName: String = "2.0TD",
        // €/kWh values — service.normalizeEnergyUnitsToEuroPerKwh would no-op
        // because they are already <= 2.0
        energyP1: Double = 0.180000,
        energyP2: Double = 0.090000,
        energyP3: Double = 0.060000
    ): PriceTableResult = PriceTableResult(
        fileName = fileName,
        extracted_tables = ExtractedTables(
            companyName = companyName,
            termino_de_potencia = TerminoDePotencia(
                titulo = "Término de potencia (€/kW·día)",
                tabla_precio_potencia = TablaPrecioPotencia(
                    titulo = "Precios de potencia",
                    tarifas = listOf(tarifa(name = tarifaName))
                )
            ),
            termino_de_energia = TerminoDeEnergia(
                titulo = "Término de energía (€/kWh)",
                tabla_precio_clasica_base = TablaPrecioClasicaBase(
                    titulo = "Precio clásica base (€/kWh)",
                    tarifas = listOf(
                        tarifa(
                            name = tarifaName,
                            p1 = energyP1,
                            p2 = energyP2,
                            p3 = energyP3
                        )
                    )
                ),
                tabla_precio_clasica_unica = TablaPrecioClasicaUnica(
                    titulo = "Precio clásica única (€/kWh)",
                    tarifas = listOf(
                        tarifa(
                            name = tarifaName,
                            p1 = energyP1,
                            p2 = energyP2,
                            p3 = energyP3
                        )
                    )
                )
            )
        )
    )

    fun response(vararg results: PriceTableResult): PriceTableResponse =
        PriceTableResponse(success = true, results = results.toList())
}
