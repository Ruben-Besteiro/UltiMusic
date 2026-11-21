"""
Traduce automáticamente los strings de UltiMusic a los 24 idiomas que no son el español.

La fuente de verdad es `app/src/main/res/values/strings.xml` (español). Este script compara ese
archivo con la copia que guardó la última vez (`tools/strings_snapshot.xml`) y:

  - traduce los strings nuevos o cambiados a cada idioma,
  - traduce también los que le falten a un idioma (así, añadir un idioma nuevo a LOCALES basta para
    que se rellene entero en la siguiente ejecución),
  - quita de todos los idiomas los strings que se hayan borrado del español.

Un string que no se ha tocado en español conserva su traducción actual, así que las correcciones
hechas a mano en un idioma sobreviven hasta que se cambie ese mismo string en español.

Traduce con la API de Claude (modelo claude-sonnet-5-5) usando el SDK oficial `anthropic`
(`pip install anthropic`). La clave se lee de `local.properties` (`anthropic.api_key=...`) o, si no
está, de las credenciales por defecto del SDK (variable ANTHROPIC_API_KEY o `ant auth login`).

Lo lanza solo la tarea `translateStrings` de Gradle antes de cada compilación (ver
app/build.gradle.kts). Si no hay cambios termina en milisegundos sin conectarse a nada. También se
puede lanzar a mano:

    python tools/translate_strings.py              # traduce lo pendiente
    python tools/translate_strings.py --dry-run    # solo enseña qué traduciría
    python tools/translate_strings.py --retranslate tab_songs settings_title
                                                   # vuelve a traducir esas claves en todos los idiomas

La copia `tools/strings_snapshot.xml` solo se actualiza cuando TODOS los idiomas se han traducido
bien: si algo falla (sin conexión, sin clave...), la siguiente compilación lo vuelve a intentar.
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
# Precio de claude-sonnet-5-5 en dólares por millón de tokens (entrada, salida), solo para el resumen.
PRICE_PER_MTOK = (2.00, 10.00)
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
    "b+sr+Latn": ("serbocroata (variedad común serbio/croata), escrito SOLO en alfabeto latino, "
                  "nunca en cirílico", ["one", "few", "other"]),
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


def write_locale(folder: str, elements: list[ET.Element]) -> None:
    path = RES / f"values-{folder}" / "strings.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    body = "\n".join(render(element) for element in elements)
    content = (
        "<resources>\n"
        "    <!-- Generado por tools/translate_strings.py a partir de values/strings.xml. Se puede\n"
        "         corregir a mano: la corrección se mantiene hasta que cambie ese string en español. -->\n\n"
        f"{body}\n"
        "</resources>\n"
    )
    path.write_text(content, encoding="utf-8", newline="\n")


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
    # en todas las peticiones de este idioma, así que las siguientes lo leen de caché (10 % del precio).
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
# Programa principal
# ---------------------------------------------------------------------------------------------

def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    parser = argparse.ArgumentParser(description="Traduce los strings de UltiMusic a todos los idiomas.")
    parser.add_argument("--dry-run", action="store_true", help="solo enseña lo que traduciría")
    parser.add_argument("--retranslate", nargs="+", default=[], metavar="CLAVE",
                        help="vuelve a traducir estas claves en todos los idiomas")
    parser.add_argument("--gradle", action="store_true",
                        help="modo compilación: los fallos son avisos y no paran el build")
    args = parser.parse_args()
    fail_code = 0 if args.gradle else 1

    def warn(message: str) -> int:
        print(f"[translateStrings] AVISO: {message}", file=sys.stderr)
        return fail_code

    try:
        base = [(e, c) for e, c in parse_resources(BASE_FILE) if is_translatable(e)]
    except ET.ParseError as e:
        return warn(f"values/strings.xml no es XML válido ({e}); no se traduce nada")
    base_names = {e.get("name") for e, _ in base}

    unknown = [k for k in args.retranslate if k not in base_names]
    if unknown:
        return warn(f"estas claves no existen en values/strings.xml: {', '.join(unknown)}")

    # Sin copia previa se asume que los idiomas están al día con el español actual.
    if not SNAPSHOT_FILE.exists():
        SNAPSHOT_FILE.write_bytes(BASE_FILE.read_bytes())
        print("[translateStrings] Creada tools/strings_snapshot.xml con el español actual.")
    snapshot = {e.get("name"): canonical(e) for e, _ in parse_resources(SNAPSHOT_FILE)}
    changed = {e.get("name") for e, _ in base if snapshot.get(e.get("name")) != canonical(e)}
    changed |= set(args.retranslate)

    # Qué hay que hacer en cada idioma.
    plans = {}
    for folder in LOCALES:
        path = RES / f"values-{folder}" / "strings.xml"
        try:
            existing = {e.get("name"): e for e, _ in parse_resources(path)} if path.exists() else {}
        except ET.ParseError as e:
            return warn(f"values-{folder}/strings.xml no es XML válido ({e}); corrígelo antes de traducir")
        to_translate = [(e, c) for e, c in base if e.get("name") in changed or e.get("name") not in existing]
        removed = sorted(set(existing) - base_names)
        if to_translate or removed:
            plans[folder] = (existing, to_translate, removed)

    if not plans:
        if changed and not args.dry_run:
            SNAPSHOT_FILE.write_bytes(BASE_FILE.read_bytes())
        return 0

    for folder, (_, to_translate, removed) in plans.items():
        parts = []
        if to_translate:
            parts.append(f"traducir {len(to_translate)}")
        if removed:
            parts.append(f"quitar {len(removed)} ({', '.join(removed)})")
        print(f"[translateStrings] values-{folder}: {', '.join(parts)}")
    if args.dry_run:
        return 0

    jobs = {folder: plan for folder, plan in plans.items() if plan[1]}
    results: dict[str, dict[str, ET.Element]] = {}
    errors: dict[str, str] = {}
    usage = [0, 0, 0, 0]  # entrada sin caché, salida, escritura en caché, lectura de caché

    if jobs:
        try:
            import anthropic
        except ImportError:
            return warn("falta el SDK de Claude para Python. Instálalo con: pip install anthropic")
        api_key = read_api_key()
        client = anthropic.Anthropic(api_key=api_key) if api_key else anthropic.Anthropic()

        def run(folder: str) -> dict[str, ET.Element]:
            existing, to_translate, _ = jobs[folder]
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

    # Se escriben los idiomas que han ido bien; los demás se quedan como estaban.
    for folder, (existing, to_translate, _) in plans.items():
        if folder in errors:
            continue
        translated = results.get(folder, {})
        elements = []
        for element, _ in base:
            name = element.get("name")
            if name in translated:
                elements.append(translated[name])
            elif name in existing:
                elements.append(existing[name])
        write_locale(folder, elements)

    if any(usage):
        cost = (usage[0] * PRICE_PER_MTOK[0] + usage[1] * PRICE_PER_MTOK[1]
                + usage[2] * PRICE_PER_MTOK[0] * 1.25 + usage[3] * PRICE_PER_MTOK[0] * 0.1) / 1e6
        print(f"[translateStrings] Tokens: {usage[0]} de entrada, {usage[2]} escritos en caché, "
              f"{usage[3]} leídos de caché, {usage[1]} de salida (≈ {cost:.2f} $)")
        # Gradle se traga la salida entre otras mil líneas; este archivo permite leer el coste sin buscarlo.
        (Path(__file__).parent / "last_translate_cost.txt").write_text(
            f"{usage[0]} entrada, {usage[2]} escritos en caché, {usage[3]} leídos de caché, "
            f"{usage[1]} salida: ≈ {cost:.4f} $ ({MODEL})\n", encoding="utf-8")

    if errors:
        return warn(
            f"no se han podido traducir {len(errors)} idioma(s): {', '.join(sorted(errors))}. "
            "Se volverá a intentar en la próxima compilación"
        )
    SNAPSHOT_FILE.write_bytes(BASE_FILE.read_bytes())
    return 0


if __name__ == "__main__":
    sys.exit(main())
