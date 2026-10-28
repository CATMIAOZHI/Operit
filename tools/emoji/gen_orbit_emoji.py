"""Builds app/src/main/assets/orbit_emoji/emoji.json for Orbit's emoji picker.

Inputs (download first):
  emoji-test.txt   https://unicode.org/Public/emoji/<version>/emoji-test.txt
  cldr-zh.xml      https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/zh.xml
  cldr-zh-derived.xml  .../common/annotationsDerived/zh.xml
  cldr-en.xml      .../common/annotations/en.xml
  cldr-en-derived.xml  .../common/annotationsDerived/en.xml

Usage: python gen_orbit_emoji.py <input dir> <output json>

Only fully qualified emoji are kept, without skin tone variants. Each entry carries its Chinese and
English names and keywords for search. The app hides emoji the phone cannot draw.
"""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

GROUPS = {
    "Smileys & Emotion": "smileys",
    "People & Body": "people",
    "Animals & Nature": "animals",
    "Food & Drink": "food",
    "Travel & Places": "travel",
    "Activities": "activities",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}
SKIN_TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}


def annotations(path: Path) -> dict:
    if not path.exists():
        return {}
    words: dict[str, list[str]] = {}
    for node in ET.parse(path).getroot().iter("annotation"):
        cp = node.get("cp")
        if not cp or not node.text:
            continue
        parts = [p.strip() for p in node.text.split("|")] if node.get("type") != "tts" else [node.text.strip()]
        bucket = words.setdefault(cp, [])
        # The spoken name first: it is the best match.
        if node.get("type") == "tts":
            bucket[:0] = parts
        else:
            bucket.extend(parts)
    return words


def main() -> None:
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    names = [
        annotations(src / "cldr-zh.xml"),
        annotations(src / "cldr-zh-derived.xml"),
        annotations(src / "cldr-en.xml"),
        annotations(src / "cldr-en-derived.xml"),
    ]
    groups: list[dict] = []
    current = None
    for line in (src / "emoji-test.txt").read_text(encoding="utf-8").splitlines():
        if line.startswith("# group:"):
            gid = GROUPS.get(line.split(":", 1)[1].strip())
            current = {"g": gid, "e": []} if gid else None
            if current:
                groups.append(current)
            continue
        if current is None or not line or line.startswith("#") or "; fully-qualified" not in line:
            continue
        codes = line.split(";", 1)[0].split()
        emoji = "".join(chr(int(c, 16)) for c in codes)
        if any(ch in SKIN_TONES for ch in emoji):
            continue
        english_name = line.split("#", 1)[1].split(" ", 3)[-1].strip() if "#" in line else ""
        bare = emoji.replace("\ufe0f", "")
        words: list[str] = []
        for table in names:
            words += table.get(emoji, []) or table.get(bare, [])
        words.append(english_name)
        seen: list[str] = []
        for w in words:
            w = w.lower()
            if w and w not in seen:
                seen.append(w)
        current["e"].append([emoji, " ".join(seen)])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(groups, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(out, sum(len(g["e"]) for g in groups), "emoji")


if __name__ == "__main__":
    main()
