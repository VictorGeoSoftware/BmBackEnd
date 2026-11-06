package com.bm.backend.services.premiumreport

/**
 * Approved copy of the closing information/maintenance page (feedback 2026-11-03): the broker's client-loyalty page
 * rebranded to PowerApp, advice items ordered 1–7, without web address or tagline. It replaces the data-protection
 * page (user decision). `**…**` marks the reference's bold emphasis; [plain] strips it.
 */
object PremiumInformationTexts {
    const val TITLE = "INFORMACIÓN Y MANTENIMIENTO DE SU PÓLIZA ELÉCTRICA"

    val INTRO: List<String> = listOf(
        "Desde PowerApp queremos agradecerle de antemano la confianza y la atención depositada en nosotros.",
        "Nuestra filosofía es construir relaciones a largo plazo basadas en la franqueza y profesionalidad, pudiendo " +
            "solventar de esta forma todas sus necesidades relacionadas con el ámbito energético."
    )

    const val SERVICES_TITLE = "SERVICIOS GRATUITOS INCLUIDOS EN SU PÓLIZA ELÉCTRICA CON NOSOTROS:"

    val SERVICES: List<String> = listOf(
        "1.- Revisión de precios anual y estudio de las mejores ofertas económicas de las compañías eléctricas.",
        "2.- Optimización de la potencia contratada de su póliza eléctrica respecto a la real demandada.",
        "3.- Eliminación de servicios extra y penalizaciones invisibles de la factura.",
        "4.- Atención al cliente a través de su gestor personal: Altas y bajas, duplicados facturas, cambios de " +
            "titular, resolución de incidencias y cualquier tema relacionado con su factura sin que tenga que hablar " +
            "más con una máquina.",
        "5.- Asesoramiento y realización de presupuestos en equipos de eficiencia energética, tales como " +
            "instalaciones solares fotovoltaicas, baterías de condensadores, monitorización de suministros, led, etc."
    )

    const val ADVICE_TITLE = "CONSEJOS PARA EVITAR GRAVES PERJUICIOS ECONÓMICOS Y TIEMPO PERDIDO:"

    val ADVICE: List<String> = listOf(
        "1.- En el presente estudio encontrará los horarios y sus distintos precios correspondientes a su tarifa " +
            "eléctrica. Intente realizar su mayor consumo eléctrico dentro de los horarios más económicos. Si no sabe " +
            "cómo pregunte a su asesor personal. De esta forma puede llegar a ahorrar hasta un 40% en su factura eléctrica.",
        "2.- Desconfíe de llamadas telefónicas o de comerciales que se presenten en su negocio sin previo aviso, ya " +
            "que nunca más volverá a saber de ellos. Tienen como objetivo la **contratación rápida** de productos y no " +
            "tienen en cuenta o no saben interpretar sus condiciones económicas actuales, ocasionándole por tanto un " +
            "grave perjuicio económico que no podrá reclamar a posteriori.",
        "3.- Las incidencias en su contador eléctrico o en su facturación siempre son notificadas **POR ESCRITO**, " +
            "por lo que cualquier persona que acuda a usted con este asunto busca confundirlo y oculta la intención " +
            "real de cambiarle de compañía (Pto. 2).",
        "4.- Si recibe una supuesta mejora económica en su contrato eléctrico, pídala **POR ESCRITO** y háganosla " +
            "llegar para su análisis. Puede contener letra pequeña que varíe la fórmula de facturación aumentando el " +
            "coste final de su factura.",
        "5.- Todas las pólizas eléctricas/gas tienen siempre **PERMANENCIA DE 12 MESES**. Esto implica una " +
            "penalización económica por baja anticipada y unilateral del contrato. Desconfíe si le dicen lo contrario (Pto. 2).",
        "6.- No firme nunca nada y no facilite documentación tal como su FACTURA, DNI o CIF, ya que podrán " +
            "fácilmente cambiarle de compañía sin su consentimiento, con la consiguiente pérdida de tiempo más tarde " +
            "en comisaria denunciando un posible delito fraude.",
        "7.- **Grábenos ahora en su móvil y ante cualquier duda contacte siempre con su asesor personal,** el cual le " +
            "guiará a la mejor opción para usted. Para nuestra empresa lo más importante no es hacer muchos clientes, " +
            "sino mantenerlos a lo largo del tiempo, que estos nos tengan siempre en cuenta y que a su vez nos sigan " +
            "recomendando a sus socios y amigos."
    )

    fun plain(text: String): String = text.replace("**", "")

    /** Every rendered string without emphasis markers, in page order. */
    val ORDERED: List<String> get() =
        listOf(TITLE) + INTRO + SERVICES_TITLE + SERVICES.map(::plain) + ADVICE_TITLE + ADVICE.map(::plain)
}
