# Gesternova 2.0TD — segundo XLSX sintético para el piloto de importación

Este oráculo se escribió a partir de nueve decimales elegidos para fabricar la muestra, antes de ejecutar el importador. `gesternova-20td-import-synthetic.xlsx` se derivó únicamente del fixture de enseñanza saneado y aprobado; no se leyó el original comercial para esta derivación. Se verificó que solo cambian los nueve valores listados, se conservan coordenadas, tipos, estilos (incluidos los bytes de `xl/styles.xml`), vacíos y visibilidad, el ZIP es íntegro y no hay macros ni relaciones externas. El fixture de enseñanza no cambió. Los bytes de importación son distintos y **no** deben añadirse a pines de enseñanza, preview o activación.

## Resultado esperado

Hoja `Tarifas`; cabeceras `I14` (producto), `F14` (tarifa), `G14` (tipo de precio), `K14` (energía P1 `€/kWh`), `S14` y `T14` (potencia P1 y P2, `€/kWdia`). Incluir solo las filas visibles 16–18 (`2.0TD`, `Precio por Periodos`). Excluir 15 y 20 (ocultas/indexadas) y 19 (`3.0TD`). `L/M` no proporcionan energía P2/P3 y no se materializan; ningún valor se toma de excedentes o coeficientes/fee.

| Producto | Tarifa y clasificación | Energía P1 `€/kWh` | Potencia P1 `€/kWdia` | Potencia P2 `€/kWdia` |
| --- | --- | --- | --- | --- |
| Oferta sintética A (`I16`) | `2.0TD` (`F16`), `Precio por Periodos` (`G16`) | `0.151234567891` (`K16`) | `0.08123` (`S16`) | `0.02956` (`T16`) |
| Oferta sintética B (`I17`) | `2.0TD` (`F17`), `Precio por Periodos` (`G17`) | `0.13234` (`K17`) | `0.07432` (`S17`) | `0.02734` (`T17`) |
| Oferta sintética C (`I18`) | `2.0TD` (`F18`), `Precio por Periodos` (`G18`) | `0` (`K18`) | `0.07987` (`S18`) | `0.02609` (`T18`) |

El cero de `K18` se guarda; los blancos `L18/M18` no. `K16` comprueba precisión más allá de seis decimales; no redondear ni convertir unidades. El perfil aplica la revisión exacta seleccionada en el catálogo QA del fixture, no una revisión derivada de estos precios. Nombre de carga de prueba: `bm-pilot-gesternova.xlsx`. Para probar el fallback, generar solo en el harness una variante que añada `BM Identity` (`A1 = BM SYNTHETIC SUPPLIER`, `B1 = gesternova`), con nombre de carga `carga.xlsx`; misma tabla y mismos resultados. Esa variante tampoco es un fixture de enseñanza.
