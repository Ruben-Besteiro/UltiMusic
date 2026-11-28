"""
Mantiene los strings de UltiMusic en los 24 idiomas que no son el español. La traducción en sí la
hace Claude Code en la sesión (skill `.claude/skills/translate-strings`), y este script hace la
parte mecánica: saber qué falta, validar y escribir. Solo llama a la API de Claude con --api.

La fuente de verdad es `app/src/main/res/values/strings.xml` (español). Este script compara ese
archivo con la copia que guardó la última vez (`tools/strings_snapshot.xml`) para saber qué strings
son nuevos o han cambiado (o le faltan a un idioma: añadir uno a LOCALES basta para que salga
entero como pendiente), y además:

  - quita de todos los idiomas los strings que se hayan borrado del español,
  - si el texto nuevo es IDÉNTICO al de otro string sin cambios, copia su traducción
    (`reuse_identical`),
  - regenera `values-b+sr+Cyrl` (serbocroata en cirílico, el de Serbia) transliterando el latino
    (`sync_cyrillic`).

Un string que no se ha tocado en español conserva su traducción actual, así que las correcciones
hechas a mano en un idioma sobreviven hasta que se cambie ese mismo string en español.

Lo lanza la tarea `translateStrings` de Gradle antes de cada compilación (`--gradle`, ver
app/build.gradle.kts): hace solo lo de arriba, NUNCA llama a la API, y avisa de lo que quede sin
traducir (se ve en español en esos idiomas hasta que se traduzca). A mano:

    python tools/translate_strings.py --dry-run    # qué hay pendiente en cada idioma
    python tools/translate_strings.py --pending F  # escribe en F lo pendiente: español nuevo, el
                                                   # anterior y la traducción actual de cada idioma
    python tools/translate_strings.py --apply F    # valida y escribe las traducciones de F:
                                                   #   <translations>
                                                   #     <locale folder="en"><string name="k">…</string></locale>
                                                   #   </translations>
                                                   # si alguna no es válida, no escribe nada
    python tools/translate_strings.py --accept K…  # da por traducidas esas claves tal como están
                                                   # ahora en cada idioma (tras editarlas a mano)
    python tools/translate_strings.py --check      # revisa todas las traducciones actuales
    python tools/translate_strings.py --retranslate K…   # marca esas claves como pendientes
    python tools/translate_strings.py --api        # traduce lo pendiente con la API (cuesta dinero)

Las reglas de traducción (marcadores, apóstrofos, plurales, nombres propios...) están en
SYSTEM_PROMPT, y las comprueba `validate` tanto en --apply como en --api.

Con --api traduce con el modelo claude-sonnet-5-5 usando el SDK oficial `anthropic`
(`pip install anthropic`). La clave se lee de `local.properties` (`anthropic.api_key=...`) o, si no
está, de las credenciales por defecto del SDK (variable ANTHROPIC_API_KEY o `ant auth login`).

Una clave cambiada se da por traducida en `tools/strings_snapshot.xml` cuando ya está traducida en
TODOS los idiomas: hasta entonces sigue saliendo como pendiente.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import copy
import json
import os
import re
import sys
import threading
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
BASE_FILE = RES / "values" / "strings.xml"
SNAPSHOT_FILE = ROOT / "tools" / "strings_snapshot.xml"
LOCAL_PROPERTIES = ROOT / "local.properties"

MODEL = "claude-sonnet-5-5"
# Precio de claude-sonnet-5-5 en dólares por millón de tokens, solo para el resumen del coste:
# (entrada, salida, escritura en caché = 1,25x la entrada, lectura de caché).
PRICE_PER_MTOK = (2.00, 10.00, 2.50, 0.10)
# Strings por petición: así cada respuesta se mantiene corta aunque haya que traducir un idioma entero.
CHUNK_SIZE = 60
# Idiomas traducidos a la vez.
MAX_PARALLEL = 6
MAX_ATTEMPTS = 3

# Carpeta values-xx -> (nombre del idioma para el prompt, cantidades de <plurals> que usa).
LOCALES: dict[str, tuple[str, list[str]]] = {
    "pt": ("portugués de Portugal", ["one", "many", "other"]),
    "en": ("inglés", ["one", "other"]),
    "fr": ("francés", ["one", "many", "other"]),
    "de": ("alemán", ["one", "other"]),
    "it": ("italiano", ["one", "many", "other"]),
    "nl": ("holandés (neerlandés)", ["one", "other"]),
    "pl": ("polaco", ["one", "few", "many", "other"]),
    # El cirílico (values-b+sr+Cyrl) NO se traduce: lo genera sync_cyrillic() a partir de este.
    # Variedad común para serbios, croatas, bosnios y montenegrinos (ver AppLocale.serbocroatian):
    # ijekavo, que admiten los cuatro estándares, con vocabulario neutro o, donde no lo hay, serbio
    # (más hablantes). El cirílico se genera de este, así que no puede llevar croatismos marcados.
    "b+sr+Latn": ("serbocroata (variedad común serbio/croata), escrito SOLO en alfabeto latino, "
                  "nunca en cirílico. Usa SIEMPRE la pronunciación ijekava (pjesma, uvijek, cijeli, "
                  "sljedeći), nunca la ekava. En el vocabulario elige la palabra neutra o internacional, "
                  "y si no la hay, la serbia, nunca la croata: muzika (no glazba), folder (no mapa), "
                  "sistem (no sustav), server (no poslužitelj), link (no poveznica), meni (no izbornik), "
                  "komanda (no naredba), podešavanja (no postavke), sačuvati (no spremiti), izabrati (no "
                  "odabrati), tačka (no točka), dugme (no gumb), prodavnica (no trgovina), spoljni (no "
                  "vanjski), važeći (no valjan), zvaničan (no službeni), korišćenje (no korištenje), "
                  "verbos en -ovati/-isati (reprodukovati, sinhronizovati, grupisati; no -irati), futuro "
                  "biće/provjeriće (no bit će), participio dodat (no dodan), «da + presente» (prestati da "
                  "radi) y «šta» como interrogativo", ["one", "few", "other"]),
    "sw": ("suajili", ["one", "other"]),
    "iw": ("hebreo", ["one", "two", "other"]),
    "ru": ("ruso", ["one", "few", "many", "other"]),
    "tr": ("turco", ["one", "other"]),
    "ar": ("árabe estándar moderno", ["zero", "one", "two", "few", "many", "other"]),
    "fa": ("persa", ["one", "other"]),
    "ur": ("urdu", ["one", "other"]),
    "hi": ("hindi", ["one", "other"]),
    "bn": ("bengalí", ["one", "other"]),
    "zh": ("chino simplificado", ["other"]),
    "ja": ("japonés", ["other"]),
    "ko": ("coreano", ["other"]),
    "vi": ("vietnamita", ["other"]),
    "th": ("tailandés", ["other"]),
    "in": ("indonesio", ["other"]),
    "b+fil": ("filipino", ["one", "other"]),
}

RESOURCE_TAGS = ("string", "plurals", "string-array")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]|%%|\^\d")
WORD = re.compile(r"\w{4,}")
UNESCAPED_APOSTROPHE = re.compile(r"(?<!\\)'")
# Comillas dobles rectas sin escapar: Android se las come. La única excepción es un texto entero
# entre comillas (p. ej. `" | "`), que es como Android conserva los espacios de los extremos.
UNESCAPED_QUOTE = re.compile(r'(?<!\\)"')

SYSTEM_PROMPT = """Eres el traductor de la interfaz de UltiMusic, un reproductor de música para Android. \
Traduces recursos de strings de Android del español al {language}.

Recibirás una lista de elementos XML de Android (<string>, <plurals> o <string-array>) en español, \
cada uno con su nombre y, a veces, un comentario del desarrollador que explica dónde aparece en la \
app. Devuelve cada elemento traducido como XML de Android completo y válido.

Reglas:
- Conserva el mismo nombre de etiqueta y el mismo atributo name. No añadas translatable ni otros atributos.
- Conserva exactamente los marcadores de formato (%1$s, %2$d, %d, %%, ^1), las etiquetas <b>...</b>, \
los saltos \\n, las comillas escapadas \\" y las entidades como &gt;.
- Escapa siempre los apóstrofos como \\' (es obligatorio en los recursos de Android).
- Escapa SIEMPRE las comillas dobles rectas (") dentro del texto como \\" en todos los idiomas, \
también en urdu, bengalí y cualquier otro: sin escapar, Android se las come. Si el original ya \
las lleva escapadas, conserva el escape.
- En <plurals> incluye exactamente estos quantity, en este orden: {quantities}. Cada uno debe \
contener los mismos marcadores que el quantity "other" del original (en "zero", "one" y "two" se \
pueden omitir si el idioma escribe el número con palabras).
- No traduzcas nombres propios ni de marcas: UltiMusic, UltiMusicPod, UltiMusic Recount, Untar \
Technologies, Genius, iTunes, iTunes Store, Amazon Music, YouTube, YouTube Data API, MusicBrainz, \
lrclib.net. Tampoco los textos de botones de webs externas que el usuario tiene que buscar tal \
cual: New API Client, Generate Access Token, Client Access Token, Client ID, Client Secret, \
App Name, App Website URL.
- Escribe siempre las cifras con los dígitos occidentales 0-9, también en árabe, persa, urdu, \
hindi o bengalí: la app fuerza ese sistema numérico en todos los idiomas para que no se mezcle \
con el de los números que formatea ella.
- Las URLs y los strings que solo contienen formato (como "%1$s | %2$s") se copian sin cambios.
- Usa un lenguaje de interfaz natural y conciso, como el de una app nativa en ese idioma.
- Mantén la terminología y el registro (formal o informal) del glosario de traducciones ya \
existentes que se incluye, para que la app sea coherente."""


# ---------------------------------------------------------------------------------------------
# Lectura y escritura de XML
# ---------------------------------------------------------------------------------------------

def parse_resources(path: Path) -> list[tuple[ET.Element, str]]:
    """Elementos de recurso del archivo, en orden, cada uno con los comentarios que lo preceden."""
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.parse(path, parser=parser).getroot()
    result = []
    pending_comments: list[str] = []
    for child in root:
        if child.tag is ET.Comment:
            pending_comments.append(" ".join((child.text or "").split()))
        elif child.tag in RESOURCE_TAGS:
            result.append((child, " ".join(pending_comments)))
            pending_comments = []
    return result


def is_translatable(element: ET.Element) -> bool:
    return element.get("translatable") != "false"


def canonical(element: ET.Element) -> str:
    """Serialización estable de un elemento, sin espacios de indentación, para comparar y escribir."""
    element = copy.deepcopy(element)
    element.tail = None
    if element.tag != "string":
        element.text = None
        for item in element:
            item.tail = None
    return ET.tostring(element, encoding="unicode")


def render(element: ET.Element) -> str:
    """El elemento con la indentación del archivo de strings (4 espacios, items a 8)."""
    if element.tag == "string":
        return "    " + canonical(element)
    element = copy.deepcopy(element)
    element.text = None
    items = list(element)
    for item in items:
        item.tail = None
        element.remove(item)
    opening = canonical(element).replace(f"></{element.tag}>", ">").replace(" />", ">")
    lines = ["    " + opening]
    lines += ["        " + ET.tostring(item, encoding="unicode") for item in items]
    lines.append(f"    </{element.tag}>")
    return "\n".join(lines)


def text_parts(element: ET.Element) -> list[str]:
    if element.tag == "string":
        return ["".join(element.itertext())]
    return ["".join(item.itertext()) for item in element]


HAND_EDITABLE_NOTE = (
    "    <!-- Generado por tools/translate_strings.py a partir de values/strings.xml. Se puede\n"
    "         corregir a mano: la corrección se mantiene hasta que cambie ese string en español. -->\n"
)


def locale_content(elements: list[ET.Element], note: str = HAND_EDITABLE_NOTE) -> str:
    body = "\n".join(render(element) for element in elements)
    return f"<resources>\n{note}\n{body}\n</resources>\n"


def write_locale(folder: str, elements: list[ET.Element]) -> None:
    path = RES / f"values-{folder}" / "strings.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(locale_content(elements), encoding="utf-8", newline="\n")


# ---------------------------------------------------------------------------------------------
# Serbocroata en cirílico (derivado del latino, sin API)
# ---------------------------------------------------------------------------------------------

SERBIAN_LATIN_FOLDER = "b+sr+Latn"
SERBIAN_CYRILLIC_FOLDER = "b+sr+Cyrl"
CYRILLIC_NOTE = (
    "    <!-- Generado por tools/translate_strings.py transliterando values-b+sr+Latn al cirílico.\n"
    "         NO editar a mano: se sobrescribe entero cada vez que cambia el latino. -->\n"
)
CYRILLIC_LETTERS = {
    "a": "а", "b": "б", "c": "ц", "č": "ч", "ć": "ћ", "d": "д", "đ": "ђ", "e": "е", "f": "ф",
    "g": "г", "h": "х", "i": "и", "j": "ј", "k": "к", "l": "л", "m": "м", "n": "н", "o": "о",
    "p": "п", "r": "р", "s": "с", "š": "ш", "t": "т", "u": "у", "v": "в", "z": "з", "ž": "ж",
}
# Letras que se escriben con UNA sola cirílica. Solo importa el primer carácter para las mayúsculas.
CYRILLIC_DIGRAPHS = {"lj": "љ", "nj": "њ", "dž": "џ"}
# Palabras (por prefijo, en minúsculas) donde lj/nj/dž son DOS letras (kon-jug-, in-jek-, nad-ž-...).
DIGRAPH_EXCEPTIONS = ("konjug", "konjunk", "injek", "nadž", "podž", "odž", "predž", "izdž")
# Nombres que se quedan en latino aunque no los detecte la heurística de [is_foreign_token].
FOREIGN_WORDS = {
    "Android", "Bluetooth", "Genius", "Google", "Spotify", "Recount", "Pod", "Technologies",
    "Untar", "Amazon", "Music", "Store", "Data", "API", "New", "Client", "Generate", "Access",
    "Token", "Secret", "App", "Name", "Website", "URL", "Hz", "kHz", "dB",
}
SQL_KEYWORDS = {"ALTER", "CREATE", "DELETE", "DROP", "INSERT", "PRAGMA", "SELECT", "UPDATE"}
# Palabras inglesas que el latino usa tal cual y que en cirílico se escriben a la serbia.
LOANWORDS = {"equalizer": "еквилајзер", "player": "плејер", "remix": "ремикс"}
# Lo que no se toca: marcadores de formato, escapes de Android (\n \' \") y URLs.
CYRILLIC_PROTECTED = re.compile(r"%(?:\d+\$)?[sd]|%%|\^\d|\\.|https?://\S+|<[^>]+>")
LATIN_WORD = re.compile(r"[A-Za-zČčĆćĐđŠšŽž0-9][A-Za-zČčĆćĐđŠšŽž0-9.]*")


def is_foreign_token(word: str) -> bool:
    """Palabra que NO es serbocroata y debe quedarse en latino (marcas, siglas, unidades)."""
    letters = word.strip(".")
    if letters in FOREIGN_WORDS or letters in SQL_KEYWORDS or any(ch.isdigit() for ch in word):
        return True
    if letters.lower() in LOANWORDS:
        return False
    if any(ch in "qwxyQWXY" for ch in word):
        return True
    # Sigla de hasta 3 letras (ID, URL, SQL). Las mayúsculas más largas (NASTAVI) son serbocroata.
    if 2 <= len(letters) <= 3 and letters.isupper():
        return True
    # camelCase (YouTube, iTunes, MusicBrainz, UltiMusic, kHz) o dominio (lrclib.net).
    return (any(ch.isupper() for ch in word[1:]) and not word.isupper()) or "." in letters


def to_cyrillic_word(word: str, flagged: set[str]) -> str:
    lower = word.lower()
    stem = lower.rstrip(".")
    if stem in LOANWORDS:
        loan = LOANWORDS[stem] + word[len(stem):]
        return loan.upper() if word.rstrip(".").isupper() else (loan[0].upper() + loan[1:] if word[0].isupper() else loan)
    plain = lower.startswith(DIGRAPH_EXCEPTIONS)
    out = []
    i = 0
    while i < len(word):
        pair = lower[i:i + 2]
        if pair in CYRILLIC_DIGRAPHS and not plain:
            flagged.add(word)
            letter = CYRILLIC_DIGRAPHS[pair]
            out.append(letter.upper() if word[i].isupper() else letter)
            i += 2
            continue
        letter = CYRILLIC_LETTERS.get(lower[i], word[i])
        out.append(letter.upper() if word[i].isupper() else letter)
        i += 1
    return "".join(out)


def to_cyrillic(text: str | None, flagged: set[str], foreign: set[str]) -> str | None:
    """`text` en cirílico. `flagged` recoge las palabras con lj/nj/dž tratadas como dígrafo y
    `foreign` las que se han dejado en latino por parecer ajenas al serbocroata (para revisarlas)."""
    if not text:
        return text

    def word(match: re.Match) -> str:
        token = match.group(0)
        if is_foreign_token(token):
            foreign.add(token)
            return token
        return to_cyrillic_word(token, flagged)

    result = []
    position = 0
    for protected in CYRILLIC_PROTECTED.finditer(text):
        result.append(LATIN_WORD.sub(word, text[position:protected.start()]))
        result.append(protected.group(0))
        position = protected.end()
    result.append(LATIN_WORD.sub(word, text[position:]))
    return "".join(result)


def sync_cyrillic() -> None:
    """Regenera values-b+sr+Cyrl/strings.xml entero a partir del latino. Solo escribe si cambia."""
    latin = RES / f"values-{SERBIAN_LATIN_FOLDER}" / "strings.xml"
    if not latin.exists():
        return
    flagged: set[str] = set()
    foreign: set[str] = set()
    elements = []
    for element, _ in parse_resources(latin):
        element = copy.deepcopy(element)
        for node in element.iter():
            node.text = to_cyrillic(node.text, flagged, foreign)
            node.tail = to_cyrillic(node.tail, flagged, foreign)
        elements.append(element)
    content = locale_content(elements, CYRILLIC_NOTE)
    path = RES / f"values-{SERBIAN_CYRILLIC_FOLDER}" / "strings.xml"
    if path.exists() and path.read_text(encoding="utf-8") == content:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")
    print(f"[translateStrings] values-{SERBIAN_CYRILLIC_FOLDER}: regenerado a partir del latino")
    if flagged:
        print("[translateStrings]   con lj/nj/dž como UNA letra (revisar si alguna son dos): "
              + ", ".join(sorted(flagged)))
    if foreign:
        print("[translateStrings]   dejadas en latino por parecer ajenas al serbocroata: "
              + ", ".join(sorted(foreign)))


# ---------------------------------------------------------------------------------------------
# Validación de una traducción
# ---------------------------------------------------------------------------------------------

def validate(source: ET.Element, xml_text: str, quantities: list[str]) -> tuple[ET.Element | None, str | None]:
    try:
        translated = ET.fromstring(xml_text)
    except ET.ParseError as e:
        return None, f"XML no válido ({e})"
    if translated.tag != source.tag or translated.get("name") != source.get("name"):
        return None, f"debe ser <{source.tag} name=\"{source.get('name')}\">"
    for text in text_parts(translated):
        if UNESCAPED_APOSTROPHE.search(text):
            return None, "hay un apóstrofo sin escapar (debe ser \\')"
        quotes = UNESCAPED_QUOTE.findall(text)
        if quotes and not (len(quotes) == 2 and text.startswith('"') and text.endswith('"')):
            return None, 'hay comillas dobles sin escapar (deben ser \\")'
    if canonical(source).count("<b>") != canonical(translated).count("<b>"):
        return None, "no conserva las etiquetas <b>"

    if source.tag == "string":
        expected = sorted(PLACEHOLDER.findall(text_parts(source)[0]))
        found = sorted(PLACEHOLDER.findall(text_parts(translated)[0]))
        if expected != found:
            return None, f"marcadores {found}, se esperaban {expected}"
    elif source.tag == "plurals":
        got = [item.get("quantity") for item in translated]
        if got != quantities:
            return None, f"quantity {got}, se esperaban {quantities}"
        other = next(item for item in source if item.get("quantity") == "other")
        expected = set(PLACEHOLDER.findall("".join(other.itertext())))
        for item in translated:
            found = set(PLACEHOLDER.findall("".join(item.itertext())))
            optional = item.get("quantity") in ("zero", "one", "two")
            if found != expected and not (optional and found <= expected):
                return None, f"el quantity \"{item.get('quantity')}\" tiene marcadores {sorted(found)}, se esperaban {sorted(expected)}"
    else:  # string-array
        if len(list(translated)) != len(list(source)):
            return None, f"tiene {len(list(translated))} items, el original {len(list(source))}"
        for src_item, tr_item in zip(source, translated):
            if sorted(PLACEHOLDER.findall("".join(src_item.itertext()))) != sorted(PLACEHOLDER.findall("".join(tr_item.itertext()))):
                return None, "un item no conserva sus marcadores"
    return translated, None


# ---------------------------------------------------------------------------------------------
# Llamada a la API de Claude
# ---------------------------------------------------------------------------------------------

RESPONSE_SCHEMA = {
    "type": "object",
    "properties": {
        "translations": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "xml": {"type": "string"},
                },
                "required": ["name", "xml"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["translations"],
    "additionalProperties": False,
}


class TranslationError(Exception):
    pass


USAGE_LOCK = threading.Lock()


def read_api_key() -> str | None:
    if not LOCAL_PROPERTIES.exists():
        return None
    for line in LOCAL_PROPERTIES.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "anthropic.api_key" and value.strip():
            return value.strip()
    return None


def request_translations(client, folder: str, sources: list[tuple[ET.Element, str]],
                         glossary: list[dict], feedback: dict[str, str], usage: list[int],
                         cache_prompt: bool = False) -> dict[str, str]:
    """Una petición a Claude: devuelve {name: xml traducido} tal cual lo devuelve el modelo."""
    import anthropic

    language, quantities = LOCALES[folder]
    items = []
    for element, comment in sources:
        entry = {"name": element.get("name"), "xml": canonical(element)}
        if comment:
            entry["contexto"] = comment
        if element.get("name") in feedback:
            entry["error_del_intento_anterior"] = feedback[element.get("name")]
        items.append(entry)

    # El glosario va en su propio bloque, separado de los elementos, para poder cachearlo: es idéntico
    # en todas las peticiones de este idioma, así que las siguientes lo leen de caché (5 % del precio).
    glossary_text = (
        "Glosario de traducciones ya existentes en la app (español -> traducción):\n"
        + json.dumps(glossary, ensure_ascii=False)
    )
    items_text = "Traduce estos elementos:\n" + json.dumps(items, ensure_ascii=False, indent=1)
    system_text = SYSTEM_PROMPT.format(language=language, quantities=", ".join(quantities))
    if cache_prompt:
        system = [{"type": "text", "text": system_text, "cache_control": {"type": "ephemeral"}}]
        user_content = [
            {"type": "text", "text": glossary_text, "cache_control": {"type": "ephemeral"}},
            {"type": "text", "text": items_text},
        ]
    else:
        system = system_text
        user_content = glossary_text + "\n\n" + items_text

    try:
        with client.beta.messages.stream(
            model=MODEL,
            max_tokens=64000,
            # Si un clasificador de seguridad rechaza la petición, la API la repite sola en el modelo
            # de respaldo que recomienda Anthropic en vez de devolver el rechazo.
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            output_config={
                "effort": "medium",
                "format": {"type": "json_schema", "schema": RESPONSE_SCHEMA},
            },
            system=system,
            messages=[{"role": "user", "content": user_content}],
        ) as stream:
            message = stream.get_final_message()
    except anthropic.AuthenticationError as e:
        raise TranslationError(
            "la clave de la API de Claude no es válida. Ponla en local.properties como "
            "anthropic.api_key=sk-ant-..."
        ) from e
    except anthropic.APIConnectionError as e:
        raise TranslationError(f"no se ha podido conectar con la API de Claude ({e})") from e
    except anthropic.APIStatusError as e:
        raise TranslationError(f"la API de Claude ha devuelto el error {e.status_code}: {e.message}") from e

    with USAGE_LOCK:
        # input_tokens NO incluye lo que se escribe o se lee de caché: se cuentan aparte.
        usage[0] += message.usage.input_tokens
        usage[1] += message.usage.output_tokens
        usage[2] += message.usage.cache_creation_input_tokens or 0
        usage[3] += message.usage.cache_read_input_tokens or 0

    if message.stop_reason == "refusal":
        raise TranslationError("Claude ha rechazado la petición")
    if message.stop_reason == "max_tokens":
        raise TranslationError("la respuesta se ha cortado por longitud")

    # Si hubo un modelo de respaldo, el texto válido es el que viene después del último bloque "fallback".
    blocks = list(message.content)
    last_fallback = max((i for i, b in enumerate(blocks) if b.type == "fallback"), default=-1)
    text = "".join(b.text for b in blocks[last_fallback + 1:] if b.type == "text")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise TranslationError(f"la respuesta no es JSON válido ({e})") from e
    return {entry["name"]: entry["xml"] for entry in data["translations"]}


def translate_locale(client, folder: str, sources: list[tuple[ET.Element, str]],
                     glossary: list[dict], usage: list[int]) -> dict[str, ET.Element]:
    """Traduce `sources` a un idioma, reintentando los que no pasen la validación."""
    _, quantities = LOCALES[folder]
    done: dict[str, ET.Element] = {}
    pending = list(sources)
    feedback: dict[str, str] = {}
    # Con una sola petición el caché sale MÁS caro (escribir cuesta 1,25x el input normal y nunca se
    # llega a leer), así que solo se usa cuando el idioma necesita dos o más peticiones.
    cache_prompt = len(pending) > CHUNK_SIZE
    for attempt in range(1, MAX_ATTEMPTS + 1):
        failed = []
        for start in range(0, len(pending), CHUNK_SIZE):
            chunk = pending[start:start + CHUNK_SIZE]
            answers = request_translations(client, folder, chunk, glossary, feedback, usage, cache_prompt)
            for element, comment in chunk:
                name = element.get("name")
                if name not in answers:
                    feedback[name] = "faltaba en la respuesta"
                    failed.append((element, comment))
                    continue
                translated, error = validate(element, answers[name], quantities)
                if error:
                    feedback[name] = f"{error}. Tu respuesta fue: {answers[name]}"
                    failed.append((element, comment))
                else:
                    done[name] = translated
        if not failed:
            return done
        pending = failed
    names = ", ".join(e.get("name") for e, _ in pending)
    raise TranslationError(f"no se han podido traducir bien tras {MAX_ATTEMPTS} intentos: {names}")


# ---------------------------------------------------------------------------------------------
# Atajos sin API: reutilizar traducciones idénticas y aceptar ediciones a mano
# ---------------------------------------------------------------------------------------------

def content_key(element: ET.Element) -> str:
    """El elemento sin su nombre: dos strings con el mismo texto (y tipo) dan la misma clave."""
    element = copy.deepcopy(element)
    element.attrib.pop("name", None)
    return canonical(element)


def reuse_identical(base: list[tuple[ET.Element, str]], changed: set[str], existing: dict[str, ET.Element],
                    to_translate: list[tuple[ET.Element, str]]) -> tuple[dict[str, ET.Element], list]:
    """Separa de `to_translate` los strings cuyo español es idéntico al de otro string que no ha
    cambiado y ya está traducido en este idioma: devuelve ({nombre: traducción copiada}, lo que
    sigue pendiente de traducir)."""
    donors: dict[str, str] = {}
    for element, _ in base:
        name = element.get("name")
        if name not in changed and name in existing:
            donors.setdefault(content_key(element), name)
    reused: dict[str, ET.Element] = {}
    pending = []
    for element, comment in to_translate:
        donor = donors.get(content_key(element))
        if donor is None:
            pending.append((element, comment))
            continue
        copied = copy.deepcopy(existing[donor])
        copied.set("name", element.get("name"))
        reused[element.get("name")] = copied
    return reused, pending


def accept_into_snapshot(keys: list[str]) -> None:
    """Pone en `strings_snapshot.xml` el español actual de [keys] y deja el resto como estaba: el
    build da esas claves por traducidas (con lo que haya ahora en cada idioma)."""
    current = {e.get("name"): e for e, _ in parse_resources(BASE_FILE)}
    old = {e.get("name"): e for e, _ in parse_resources(SNAPSHOT_FILE)}
    elements = []
    for name, element in current.items():
        if name in keys:
            elements.append(element)
        elif name in old:
            elements.append(old[name])
    SNAPSHOT_FILE.write_text(locale_content(elements, note=""), encoding="utf-8", newline="\n")


# ---------------------------------------------------------------------------------------------
# Programa principal
# ---------------------------------------------------------------------------------------------

def build_pending_file(path: Path, pending: dict[str, list[str]], base_by_name: dict[str, tuple[ET.Element, str]],
                       snapshot_elements: dict[str, ET.Element], existing_by_folder: dict[str, dict[str, ET.Element]]) -> None:
    """Escribe en [path] lo que falta traducir, para traducirlo a mano (en la sesión de Claude Code)
    y devolverlo con --apply. Por cada clave: el español nuevo, el anterior (si cambió) y la
    traducción actual en cada idioma (si existe), para que un cambio pequeño se pueda trasladar."""
    root = ET.Element("pending")
    for folder, (language, quantities) in LOCALES.items():
        ET.SubElement(root, "idioma", folder=folder, nombre=language, quantities=" ".join(quantities))
    for name, folders in pending.items():
        element, comment = base_by_name[name]
        item = ET.SubElement(root, "item", name=name, idiomas=" ".join(folders))
        if comment:
            ET.SubElement(item, "contexto").text = comment
        if name in snapshot_elements and canonical(snapshot_elements[name]) != canonical(element):
            ET.SubElement(item, "anterior").append(copy.deepcopy(snapshot_elements[name]))
        ET.SubElement(item, "nuevo").append(copy.deepcopy(element))
        for folder in folders:
            current = existing_by_folder[folder].get(name)
            if current is not None:
                ET.SubElement(item, "actual", folder=folder).append(copy.deepcopy(current))
    ET.indent(root, space=" ")
    path.write_text(ET.tostring(root, encoding="unicode") + "\n", encoding="utf-8", newline="\n")


def read_apply_file(path: Path) -> dict[str, list[ET.Element]]:
    """Lee un archivo de --apply:
        <translations>
          <locale folder="en">
            <string name="clave">…</string>
          </locale>
          …
        </translations>"""
    root = ET.parse(path).getroot()
    result: dict[str, list[ET.Element]] = {}
    for locale in root.findall("locale"):
        result.setdefault(locale.get("folder"), []).extend(
            child for child in locale if child.tag in RESOURCE_TAGS)
    return result


def check_locales(base: list[tuple[ET.Element, str]], only: set[str] | None = None) -> list[str]:
    """Problemas de las traducciones actuales frente al español (marcadores, apóstrofos, plurales,
    <b>, claves que faltan). Con [only], solo mira esas claves."""
    problems = []
    for folder, (_, quantities) in LOCALES.items():
        path = RES / f"values-{folder}" / "strings.xml"
        try:
            existing = {e.get("name"): e for e, _ in parse_resources(path)} if path.exists() else {}
        except ET.ParseError as e:
            problems.append(f"values-{folder}: XML no válido ({e})")
            continue
        for element, _ in base:
            name = element.get("name")
            if only is not None and name not in only:
                continue
            if name not in existing:
                problems.append(f"values-{folder}: falta {name}")
                continue
            _, error = validate(element, canonical(existing[name]), quantities)
            if error:
                problems.append(f"values-{folder}: {name}: {error}")
    return problems


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    parser = argparse.ArgumentParser(description="Traduce los strings de UltiMusic a todos los idiomas.")
    parser.add_argument("--dry-run", action="store_true", help="solo enseña lo que haría")
    parser.add_argument("--retranslate", nargs="+", default=[], metavar="CLAVE",
                        help="marca estas claves como pendientes de traducir en todos los idiomas")
    parser.add_argument("--accept", nargs="+", default=[], metavar="CLAVE",
                        help="da por traducidas estas claves con lo que tenga ahora cada idioma")
    parser.add_argument("--pending", metavar="ARCHIVO", type=Path,
                        help="escribe en ARCHIVO lo que falta traducir, para traducirlo a mano")
    parser.add_argument("--apply", metavar="ARCHIVO", type=Path,
                        help="valida y escribe las traducciones hechas a mano de ARCHIVO")
    parser.add_argument("--check", action="store_true",
                        help="revisa todas las traducciones actuales (marcadores, plurales, apóstrofos…)")
    parser.add_argument("--api", action="store_true",
                        help="traduce lo pendiente con la API de Claude (cuesta dinero)")
    parser.add_argument("--gradle", action="store_true",
                        help="modo compilación: nunca llama a la API y los fallos no paran el build")
    args = parser.parse_args()
    fail_code = 0 if args.gradle else 1
    if args.gradle:
        args.api = False

    def warn(message: str) -> int:
        print(f"[translateStrings] AVISO: {message}", file=sys.stderr)
        return fail_code

    try:
        base = [(e, c) for e, c in parse_resources(BASE_FILE) if is_translatable(e)]
    except ET.ParseError as e:
        return warn(f"values/strings.xml no es XML válido ({e}); no se traduce nada")
    base_names = {e.get("name") for e, _ in base}
    base_by_name = {e.get("name"): (e, c) for e, c in base}

    if args.check:
        problems = check_locales(base)
        for problem in problems:
            print(f"[translateStrings] {problem}")
        print(f"[translateStrings] {len(problems)} problema(s)")
        return 1 if problems else 0

    unknown = [k for k in args.retranslate + args.accept if k not in base_names]
    if unknown:
        return warn(f"estas claves no existen en values/strings.xml: {', '.join(unknown)}")

    # Sin copia previa se asume que los idiomas están al día con el español actual.
    if not SNAPSHOT_FILE.exists():
        SNAPSHOT_FILE.write_bytes(BASE_FILE.read_bytes())
        print("[translateStrings] Creada tools/strings_snapshot.xml con el español actual.")
    if args.accept and not args.dry_run:
        problems = check_locales(base, set(args.accept))
        if problems:
            for problem in problems:
                print(f"[translateStrings] {problem}", file=sys.stderr)
            return warn("no se aceptan: corrige antes esos problemas")
        accept_into_snapshot(args.accept)
        print(f"[translateStrings] Aceptadas sin traducir: {', '.join(args.accept)}")
    snapshot_elements = {e.get("name"): e for e, _ in parse_resources(SNAPSHOT_FILE)}
    changed = {e.get("name") for e, _ in base
               if e.get("name") not in snapshot_elements or canonical(snapshot_elements[e.get("name")]) != canonical(e)}
    changed |= set(args.retranslate)

    # Traducciones hechas a mano (--apply): pendientes o correcciones de cualquier clave. Se valida
    # cada una igual que las de la API, y si alguna no vale no se escribe nada.
    applied: dict[str, dict[str, ET.Element]] = {}
    if args.apply:
        try:
            supplied = read_apply_file(args.apply)
        except (ET.ParseError, OSError) as e:
            return warn(f"no se ha podido leer {args.apply} ({e})")
        invalid = []
        for folder, elements in supplied.items():
            if folder not in LOCALES:
                invalid.append(f"idioma desconocido: {folder}")
                continue
            _, quantities = LOCALES[folder]
            for element in elements:
                name = element.get("name")
                if name not in base_by_name:
                    invalid.append(f"values-{folder}: la clave {name} no existe en español")
                    continue
                translated, error = validate(base_by_name[name][0], canonical(element), quantities)
                if error:
                    invalid.append(f"values-{folder}: {name}: {error}")
                else:
                    applied.setdefault(folder, {})[name] = translated
        if invalid:
            for problem in invalid:
                print(f"[translateStrings]   NO VÁLIDO, {problem}", file=sys.stderr)
            return warn("hay traducciones no válidas: no se ha escrito nada. Corrígelas y vuelve a aplicar")

    # Qué hay que hacer en cada idioma.
    plans = {}
    existing_by_folder: dict[str, dict[str, ET.Element]] = {}
    for folder in LOCALES:
        path = RES / f"values-{folder}" / "strings.xml"
        try:
            existing = {e.get("name"): e for e, _ in parse_resources(path)} if path.exists() else {}
        except ET.ParseError as e:
            return warn(f"values-{folder}/strings.xml no es XML válido ({e}); corrígelo antes de traducir")
        existing_by_folder[folder] = existing
        mine = applied.get(folder, {})
        to_translate = [(e, c) for e, c in base
                        if (e.get("name") in changed or e.get("name") not in existing) and e.get("name") not in mine]
        reused, to_translate = reuse_identical(base, changed, existing, to_translate)
        reused = {**reused, **mine}
        removed = sorted(set(existing) - base_names)
        if to_translate or removed or reused:
            plans[folder] = (existing, to_translate, removed, reused)

    if not plans:
        if not args.dry_run:
            sync_cyrillic()
            if changed:
                SNAPSHOT_FILE.write_bytes(BASE_FILE.read_bytes())
        return 0

    for folder, (_, to_translate, removed, reused) in plans.items():
        parts = []
        if reused:
            parts.append(f"escribir {len(reused)} sin API ({', '.join(sorted(reused))})")
        if to_translate:
            parts.append(f"traducir {len(to_translate)}")
        if removed:
            parts.append(f"quitar {len(removed)} ({', '.join(removed)})")
        print(f"[translateStrings] values-{folder}: {', '.join(parts)}")
    if args.dry_run:
        return 0

    results: dict[str, dict[str, ET.Element]] = {}
    errors: dict[str, str] = {}
    usage = [0, 0, 0, 0]  # entrada sin caché, salida, escritura en caché, lectura de caché
    jobs = {folder: plan for folder, plan in plans.items() if plan[1]}

    if args.api and jobs:
        try:
            import anthropic
        except ImportError:
            return warn("falta el SDK de Claude para Python. Instálalo con: pip install anthropic")
        api_key = read_api_key()
        client = anthropic.Anthropic(api_key=api_key) if api_key else anthropic.Anthropic()

        def run(folder: str) -> dict[str, ET.Element]:
            existing, to_translate, _, _ = jobs[folder]
            translating = {e.get("name") for e, _ in to_translate}
            glossary = [
                {"es": text_parts(e)[0], "traduccion": text_parts(existing[e.get("name")])[0]}
                for e, _ in base
                if e.tag == "string" and e.get("name") in existing and e.get("name") not in translating
                and len(text_parts(e)[0]) <= 60
            ]
            if len(to_translate) <= CHUNK_SIZE:
                # Pasada pequeña: el glosario entero pesa mucho más que lo que se traduce, así que solo
                # se envían las entradas que comparten alguna palabra (4+ letras) con lo pendiente.
                words = {w for e, _ in to_translate for w in WORD.findall(" ".join(text_parts(e)).lower())}
                glossary = [g for g in glossary if words & set(WORD.findall(g["es"].lower()))]
            return translate_locale(client, folder, to_translate, glossary, usage)

        print(f"[translateStrings] Traduciendo con {MODEL}…")
        with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_PARALLEL) as pool:
            futures = {pool.submit(run, folder): folder for folder in jobs}
            for future in concurrent.futures.as_completed(futures):
                folder = futures[future]
                try:
                    results[folder] = future.result()
                    print(f"[translateStrings]   values-{folder}: hecho")
                except TranslationError as e:
                    errors[folder] = str(e)
                    print(f"[translateStrings]   values-{folder}: FALLO, {e}", file=sys.stderr)

    # Se escriben los idiomas que han ido bien (lo reutilizado, lo traducido y lo quitado); lo que
    # siga pendiente conserva su traducción anterior, o se queda en español si no tenía.
    for folder, (existing, _, _, reused) in plans.items():
        if folder in errors:
            continue
        translated = {**reused, **results.get(folder, {})}
        elements = []
        for element, _ in base:
            name = element.get("name")
            if name in translated:
                elements.append(translated[name])
            elif name in existing:
                elements.append(existing[name])
        write_locale(folder, elements)
    sync_cyrillic()

    if any(usage):
        cost = (usage[0] * PRICE_PER_MTOK[0] + usage[1] * PRICE_PER_MTOK[1]
                + usage[2] * PRICE_PER_MTOK[2] + usage[3] * PRICE_PER_MTOK[3]) / 1e6
        print(f"[translateStrings] Tokens: {usage[0]} de entrada, {usage[2]} escritos en caché, "
              f"{usage[3]} leídos de caché, {usage[1]} de salida (≈ {cost:.2f} $)")
        # Gradle se traga la salida entre otras mil líneas; este archivo permite leer el coste sin buscarlo.
        (Path(__file__).parent / "last_translate_cost.txt").write_text(
            f"{usage[0]} entrada, {usage[2]} escritos en caché, {usage[3]} leídos de caché, "
            f"{usage[1]} salida: ≈ {cost:.4f} $ ({MODEL})\n", encoding="utf-8")

    # Lo que sigue pendiente tras esta pasada, por clave: en qué idiomas.
    pending: dict[str, list[str]] = {}
    for folder, (_, to_translate, _, _) in plans.items():
        for element, _ in to_translate:
            name = element.get("name")
            if folder in errors or name not in results.get(folder, {}):
                pending.setdefault(name, []).append(folder)

    if pending:
        # Las claves ya resueltas en todos sus idiomas se dan por traducidas; el resto no.
        resolved = sorted(name for name in changed if name not in pending)
        if resolved:
            accept_into_snapshot(resolved)
        if args.pending:
            build_pending_file(args.pending, pending, base_by_name, snapshot_elements, existing_by_folder)
            print(f"[translateStrings] Pendiente escrito en {args.pending}")
        if errors:
            return warn(f"no se han podido traducir {len(errors)} idioma(s): {', '.join(sorted(errors))}")
        return warn(
            f"{len(pending)} string(s) sin traducir: {', '.join(sorted(pending))}. En los idiomas que no "
            "lo tenían se verán en español. Tradúcelos con la skill translate-strings "
            "(o con --api, que cuesta dinero)"
        )
    SNAPSHOT_FILE.write_bytes(BASE_FILE.read_bytes())
    return 0


if __name__ == "__main__":
    sys.exit(main())
