---
name: translate-strings
description: Traduce a los 24 idiomas de UltiMusic los strings nuevos o cambiados de values/strings.xml, en la propia sesión y sin llamar a la API de traducción. Úsala SIEMPRE que se añada, cambie o borre un texto de app/src/main/res/values/strings.xml, o cuando el build avise de "string(s) sin traducir".
---

# Traducir los strings de UltiMusic

`app/src/main/res/values/strings.xml` (español) es la fuente de verdad. Hay 24 idiomas más en `values-*/strings.xml`. Las traducciones las haces **tú, en la sesión**: el usuario tiene un plan mensual fijo, y cada llamada a la API se paga aparte. La API (`--api`) solo se usa si el usuario lo pide.

El script `tools/translate_strings.py` hace la parte mecánica:
- Sabe qué falta traducir.
- Valida marcadores, apóstrofos, plurales y `<b>`.
- Escribe los archivos.
- Quita las claves borradas.
- Copia sola la traducción cuando un texto nuevo es idéntico a otro que ya está traducido.
- Genera `values-b+sr+Cyrl` a partir del latino. **Nunca edites el cirílico.**

El build (`--gradle`) nunca llama a la API: si queda algo sin traducir, solo avisa.

## Procedimiento

1. **Edita solo `values/strings.xml`** (el español), con Edit.

2. **Mira qué queda pendiente**, guardando el archivo en el scratchpad:
   ```
   python tools/translate_strings.py --pending <scratchpad>/pending.xml
   ```
   - Si no sale nada pendiente, el script ya lo resolvió todo (borrados o textos idénticos) y has terminado.
   - Si sale algo, lee el archivo. Cada `<item>` lleva:
     - el español `<nuevo>`;
     - el `<anterior>`, si el string cambió;
     - la traducción `<actual>` de cada idioma, si existe;
     - el `<contexto>` del comentario.
   - Los `<idioma>` del principio dan el nombre de cada carpeta y los `quantity` de plural que usa.

3. **Traduce.**
   - **Cambio pequeño** (una palabra o una frase dentro de un texto ya traducido): parte de la traducción `<actual>` de cada idioma y cambia solo lo que cambió en español. No retraduzcas el resto, para no alterar lo que ya estaba bien.
   - **Texto nuevo o reescrito:** tradúcelo entero.
   - **Coherencia:** antes de elegir una palabra, busca con Grep cómo traduce la app ese término en ese idioma (p. ej. `dispositivo` en `values-de`). Usa el mismo término y el mismo registro (tuteo o usted) que el resto del archivo.
   - **Reglas:** cumple las de `SYSTEM_PROMPT` en `tools/translate_strings.py`. Las más fáciles de olvidar:
     - Escapa los apóstrofos (`\'`) y las comillas dobles (`\"`) en todos los idiomas.
     - Conserva `%1$s`, `%d`, `%%`, `^1`, `\n`, `<b>` y las entidades como `&gt;`.
     - En `<plurals>`, pon exactamente los `quantity` de ese idioma.
     - No traduzcas nombres propios ni de marcas (UltiMusic, YouTube, Genius…).
     - Usa siempre dígitos 0-9, también en árabe, persa, urdu, hindi y bengalí.
     - El serbocroata va solo en alfabeto latino.
   - **Calidad:** que suene a app nativa en ese idioma, no a traducción literal.

4. **Escribe las traducciones** en `<scratchpad>/apply.xml` y aplícalas:
   ```xml
   <translations>
     <locale folder="en">
       <string name="clave">…</string>
     </locale>
     …
   </translations>
   ```
   ```
   python tools/translate_strings.py --apply <scratchpad>/apply.xml
   ```
   - Si alguna no es válida, el script no escribe nada y dice por qué. Corrígela y vuelve a aplicar.
   - Con muchos textos se puede aplicar por tandas, por ejemplo unos idiomas cada vez. Una clave se da por traducida cuando ya está en los 24.
   - Escribir `apply.xml` con Write es correcto aquí. No hace falta editar los `values-*` uno a uno con Edit: los cambios se ven con `git diff`.

5. **Comprueba y compila:** `python tools/translate_strings.py --check` y después compila como siempre. El build no debe avisar de nada pendiente.

6. **Informa al usuario:** dile que la traducción se hizo en la sesión, sin coste de API. Si alguna vez se usó `--api`, dale el coste con `tail tools/last_translate_cost.txt`.

## Otros casos
- Si has editado un `values-*` directamente, ejecuta `--accept <claves>`. Valida esas claves y las da por traducidas.
- Si una traducción concreta está mal y el español no ha cambiado, corrígela a mano en ese idioma: se mantiene hasta que cambie el español.
- Para forzar que se retraduzca un string sin tocar el español: `--retranslate <claves>`, y luego sigue desde el paso 2.
