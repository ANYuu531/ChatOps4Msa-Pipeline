#!/usr/bin/env python3
"""Assembles a SOSEL deck: a body file with placeholders -> a standalone HTML file.

The body is ordinary HTML (head + <section class="slide"> … ) carrying three kinds of
placeholder, so the source you edit stays a few tens of KB instead of the ~1 MB the
finished deck weighs:

    {{LOGO}} {{CAT}} {{WAVE}}   the base64 assets from assets.json
    {{CHART:<name>}}            docs/charts/<name>.svg, inlined as a live <svg>

Charts are inlined rather than linked so the deck is one portable file, and inlined as
SVG rather than base64 so they stay vector in the printed PDF and scale with the slide.

    python3 docs/deck-tools/build_deck.py <body.html> <out.html>

To print it (16:9 pages come from the body's own @page rule):

    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new \
      --disable-gpu --no-pdf-header-footer --virtual-time-budget=10000 \
      --print-to-pdf=out.pdf "file://$PWD/docs/<deck>.html"
"""
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent


def chart(name: str) -> str:
    svg = (REPO / "docs/charts" / f"{name}.svg").read_text()
    # The class carries the sizing; meet keeps the aspect ratio inside its grid cell.
    return svg.replace("<svg ", '<svg class="chart" preserveAspectRatio="xMidYMid meet" ', 1)


def main(body_path: str, out_path: str) -> None:
    assets = json.loads((HERE / "assets.json").read_text())
    body = pathlib.Path(body_path).read_text()
    for key in ("LOGO", "CAT", "WAVE"):
        body = body.replace("{{%s}}" % key, assets[key.lower()])
    body = re.sub(r"\{\{CHART:([a-z0-9-]+)\}\}", lambda m: chart(m.group(1)), body)

    missing = sorted(set(re.findall(r"\{\{[^}]+\}\}", body)))
    if missing:
        sys.exit(f"unresolved placeholders: {missing}")

    out = pathlib.Path(out_path)
    out.write_text(body)
    slides = body.count('class="slide')
    print(f"wrote {out} ({len(body):,} bytes, {slides} slides)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
