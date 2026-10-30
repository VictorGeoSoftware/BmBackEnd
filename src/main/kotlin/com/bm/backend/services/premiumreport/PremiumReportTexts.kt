package com.bm.backend.services.premiumreport

/** Approved Spanish copy for the Premium report cover (user answers 2026-10-29). Later pages add their own objects. */
object PremiumReportTexts {
    const val TITLE = "ESTUDIO COMPLETO DE SUMINISTRO ELÉCTRICO"
    const val SUBTITLE = "Análisis de consumo y oferta económica"
    const val AUTHOR = "BM"

    const val ISSUE_DATE_LABEL = "Fecha del informe:"
    const val SUPPLY_SECTION = "DATOS DEL SUMINISTRO"

    const val HOLDER_LABEL = "Titular del suministro:"
    const val ADDRESS_LABEL = "Dirección del suministro:"
    const val CUPS_LABEL = "CUPS:"
    const val TARIFF_LABEL = "Tarifa de acceso:"
    const val DISTRIBUTOR_LABEL = "Distribuidora:"
    const val LOCATION_LABEL = "Ubicación:"
    const val CONTRACTED_POWER_LABEL = "Potencia contratada:"
    const val LAST_READING_LABEL = "Fecha de la última lectura:"

    const val SNAPSHOT_UNAVAILABLE = "No se dispone de datos adicionales del suministro."

    /** T1–T3 (spec report-tariff-applicability, approved 2026-10-29). */
    const val TARIFF_UNKNOWN = "Tarifa no reconocida para este informe: se muestran los periodos presentes en la fuente."
    fun tariffConflict(snapshot: String, tariffName: String) =
        "Las fuentes indican tarifas distintas ($snapshot / $tariffName): se muestran los periodos presentes en la fuente."
    fun tariffInconsistent(code: String) =
        "La fuente publica valores en periodos que no corresponden a la tarifa $code: se muestran los periodos presentes en la fuente."
}
