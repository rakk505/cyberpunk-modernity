#!/usr/bin/env python3
"""Import Cyberpunk 2077 cyberware from the Fandom wiki.

The importer intentionally keeps the source wikitext beside a resolved effect string for every
quality grade.  Numeric ranges such as ``{{R|TI|20}}-{{R|TV|40}}`` are evaluated for each of the
eleven upgrade grades (T1, T1+, ... T5++) by piecewise linear interpolation between the explicit
wiki anchors.  Slash-delimited values are treated as stepwise tier values.

It also downloads one lossless reference icon per cyberware family.  The references are converted
to Minecraft textures in a separate, explicit pixelart-downsample step so the art pipeline remains
auditable.
"""

from __future__ import annotations

import argparse
import html
import io
import json
import math
import re
import unicodedata
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from PIL import Image


WIKI_ROOT = "https://cyberpunk.fandom.com"
MASTER_PAGE = "Cyberpunk 2077 Cyberware"
MASTER_URL = f"{WIKI_ROOT}/wiki/Cyberpunk_2077_Cyberware"
API_URL = f"{WIKI_ROOT}/api.php"
USER_AGENT = "Cyberpunk Modernity cyberware importer/1.0 (local mod asset pipeline)"

SLOT_NAMES = (
    "Frontal Cortex",
    "Operating System",
    "Arms",
    "Face",
    "Skeleton",
    "Hands",
    "Nervous System",
    "Circulatory System",
    "Integumentary System",
    "Legs",
)

SLOT_IDS = {
    "Frontal Cortex": "FRONTAL_CORTEX",
    "Operating System": "OPERATING_SYSTEM",
    "Arms": "ARMS",
    "Face": "FACE",
    "Skeleton": "SKELETON",
    "Hands": "HANDS",
    "Nervous System": "NERVOUS_SYSTEM",
    "Circulatory System": "CIRCULATORY_SYSTEM",
    "Integumentary System": "INTEGUMENTARY_SYSTEM",
    "Legs": "LEGS",
}

TIERS = (
    ("T1", "Tier 1", "t1"),
    ("T1+", "Tier 1+", "t1_plus"),
    ("T2", "Tier 2", "t2"),
    ("T2+", "Tier 2+", "t2_plus"),
    ("T3", "Tier 3", "t3"),
    ("T3+", "Tier 3+", "t3_plus"),
    ("T4", "Tier 4", "t4"),
    ("T4+", "Tier 4+", "t4_plus"),
    ("T5", "Tier 5", "t5"),
    ("T5+", "Tier 5+", "t5_plus"),
    ("T5++", "Tier 5++", "t5_plus_plus"),
)

WIKI_TIER_TO_RANK = {
    "TI": 0,
    "TI+": 1,
    "TII": 2,
    "TII+": 3,
    "TIII": 4,
    "TIII+": 5,
    "TIV": 6,
    "TIV+": 7,
    "TV": 8,
    "TV+": 9,
    "TV++": 10,
}

SLUG_OVERRIDES = {
    "Apogee": "militech_apogee",
    "Falcon": "militech_falcon",
    "Warp Dancer": "qiant_warp_dancer",
    "ThreatEvac": "threat_evac",
}


@dataclass
class MasterRow:
    slot: str
    name: str
    page: str
    href: str
    image_url: str
    rendered_quality: str
    rendered_capacity: str
    rendered_armor: str
    rendered_effect: str
    os_type: str


class CyberwareTableParser(HTMLParser):
    """Small dependency-free parser for the first DPL table below each slot heading."""

    def __init__(self) -> None:
        super().__init__()
        self.heading_tag: str | None = None
        self.heading_text = ""
        self.section = ""
        self.in_table = False
        self.finished_sections: set[str] = set()
        self.row: list[dict[str, str]] | None = None
        self.cell: dict[str, str] | None = None
        self.tables: dict[str, list[list[dict[str, str]]]] = defaultdict(list)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = {key: value or "" for key, value in attrs}
        if tag in {"h2", "h3"}:
            self.heading_tag = tag
            self.heading_text = ""
        elif (
            tag == "table"
            and self.section in SLOT_NAMES
            and self.section not in self.finished_sections
        ):
            self.in_table = True
            self.finished_sections.add(self.section)
        elif self.in_table and tag == "tr":
            self.row = []
        elif self.in_table and self.row is not None and tag in {"td", "th"}:
            self.cell = {"text": "", "href": "", "image": ""}
        elif self.cell is not None and tag == "a":
            href = attributes.get("href", "")
            if not self.cell["href"] and href.startswith("/wiki/"):
                self.cell["href"] = href
        elif self.cell is not None and tag == "img" and not self.cell["image"]:
            self.cell["image"] = attributes.get("data-src") or attributes.get("src", "")
        elif self.cell is not None and tag in {"br", "li"}:
            self.cell["text"] += "\n"

    def handle_endtag(self, tag: str) -> None:
        if self.heading_tag == tag:
            if tag == "h3":
                self.section = self.heading_text.replace("[]", "").strip()
            else:
                self.section = ""
            self.heading_tag = None
        elif tag == "table" and self.in_table:
            self.in_table = False
        elif (
            self.in_table
            and self.row is not None
            and tag in {"td", "th"}
            and self.cell is not None
        ):
            self.cell["text"] = clean_whitespace(self.cell["text"])
            self.row.append(self.cell)
            self.cell = None
        elif self.in_table and tag == "tr" and self.row is not None:
            if self.row:
                self.tables[self.section].append(self.row)
            self.row = None

    def handle_data(self, data: str) -> None:
        if self.heading_tag is not None:
            self.heading_text += data
        if self.cell is not None:
            self.cell["text"] += data


def clean_whitespace(value: str) -> str:
    lines = [" ".join(line.split()) for line in value.splitlines()]
    return "\n".join(line for line in lines if line).strip()


def api_json(params: dict[str, str]) -> dict[str, Any]:
    url = API_URL + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def fetch_master_rows() -> list[MasterRow]:
    parsed = api_json(
        {
            "action": "parse",
            "page": MASTER_PAGE,
            "prop": "text",
            "format": "json",
            "formatversion": "2",
        }
    )["parse"]
    parser = CyberwareTableParser()
    parser.feed(parsed["text"])

    rows: list[MasterRow] = []
    for slot in SLOT_NAMES:
        table = parser.tables.get(slot, [])
        if len(table) < 2:
            raise RuntimeError(f"No rendered cyberware table found for {slot}")
        headers = [cell["text"] for cell in table[0]]
        for cells in table[1:]:
            if len(cells) != len(headers) or len(cells) < 5:
                continue
            values = {headers[index]: cells[index]["text"] for index in range(len(headers))}
            title_cell = cells[1]
            href = title_cell["href"]
            if not href:
                continue
            page = urllib.parse.unquote(href.split("/wiki/", 1)[1]).replace("_", " ")
            rows.append(
                MasterRow(
                    slot=slot,
                    name=values["Title"],
                    page=page,
                    href=href,
                    image_url=cells[0]["image"],
                    rendered_quality=values.get("Quality", ""),
                    rendered_capacity=values.get("Capacity", ""),
                    rendered_armor=values.get("Armor", ""),
                    rendered_effect=values.get("Effect(s)", ""),
                    os_type=values.get("Type", ""),
                )
            )
    if len(rows) < 100:
        raise RuntimeError(f"Expected a full catalog, found only {len(rows)} entries")
    return rows


def fetch_page_wikitext(rows: list[MasterRow]) -> dict[str, str]:
    pages: dict[str, str] = {}
    for start in range(0, len(rows), 50):
        batch = rows[start : start + 50]
        result = api_json(
            {
                "action": "query",
                "prop": "revisions",
                "rvprop": "content",
                "rvslots": "main",
                "titles": "|".join(row.page for row in batch),
                "redirects": "1",
                "format": "json",
                "formatversion": "2",
            }
        )
        for page in result["query"]["pages"]:
            revisions = page.get("revisions", [])
            content = revisions[0]["slots"]["main"].get("content", "") if revisions else ""
            pages[page["title"]] = content
    missing = [row.page for row in rows if row.page not in pages]
    if missing:
        raise RuntimeError(f"Missing page wikitext: {', '.join(missing)}")
    return pages


def extract_template(wikitext: str, name: str) -> str:
    start = wikitext.find("{{" + name)
    if start < 0:
        return ""
    depth = 0
    index = start
    while index < len(wikitext) - 1:
        pair = wikitext[index : index + 2]
        if pair == "{{":
            depth += 1
            index += 2
            continue
        if pair == "}}":
            depth -= 1
            index += 2
            if depth == 0:
                return wikitext[start:index]
            continue
        index += 1
    return ""


def template_fields(template: str) -> dict[str, str]:
    if not template:
        return {}
    body = template[2:-2]
    parts: list[str] = []
    buffer: list[str] = []
    curly_depth = 0
    square_depth = 0
    index = 0
    while index < len(body):
        pair = body[index : index + 2]
        if pair == "{{":
            curly_depth += 1
            buffer.append(pair)
            index += 2
            continue
        if pair == "}}":
            curly_depth -= 1
            buffer.append(pair)
            index += 2
            continue
        if pair == "[[":
            square_depth += 1
            buffer.append(pair)
            index += 2
            continue
        if pair == "]]":
            square_depth -= 1
            buffer.append(pair)
            index += 2
            continue
        if body[index] == "|" and curly_depth == 0 and square_depth == 0:
            parts.append("".join(buffer))
            buffer = []
            index += 1
            continue
        buffer.append(body[index])
        index += 1
    parts.append("".join(buffer))

    fields: dict[str, str] = {}
    for part in parts[1:]:
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        fields[key.strip()] = value.strip()
    return fields


TOKEN_RE = re.compile(
    r"\{\{R\|(T(?:III|II|IV|I|V)(?:\+\+|\+)?)\|([^{}|]+)(?:\|[^{}]*)?\}\}"
)


def parse_numeric(value: str) -> float | None:
    normalized = html.unescape(value).replace("−", "-").replace(",", "").strip()
    match = re.fullmatch(r"[+\-]?\d+(?:\.\d+)?", normalized)
    return float(normalized) if match else None


def format_number(value: float) -> str:
    if math.isclose(value, round(value), abs_tol=1.0e-9):
        return str(int(round(value)))
    return f"{value:.2f}".rstrip("0").rstrip(".")


def token_rank(match: re.Match[str]) -> int:
    return WIKI_TIER_TO_RANK[match.group(1)]


def resolve_tier_values(wikitext: str, tier_rank: int) -> str:
    """Resolve all explicit tier annotations into one tier-specific wikitext string."""
    value = wikitext

    # Replace numeric endpoint ranges from right to left so indices remain valid.
    tokens = list(TOKEN_RE.finditer(value))
    replacements: list[tuple[int, int, str]] = []
    used: set[int] = set()
    for index in range(len(tokens) - 1):
        first = tokens[index]
        second = tokens[index + 1]
        between = html.unescape(value[first.end() : second.start()]).strip()
        if between not in {"-", "–", "—"}:
            continue
        first_number = parse_numeric(first.group(2))
        second_number = parse_numeric(second.group(2))
        if first_number is None or second_number is None:
            continue
        first_rank = token_rank(first)
        second_rank = token_rank(second)
        if second_rank == first_rank:
            resolved = second_number
        else:
            fraction = (tier_rank - first_rank) / (second_rank - first_rank)
            fraction = max(0.0, min(1.0, fraction))
            resolved = first_number + (second_number - first_number) * fraction
        replacements.append((first.start(), second.end(), format_number(resolved)))
        used.update({index, index + 1})
    for start, end, replacement in reversed(replacements):
        value = value[:start] + replacement + value[end:]

    # Slash-delimited annotated values are discrete/stepwise rather than interpolated.
    tokens = list(TOKEN_RE.finditer(value))
    runs: list[list[re.Match[str]]] = []
    current: list[re.Match[str]] = []
    for token in tokens:
        if not current:
            current = [token]
            continue
        between = value[current[-1].end() : token.start()].strip()
        if between == "/":
            current.append(token)
        else:
            if len(current) > 1:
                runs.append(current)
            current = [token]
    if len(current) > 1:
        runs.append(current)
    for run in reversed(runs):
        eligible = [token for token in run if token_rank(token) <= tier_rank]
        selected = eligible[-1] if eligible else run[0]
        value = value[: run[0].start()] + selected.group(2) + value[run[-1].end() :]

    # A lone annotated value is the value applicable at its endpoint and remains constant elsewhere.
    value = TOKEN_RE.sub(lambda match: match.group(2), value)
    return clean_wikitext(value)


def clean_wikitext(value: str) -> str:
    value = re.sub(r"<br\s*/?>", "\n", value, flags=re.IGNORECASE)
    value = re.sub(r"<ref\b[^>]*>.*?</ref>", "", value, flags=re.IGNORECASE | re.DOTALL)
    value = re.sub(r"<[^>]+>", "", value)
    value = re.sub(r"\[\[[^\]|]+\|([^\]]+)\]\]", r"\1", value)
    value = re.sub(r"\[\[([^\]]+)\]\]", r"\1", value)
    value = re.sub(r"\{\{R\|I\|([^{}|]+)(?:\|[^{}]*)?\}\}", r"\1", value)
    value = re.sub(r"\{\{R\|([^{}|]+)\}\}", r"\1", value)
    value = re.sub(r"\{\{[^{}]+\}\}", "", value)
    value = html.unescape(value)
    value = value.replace("&minus;", "−").replace("&plus;", "+")
    value = value.replace("−", "−")
    return clean_whitespace(value)


def first_quality_rank(quality: str) -> int:
    match = re.search(r"\{\{R\|(T(?:III|II|IV|I|V)(?:\+\+|\+)?)", quality)
    if not match:
        raise ValueError(f"Cannot determine minimum tier from {quality!r}")
    return WIKI_TIER_TO_RANK[match.group(1)]


def tier_ranks(fields: dict[str, str]) -> list[int]:
    minimum = first_quality_rank(fields.get("quality", ""))
    base_ids = [
        part.strip()
        for part in re.split(r"<br\s*/?>", fields.get("baseid", ""), flags=re.IGNORECASE)
        if part.strip()
    ]
    annotated = [
        WIKI_TIER_TO_RANK[match.group(1)]
        for source in (fields.get("effects", ""), fields.get("armor", ""))
        for match in TOKEN_RE.finditer(source)
    ]
    # Unique quest/tattoo implants with one game record and no tier-varying source value are fixed.
    if len(base_ids) <= 1 and (not annotated or max(annotated) == min(annotated)):
        return [minimum]
    if len(base_ids) <= 1:
        return list(range(minimum, max(annotated) + 1))
    # Standard cyberware has one record for every half tier through T5++; tolerate malformed wiki
    # baseid lists and use the explicit effect endpoint to restore an omitted final record.
    return list(range(minimum, 11))


def slugify(name: str) -> str:
    if name in SLUG_OVERRIDES:
        return SLUG_OVERRIDES[name]
    normalized = unicodedata.normalize("NFKD", name.replace("Σ", " Sigma "))
    ascii_name = normalized.encode("ascii", "ignore").decode("ascii").lower()
    ascii_name = ascii_name.replace("'", "")
    return re.sub(r"[^a-z0-9]+", "_", ascii_name).strip("_")


def number_before(text: str, phrase: str) -> float | None:
    normalized = text.replace("−", "-")
    matches = list(
        re.finditer(r"([+\-]?\d+(?:\.\d+)?)\s*%?\s*" + phrase, normalized, re.IGNORECASE)
    )
    return float(matches[0].group(1)) if matches else None


def number_after(text: str, phrase: str) -> float | None:
    normalized = text.replace("−", "-")
    match = re.search(phrase + r"[^\d+\-]*([+\-]?\d+(?:\.\d+)?)", normalized, re.IGNORECASE)
    return float(match.group(1)) if match else None


def percentage(text: str, phrase: str) -> float | None:
    return number_before(text, re.escape(phrase))


def build_mechanics(
    family_id: str,
    name: str,
    slot: str,
    os_type: str,
    effect: str,
    armor: float,
    tier_rank: int,
) -> dict[str, Any]:
    """Create normalized values consumed by the server-side effect engine.

    The complete human-readable effect remains authoritative in ``effect``; this map contains only
    values that have a direct Minecraft analogue.
    """
    values: dict[str, float] = {}
    flags: list[str] = []
    lower = effect.lower()

    if armor > 0:
        # CP2077 armor numbers are much larger than Minecraft's; ten source points equal one armor
        # point so tier progression remains exact without making low tiers invulnerable.
        values["armor_points"] = armor / 10.0

    match = re.search(r"([+−-]?\d+(?:\.\d+)?)%\s+Max Health", effect, re.IGNORECASE)
    if match:
        values["max_health_percent"] = float(match.group(1).replace("−", "-"))
    match = re.search(r"([+−-]?\d+(?:\.\d+)?)\s+Max RAM", effect, re.IGNORECASE)
    if match:
        values["max_ram"] = float(match.group(1).replace("−", "-"))

    direct_family_values: dict[str, tuple[str, str]] = {
        "leeroy_ligament_system": ("movement_speed_percent", "Movement Speed"),
        "dense_marrow": ("melee_damage_percent", "melee damage"),
        "microrotors": ("attack_speed_percent", "melee attack speed"),
        "pain_editor": ("incoming_damage_reduction_percent", "all incoming damage"),
        "titanium_bones": ("carry_capacity_percent", "Carrying Capacity"),
        "shock_absorber": ("recoil_reduction_percent", "recoil"),
        "immovable_force": ("recoil_reduction_percent", "recoil"),
        "clutch_padding": ("ranged_stamina_reduction_percent", "Stamina cost for shooting"),
        "isometric_stabilizer": ("stamina_reduction_percent", "Stamina cost for all attacks"),
        "cockatrice": ("crit_chance_percent", "Crit Chance"),
        "stabber": ("blade_crit_chance_percent", "Crit Chance with Blades"),
        "lynx_paws": ("quiet_movement_percent", "quieter movement"),
    }
    if family_id in direct_family_values:
        key, phrase = direct_family_values[family_id]
        found = percentage(effect, phrase)
        if found is not None:
            values[key] = abs(found) if "reduction" in key else found
    if family_id == "cockatrice":
        values["crit_chance_percent"] = number_after(effect, r"Crit Chance by") or 0
    if family_id == "immovable_force":
        values["spread_reduction_percent"] = abs(percentage(effect, "bullet spread") or 0)

    if family_id in {"para_bellum", "rara_avis"}:
        found = percentage(effect, "Armor")
        if found is not None:
            values["armor_multiplier_percent"] = found
    if family_id == "neofiber":
        values["mitigation_chance_percent"] = percentage(effect, "Mitigation Chance") or 0
        values["mitigation_strength_percent"] = percentage(effect, "Mitigation Strength") or 0
    if family_id == "spring_joints":
        values["mitigation_strength_percent"] = percentage(effect, "Mitigation Strength") or 0
    if family_id == "ram_upgrade":
        found = number_after(effect, r"RAM recovery rate by")
        if found is not None:
            values["ram_regen_per_second"] = found
    if family_id in {"axolotl", "newton_module"}:
        found = percentage(effect, "Cooldown")
        if found is not None:
            values["cooldown_on_kill_percent"] = abs(found)
    if family_id == "memory_boost":
        found = number_before(effect, r"RAM when you neutralize")
        if found is not None:
            values["ram_on_kill"] = found
    if family_id in {"bioconductor", "cox_2_cybersomatic_optimizer"}:
        values["quickhack_crit_chance_percent"] = percentage(effect, "Crit Chance with quickhacks") or 0
        flags.append("quickhack_crit")
    if family_id == "ex_disk":
        values["quickhack_upload_speed_percent"] = percentage(effect, "upload speed") or 0
    if family_id == "mechatronic_core":
        values["mechanical_damage_percent"] = percentage(effect, "damage against drones") or 0
    if family_id in {"camillo_ram_manager", "ram_reallocator"}:
        values["low_ram_restore_percent"] = percentage(effect, "Max RAM") or 0
        values["low_ram_threshold_percent"] = number_after(effect, r"falls to") or 0
        values["trigger_cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
        flags.append("low_ram_restore")
    if family_id == "quantum_tuner":
        values["cooldown_reduction_percent"] = abs(percentage(effect, "Cooldown Time") or 0)
        values["trigger_cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
        values["cooldown_restore_max_seconds"] = number_after(effect, r"up to a max of") or 0
        flags.append("quantum_tuner")
    if family_id == "self_ice":
        values["trigger_cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
        flags.append("self_ice")
    if family_id == "kerenzikov_boost_system":
        flags.append("kerenzikov_boost")
        values["ranged_stamina_reduction_percent"] = abs(
            percentage(effect, "Stamina cost from shooting") or 0
        )
        values["time_slow_bonus_percent"] = number_after(effect, r"Slows time by") or 0

    if family_id in {"adrenaline_converter", "adreno_trigger"}:
        flags.append("combat_entry_speed")
        values["combat_speed_percent"] = percentage(effect, "movement speed") or 0
        values["combat_speed_seconds"] = number_before(effect, r"sec\. when entering combat") or 0
    if family_id == "atomic_sensors":
        flags.append("detection_speed")
        values["detection_speed_percent"] = percentage(effect, "movement speed at") or 0
    if family_id in {"deep_field_visual_interface", "visual_cortex_support"}:
        flags.append("distance_crit")
        values["distance_crit_percent"] = number_after(effect, r"max\.") or 0
        distance = re.search(r"at\s+(\d+(?:\.\d+)?)m", effect, re.IGNORECASE)
        values["distance_crit_range"] = float(distance.group(1)) if distance else 30
    if family_id in {"reflex_tuner", "revulsor"}:
        flags.append("low_health_time_slow")
        values["time_slow_percent"] = number_after(effect, r"Slows time by") or 0
        values["duration_seconds"] = number_before(effect, r"sec\. when your Health") or 0
        values["cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
    if family_id == "synaptic_accelerator":
        flags.append("detection_time_slow")
        values["time_slow_percent"] = number_after(effect, r"Slows time by") or 0
        values["duration_seconds"] = number_before(effect, r"sec\. when enemy detection") or 0
        values["cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
    if family_id == "kerenzikov":
        flags.append("kerenzikov")
        values["time_slow_percent"] = number_after(effect, r"Slows time by") or 0
        values["duration_seconds"] = number_before(effect, r"sec\. when you aim") or 0
        values["cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
    if family_id == "tyrosine_injector":
        flags.append("takedown_boost")
        values["kill_headshot_percent"] = percentage(effect, "headshot damage") or 0
        values["kill_movement_percent"] = percentage(effect, "Movement Speed") or 0

    if family_id == "kinetic_frame":
        flags.append("high_stamina_mitigation")
        values["mitigation_chance_percent"] = percentage(effect, "Mitigation Chance") or 0
    if family_id == "feen_x":
        flags.append("low_ram_regen")
        values["low_ram_regen_percent"] = percentage(effect, "RAM Regen Rate") or 0
        values["low_ram_threshold"] = number_after(effect, r"below") or 0
    if family_id == "ram_recoup":
        flags.append("ram_on_damage")
        values["ram_damage_percent"] = percentage(effect, "of damage received") or 0
    if family_id == "scar_coalescer":
        flags.append("low_health_armor")
        values["conditional_armor_percent"] = percentage(effect, "Armor when below") or 0
    if family_id == "scarab":
        flags.append("crouch_armor")
        values["crouch_armor"] = number_after(effect, r"When crouched:\s*\+") or 0
        values["crouch_speed_penalty_percent"] = abs(percentage(effect, "Movement Speed") or 0)
    if family_id == "universal_booster":
        flags.append("health_item_boost")

    normalized_os_type = os_type.lower()
    if "sandevistan" in normalized_os_type:
        flags.append("sandevistan")
        slow_values = [
            float(value)
            for value in re.findall(r"Slows time by\s+(\d+(?:\.\d+)?)%", effect, re.IGNORECASE)
        ]
        damage_values = [
            float(value)
            for value in re.findall(r"\+(\d+(?:\.\d+)?)%\s+damage(?:\s|$)", effect, re.IGNORECASE)
        ]
        headshot_values = [
            float(value)
            for value in re.findall(r"\+(\d+(?:\.\d+)?)%\s+headshot", effect, re.IGNORECASE)
        ]
        values["time_slow_percent"] = slow_values[0] if slow_values else 0
        values["duration_seconds"] = number_after(effect, r"Max duration:") or 0
        values["cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
        values["crit_chance_percent"] = percentage(effect, "Crit Chance") or 0
        values["crit_damage_percent"] = percentage(effect, "Crit Damage") or 0
        values["active_damage_percent"] = damage_values[0] if damage_values else 0
        values["headshot_damage_percent"] = headshot_values[0] if headshot_values else 0
        values["kill_duration_percent"] = percentage(effect, "extended duration") or 0
        values["kill_health_percent"] = percentage(effect, "Health") or 0
        values["kill_stamina_percent"] = percentage(effect, "Stamina") or 0
        if family_id == "zetatech_sandevistan":
            values["airborne_time_slow_percent"] = slow_values[1] if len(slow_values) > 1 else 0
            values["airborne_damage_percent"] = damage_values[1] if len(damage_values) > 1 else 0
            values["airborne_headshot_percent"] = headshot_values[0] if headshot_values else 0
            values["headshot_damage_percent"] = 0
            values["fall_damage_reduction_percent"] = abs(
                percentage(effect, "fall damage") or 0
            )
        if family_id == "qiant_warp_dancer":
            values["mitigation_chance_percent"] = percentage(effect, "Mitigation Chance") or 0
            values["mitigation_strength_percent"] = percentage(effect, "Mitigation Strength") or 0
            values["elemental_resistance_percent"] = percentage(
                effect, "resistance to Thermal"
            ) or 0
    elif "berserk" in normalized_os_type:
        flags.extend(["berserk", "melee_only"])
        if "invulnerable" in lower:
            flags.append("invulnerable")
        values["active_damage_reduction_percent"] = percentage(effect, "damage reduction") or 100
        values["active_attack_speed_percent"] = percentage(effect, "attack speed") or 0
        values["active_movement_speed_percent"] = percentage(effect, "Movement Speed") or 0
        values["active_crit_chance_percent"] = percentage(effect, "Crit Chance") or 0
        values["active_crit_damage_percent"] = percentage(effect, "Crit Damage") or 0
        values["fall_damage_reduction_percent"] = abs(percentage(effect, "fall damage") or 0)
        values["duration_seconds"] = number_after(effect, r"Duration:") or 0
        values["cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
    elif "cyberdeck" in normalized_os_type:
        flags.append("cyberdeck")
    elif family_id == "chrome_compressor":
        flags.append("chrome_compressor")
        values["capacity_bonus"] = number_before(effect, r"Cyberware Capacity") or 0

    if family_id == "arasaka_mk_1_5":
        flags.append("takedown_ram")
        values["takedown_ram"] = number_before(effect, r"RAM after performing a Takedown") or 0
    if family_id == "biotech_sigma_mk_1_4":
        flags.append("quickhack_dot")
        values["quickhack_duration_percent"] = abs(percentage(
            effect, "duration for Combat quickhacks"
        ) or 0)
        values["quickhack_dot_damage_percent"] = percentage(
            effect, "damage-over-time with quickhacks"
        ) or 0
        values["monowire_dot_damage_percent"] = percentage(effect, "Monowire damage") or 0
    if family_id == "canto_mk_6":
        flags.append("blackwall_gateway")
    if family_id == "paraline_mk_1_5":
        flags.append("paraline")
        values["quickhack_damage_percent"] = percentage(effect, "quickhack damage") or 0
        values["monowire_ram_damage_percent"] = percentage(
            effect, "Monowire damage per used RAM unit"
        ) or 0
        values["monowire_ram_damage_cap_percent"] = number_after(effect, r"max\.") or 0
    if family_id == "netdriver_mk_1":
        flags.append("device_specialist")
        values["quickhack_ram_cost_reduction_percent"] = abs(
            percentage(effect, "RAM cost for Device") or 0
        )
    if family_id == "raven_microcyber_mk_1_3":
        flags.append("quickhack_spread")
        values["quickhack_spread_distance_percent"] = abs(percentage(
            effect, "spread distance with quickhacks"
        ) or 0)
    if family_id == "rippler_mk_1_5":
        flags.append("quickhack_combo")
        values["quickhack_combo_damage_percent"] = percentage(
            effect, "damage with Combat quickhacks"
        ) or 0

    if slot == "ARMS":
        if "gorilla_arms" in family_id:
            flags.append("gorilla_arms")
        elif "mantis_blades" in family_id:
            flags.append("mantis_blades")
        elif "monowire" in family_id:
            flags.append("monowire")
        elif "projectile_launch_system" in family_id:
            flags.append("projectile_launcher")
        for element in ("electrical", "thermal", "chemical", "physical"):
            if (
                f"deal {element} damage" in lower
                or f"deals massive {element} damage" in lower
                or element in family_id
                or (element == "electrical" and "electrifying" in family_id)
            ):
                flags.append("damage_" + element)
                break
        for status in ("shock", "burn", "poison", "bleeding"):
            found = percentage(effect, f"{status} chance")
            if found is not None:
                values["status_chance_percent"] = found
                flags.append("status_" + status)
                break

    smart_families = {
        "smart_link",
        "tattoo_johnnys_special",
        "tattoo_together_forever",
        "tattoo_tyger_claws_dermal_imprint",
    }
    if family_id in smart_families:
        flags.append("smart_targeting")
    if family_id == "smart_link":
        values["smart_lock_duration_percent"] = percentage(effect, "target-lock duration") or 0
        values["smart_crit_damage_percent"] = percentage(effect, "Crit Damage with Smart weapons") or 0
    if family_id == "tattoo_tyger_claws_dermal_imprint":
        values["smart_lock_speed_percent"] = percentage(effect, "lock-on speed") or 0
    if family_id == "ballistic_coprocessor":
        flags.append("ricochet")
        values["ricochet_chance_percent"] = 20
        values["ricochet_damage_percent"] = percentage(effect, "ricochet damage") or 0
    if family_id == "handle_wrap":
        flags.append("throwable_crit")
        values["throwable_crit_chance_percent"] = percentage(
            effect, "Crit Chance with throwable weapons"
        ) or 0
        values["duration_seconds"] = number_before(
            effect, r"sec\."
        ) or 6
    if family_id == "microgenerator":
        flags.append("reload_shock")
        values["reload_shock_damage"] = number_after(effect, r"deals up to") or 0
    if family_id == "reinforced_tendons":
        flags.append("double_jump")
    if family_id == "fortified_ankles":
        flags.append("charged_jump")
    if family_id == "optical_camo":
        flags.append("optical_camo")
        values["visibility_reduction_percent"] = abs(percentage(effect, "visibility to enemies") or 0)
        values["duration_seconds"] = number_before(effect, r"sec\., making") or 0
        values["cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
    if family_id == "nano_plating":
        flags.append("projectile_block")
        values["projectile_block_chance_percent"] = number_after(effect, "") or 0
    if family_id == "second_heart":
        flags.append("second_heart")
        values["cooldown_seconds"] = number_after(effect, r"Cooldown:") or 0
    if family_id == "biomonitor":
        flags.append("biomonitor")
        values["health_item_effectiveness_percent"] = percentage(
            effect, "Health item effectiveness"
        ) or 0
    if family_id == "blood_pump":
        flags.append("blood_pump")
        values["blood_pump_instant_health"] = number_after(effect, r"restores") or 0
        values["blood_pump_regen_per_second"] = number_after(effect, r"regenerates") or 0
    if family_id == "heal_on_kill":
        values["health_on_kill_percent"] = percentage(effect, "Health when you neutralize") or 0
    if family_id == "adrenaline_booster":
        values["stamina_on_melee_kill_percent"] = percentage(effect, "Stamina whenever") or 0
    if family_id == "threat_evac":
        flags.append("low_health_speed")
        values["low_health_speed_percent"] = percentage(effect, "movement speed when") or 0
        all_speed = [float(v) for v in re.findall(r"\+(\d+(?:\.\d+)?)%", effect)]
        values["critical_health_speed_percent"] = all_speed[-1] if all_speed else 0
    if family_id == "subdermal_armor":
        flags.append("subdermal_armor")
    if family_id == "behavioral_imprint_synced_faceplate":
        flags.append("behavioral_identity")
    if family_id in {"clairvoyant", "doomsayer", "sentry", "the_oracle"}:
        flags.append("scanner_highlight")
    if family_id in {"basic_kiroshi_optics", "doomsayer", "sentry", "stalker"}:
        flags.append("kiroshi_optics")
    if family_id == "basic_kiroshi_optics":
        values["detection_reduction_percent"] = abs(
            percentage(effect, "camera detection speed") or 0
        )
        values["scanner_enemy_range"] = 48
    scanner_ranges = [
        float(value)
        for value in re.findall(r"within\s+(\d+(?:\.\d+)?)\s*m", effect, re.IGNORECASE)
    ]
    if family_id == "clairvoyant" and scanner_ranges:
        values["scanner_enemy_range"] = scanner_ranges[0]
    if family_id == "doomsayer" and scanner_ranges:
        values["scanner_explosive_range"] = scanner_ranges[0]
    if family_id == "sentry" and scanner_ranges:
        values["scanner_device_range"] = scanner_ranges[0]
    if family_id == "the_oracle":
        if scanner_ranges:
            values["scanner_enemy_range"] = scanner_ranges[0]
        if len(scanner_ranges) > 1:
            values["scanner_device_range"] = scanner_ranges[1]
        if len(scanner_ranges) > 2:
            values["scanner_explosive_range"] = scanner_ranges[2]
    if family_id == "stalker":
        flags.append("tech_targeting")
        values["scanner_wall_range"] = number_after(effect, r"up to") or 0

    if family_id == "heal_on_kill":
        flags.append("health_on_kill")
    if family_id == "feedback_circuit":
        flags.append("health_on_tech_hit")
        values["health_on_tech_hit_percent"] = percentage(effect, "Health when you hit") or 0
    if family_id == "electromag_recycler":
        flags.append("health_stamina_on_tech_hit")
        values["health_stamina_on_tech_hit_percent"] = percentage(effect, "Health and Stamina") or 0
    if family_id == "black_mamba":
        flags.append("poison_synergy")
        values["poison_other_damage_percent"] = percentage(effect, "all other damage") or 0
    if family_id == "microrotors":
        flags.append("melee_attack_speed")
    if family_id == "shock_n_awe":
        flags.append("damage_reactive_shock")
        values["shock_chance_percent"] = 10
        values["shock_damage"] = number_after(effect, r"deals") or 0
    if family_id == "countershell":
        flags.append("burst_damage_mitigation")
        values["mitigation_chance_percent"] = percentage(effect, "Mitigation Chance") or 0
        values["duration_seconds"] = number_before(effect, r"sec\. if you lose") or 0
        values["cooldown_seconds"] = number_after(effect, r"Cooldown") or 0
    if family_id == "nano_plating":
        flags.append("projectile_block")
    if family_id == "painducer":
        flags.append("damage_to_dot")
        values["damage_to_dot_percent"] = percentage(effect, "of damage taken") or 0
    if family_id in {"peripheral_inverse", "proxishield"}:
        flags.append("proximity_damage_reduction")
        values["close_damage_reduction_percent"] = abs(
            percentage(effect, "incoming damage at") or 0
        )
    if family_id == "rangeguard":
        flags.append("range_armor")
        values["range_armor"] = number_after(effect, r"\+") or 0
    if family_id == "carapace":
        flags.append("rear_armor")
        values["rear_armor_percent"] = percentage(effect, "Armor effectiveness") or 0
    if family_id == "cogito_lattice":
        flags.append("low_ram_armor")
        values["low_ram_armor_percent"] = percentage(effect, "Armor from this cyberware") or 0
    if family_id == "chitin":
        flags.append("health_regen")
    if family_id == "cellular_adapter":
        flags.append("technical_adaptation")
        effective_technical_ability = tier_rank + 1
        values["explosion_resistance_percent"] = effective_technical_ability
        values["tech_weapon_damage_percent"] = effective_technical_ability * 0.5
        values["health_item_cooldown_reduction_percent"] = effective_technical_ability * 0.5
        values["grenade_cooldown_reduction_percent"] = effective_technical_ability * 0.5
    if family_id == "defenzikov":
        flags.append("post_kerenzikov_mitigation")
        values["mitigation_chance_percent"] = percentage(effect, "Mitigation Chance") or 0
    if family_id == "jenkins_tendons":
        flags.append("sprint_ramp")
        values["sprint_speed_start_percent"] = 30
        values["sprint_speed_end_percent"] = 10
    if family_id == "lynx_paws":
        flags.append("quiet_steps")
        values["crouch_speed_percent"] = percentage(effect, "crouched movement speed") or 0
        values["fall_damage_reduction_percent"] = abs(percentage(effect, "fall damage") or 0)
    if family_id == "universal_booster":
        values["health_item_armor_percent"] = percentage(effect, "Armor") or 0
        values["health_item_stamina_reduction_percent"] = abs(
            percentage(effect, "all Stamina cost") or 0
        )
        values["duration_seconds"] = 5

    return {"flags": sorted(set(flags)), "values": {key: round(val, 4) for key, val in values.items()}}


def canonical_image_url(url: str) -> str:
    if not url:
        return url
    url = url.replace("/scale-to-width-down/64", "")
    url = url.replace("/scale-to-width-down/128", "")
    return url


def download_reference(url: str, output: Path) -> None:
    request = urllib.request.Request(canonical_image_url(url), headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read()
    with Image.open(io.BytesIO(payload)) as source:
        frame = source.convert("RGBA")
        output.parent.mkdir(parents=True, exist_ok=True)
        frame.save(output, "PNG")


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--skip-images", action="store_true")
    args = parser.parse_args()

    root = args.project_root.resolve()
    rows = fetch_master_rows()
    pages = fetch_page_wikitext(rows)

    families: list[dict[str, Any]] = []
    variants: list[dict[str, Any]] = []
    seen_slugs: dict[str, str] = {}
    reference_dir = root / ".modernity" / "art" / "references" / "cyberware"
    item_def_dir = root / "src" / "main" / "resources" / "assets" / "cyberdeck" / "items"
    model_dir = root / "src" / "main" / "resources" / "assets" / "cyberdeck" / "models" / "item"

    for row in rows:
        fields = template_fields(extract_template(pages[row.page], "Infobox Cyberware"))
        if not fields.get("effects"):
            raise RuntimeError(f"{row.name} has no Infobox Cyberware effects field")
        family_id = slugify(row.name)
        collision = seen_slugs.get(family_id)
        if collision and collision != row.name:
            raise RuntimeError(f"Slug collision: {collision!r} and {row.name!r} -> {family_id}")
        seen_slugs[family_id] = row.name
        ranks = tier_ranks(fields)
        image_url = canonical_image_url(row.image_url)
        reference_path = reference_dir / f"{family_id}.png"
        if not args.skip_images and (not reference_path.exists() or reference_path.stat().st_size == 0):
            download_reference(image_url, reference_path)

        family_variants: list[str] = []
        for rank in ranks:
            tier_id, tier_name, tier_path = TIERS[rank]
            variant_id = f"{family_id}_{tier_path}"
            effect = resolve_tier_values(fields.get("effects", ""), rank)
            armor_text = resolve_tier_values(fields.get("armor", ""), rank)
            armor_match = re.search(r"-?\d+(?:\.\d+)?", armor_text)
            armor = float(armor_match.group()) if armor_match else 0.0
            capacity_match = re.search(r"-?\d+(?:\.\d+)?", clean_wikitext(fields.get("capacity", "")))
            capacity = int(round(float(capacity_match.group()))) if capacity_match else 0
            mechanics = build_mechanics(
                family_id,
                row.name,
                SLOT_IDS[row.slot],
                row.os_type,
                effect,
                armor,
                rank,
            )
            variant = {
                "id": variant_id,
                "family": family_id,
                "name": row.name,
                "tier": tier_id,
                "tier_name": tier_name,
                "tier_rank": rank,
                "slot": SLOT_IDS[row.slot],
                "capacity": capacity,
                "armor": armor,
                "effect": effect,
                "mechanics": mechanics,
            }
            variants.append(variant)
            family_variants.append(variant_id)

            write_json(
                item_def_dir / f"{variant_id}.json",
                {"model": {"type": "minecraft:model", "model": f"cyberdeck:item/{family_id}"}},
            )

        write_json(
            model_dir / f"{family_id}.json",
            {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": f"cyberdeck:item/{family_id}"},
            },
        )
        families.append(
            {
                "id": family_id,
                "name": row.name,
                "slot": SLOT_IDS[row.slot],
                "os_type": row.os_type,
                "capacity": variants[-1]["capacity"],
                "icon": family_id,
                "wiki_page": WIKI_ROOT + row.href,
                "image_source": image_url,
                "quality_wikitext": fields.get("quality", ""),
                "effects_wikitext": fields.get("effects", ""),
                "armor_wikitext": fields.get("armor", ""),
                "rendered_effect": row.rendered_effect,
                "variants": family_variants,
            }
        )

    catalog = {
        "schema": 1,
        "source": {
            "page": MASTER_URL,
            "retrieved_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "family_count": len(families),
            "variant_count": len(variants),
            "tier_resolution": (
                "Piecewise linear interpolation between numeric {{R|tier|value}} endpoints; "
                "slash-delimited annotations use the latest value at or below the selected tier."
            ),
            "asset_notice": (
                "Icons are derivative reductions of Cyberpunk Wiki/CD Projekt artwork; clear reuse "
                "rights before public distribution."
            ),
        },
        "tiers": [
            {"id": tier_id, "name": tier_name, "rank": rank, "path": tier_path}
            for rank, (tier_id, tier_name, tier_path) in enumerate(TIERS)
        ],
        "families": families,
        "variants": variants,
    }
    catalog_path = root / "src" / "main" / "resources" / "data" / "cyberdeck" / "cyberware" / "catalog.json"
    write_json(catalog_path, catalog)
    write_json(
        reference_dir / "sources.json",
        {
            family["id"]: {
                "name": family["name"],
                "wiki_page": family["wiki_page"],
                "image_source": family["image_source"],
            }
            for family in families
        },
    )
    print(
        json.dumps(
            {
                "families": len(families),
                "variants": len(variants),
                "catalog": str(catalog_path),
                "references": str(reference_dir),
            }
        )
    )


if __name__ == "__main__":
    main()
