# Previsualización Gesternova 2.0TD — oráculo aprobado

## Muestra

`gesternova-20td-sanitized.xlsx` es una proyección sintética de la tabla de las filas 14–20 del resumen Gesternova 2.0TD. La hoja se llama `Tarifas`. Conserva las coordenadas, los huecos, los tipos de las celdas no vacías y la visibilidad de las filas de esa tabla. Los nombres de las ofertas y todos los valores numéricos son inventados. Las etiquetas estructurales necesarias para el cotejo se conservan; los demás textos se sustituyen. No es una copia completa: se omiten las áreas ajenas a esas filas, imágenes, estilos comerciales, relaciones externas y metadatos de origen. Se verificó que el original no cambió y que el fixture no contiene fórmulas, macros ni enlaces externos.

## Selección aprobada para esta muestra

- Identidad del perfil: Gesternova, elegida explícitamente, no inferida por nombre de archivo. El fichero de prueba no acredita identidad comercial real.
- `F14` define `Tarifa`, `G14` define `Tipo precio`, `I14` define `Producto`. Victor confirmó que, para esta muestra, se incluyen como ofertas fijas solo filas visibles con `F = 2.0TD` y `G = Precio por Periodos`. No es una regla general de clasificación de ofertas.
- No clasificar con `Z` ni `AA`: describen remuneración o precio de excedentes de autoconsumo, no el tipo ni el precio del suministro. No leer coeficientes híbridos `J/Q` ni fee `R` como precios de ofertas fijas.
- Excluir filas 15 y 20 (ocultas e indexadas según `G`, con indicación híbrida en el producto sintético de la fila 15) y la fila 19 (`F19 = 3.0TD`). No inferir el tipo por nombre de producto ni tratar las exclusiones como precios cero.
- Para estas tres filas, `K14` indica energía P1 en `€/kWh`; `S14` y `T14` indican potencia P1 y P2 en `€/kWdia`. Mostrar los importes y la unidad literales sin conversión. Victor confirmó mostrar K como P1 solo y no repetirlo para las celdas de energía P2/P3 (`L/M`) que están vacías. Esto describe la muestra, no la tarifa en general: 2.0TD distingue tres períodos de energía y dos de potencia. No rellenar P2/P3 ni usar `0` como sustituto. Tampoco mostrar energía P4–P6 ni potencia P3–P6.

## Salida esperada — ningún valor procede del original

Todas las citas son de `Tarifas`. Las etiquetas de término, período y unidad se sustentan en las cabeceras `K14`, `S14` y `T14`.

| Producto y cita | Tarifa y cita | Evidencia de inclusión | Energía P1 `€/kWh` | Potencia P1 `€/kWdia` | Potencia P2 `€/kWdia` |
| --- | --- | --- | --- | --- | --- |
| Oferta sintética A (`I16`) | 2.0TD (`F16`) | Precio por Periodos (`G16`) | `0.12125` (`K16`) | `0.07123` (`S16`) | `0.02456` (`T16`) |
| Oferta sintética B (`I17`) | 2.0TD (`F17`) | Precio por Periodos (`G17`) | `0` (`K17`) | `0.06432` (`S17`) | `0.02234` (`T17`) |
| Oferta sintética C (`I18`) | 2.0TD (`F18`) | Precio por Periodos (`G18`) | `0.14321` (`K18`) | `0.06987` (`S18`) | `0.02109` (`T18`) |

El cero de `K17` es intencionado y distinto de la ausencia en `L17/M17`. Orden: filas 16, 17 y 18; por fila, energía P1 y potencia P1/P2. No escribir propuestas ni catálogo. Esta tabla se construyó a partir de los precios inventados escritos en el fixture y no de la salida de un extractor.

## Aprobación y límites

Victor aprobó la proyección sintética y este oráculo como criterio de cotejo para el preview del piloto, versionar únicamente estos dos artefactos sintéticos y usar BmWeb + BmBackend en QA local aislado y con opt-ins apagados por defecto. No equivale a aceptación de la implementación ni autoriza el original comercial, el QA alojado, la activación, la importación o la publicación.
