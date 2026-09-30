#!/usr/bin/env python3
"""Turns a printed deck PDF into a .pptx of full-bleed page images, for Google Slides.

The HTML deck is the source of truth; this exists only because Google Slides cannot
import a PDF. Each page becomes one 16:9 slide holding that page as a picture, so the
layout is exactly what the PDF shows — nothing reflows, nothing to fix by hand. The
slides are therefore NOT editable as text, which is the accepted trade-off.

Per-page speaker notes are pulled out of the逐頁講稿 markdown (`## P<n> · …` sections)
and put in each slide's notes field, so the script travels with the deck.

Links survive. A page image cannot be clicked, so every link annotation Chrome wrote
into the PDF is re-created as an almost invisible shape at the same place carrying the
same URL — the references on a slide stay clickable in PowerPoint and in Google Slides,
which is the whole point of citing them.

    python3 docs/deck-tools/pdf_to_pptx.py <deck.pdf> <out.pptx> [script.md]

Needs `pdftoppm` (poppler) on PATH, python-pptx and pypdf.
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

import pypdf
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches
from lxml import etree

DPI = 150  # 960x540 pt page -> 2000x1125 px: crisp on a projector, still a sane file size


def page_images(pdf: pathlib.Path, workdir: pathlib.Path) -> list[pathlib.Path]:
    if not shutil.which("pdftoppm"):
        sys.exit("pdftoppm not found — install poppler (brew install poppler)")
    subprocess.run(["pdftoppm", "-png", "-r", str(DPI), str(pdf), str(workdir / "page")], check=True)
    return sorted(workdir.glob("page-*.png"))


def links_by_page(pdf: pathlib.Path) -> dict[int, list[tuple[tuple[float, float, float, float], str]]]:
    """Every /Link annotation with a URI, as (x0, y0, x1, y1) in PDF points plus the URL.

    Chrome writes one per <a href> when it prints, so the deck's own markup is what ends
    up here — nothing has to be listed by hand.
    """
    out: dict[int, list[tuple[tuple[float, float, float, float], str]]] = {}
    reader = pypdf.PdfReader(str(pdf))
    for number, page in enumerate(reader.pages, start=1):
        found = []
        for annotation in page.get("/Annots", []) or []:
            obj = annotation.get_object()
            if obj.get("/Subtype") != "/Link":
                continue
            uri = (obj.get("/A") or {}).get("/URI")
            if not uri:
                continue
            x0, y0, x1, y1 = (float(v) for v in obj["/Rect"])
            found.append(((min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)), str(uri)))
        if found:
            out[number] = found
    return out


def add_link(slide, rect, uri, page_size, slide_size) -> None:
    """A click target over the page image: same rectangle, 1% white, carrying the URL.

    Not a fully transparent shape — PowerPoint only treats the outline of one as
    clickable, so a barely-there fill is what makes the whole rectangle a link.
    """
    (page_w, page_h), (slide_w, slide_h) = page_size, slide_size
    x0, y0, x1, y1 = rect
    left = Emu(int(x0 / page_w * slide_w))
    # PDF measures from the bottom, a slide from the top.
    top = Emu(int((page_h - y1) / page_h * slide_h))
    width = Emu(max(1, int((x1 - x0) / page_w * slide_w)))
    height = Emu(max(1, int((y1 - y0) / page_h * slide_h)))

    shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
    shape.shadow.inherit = False
    shape.line.fill.background()
    shape.fill.solid()
    shape.fill.fore_color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    solid = shape.fill._xPr.find(qn("a:solidFill"))
    colour = solid.find(qn("a:srgbClr"))
    alpha = etree.SubElement(colour, qn("a:alpha"))
    alpha.set("val", "1000")  # 1% — invisible on the page, still a click target
    shape.click_action.hyperlink.address = uri


def notes_by_page(script: pathlib.Path | None) -> dict[int, str]:
    """`## P<n> · title` … up to the next `## ` — the spoken script for that page."""
    if script is None or not script.exists():
        return {}
    out: dict[int, str] = {}
    for block in re.split(r"\n## ", script.read_text()):
        m = re.match(r"P(\d+)\s*·\s*(.*)", block)
        if not m:
            continue
        body = block.split("\n", 1)[1] if "\n" in block else ""
        body = body.replace("**", "").replace("`", "").strip()
        body = re.sub(r"\n---\s*$", "", body).strip()
        out[int(m.group(1))] = f"P{m.group(1)} · {m.group(2)}\n\n{body}"
    return out


def main(pdf_path: str, out_path: str, script_path: str | None) -> None:
    pdf = pathlib.Path(pdf_path)
    notes = notes_by_page(pathlib.Path(script_path) if script_path else None)

    links = links_by_page(pdf)
    reader = pypdf.PdfReader(str(pdf))
    box = reader.pages[0].mediabox
    page_size = (float(box.width), float(box.height))

    prs = Presentation()
    prs.slide_width, prs.slide_height = Inches(13.333), Inches(7.5)  # 16:9
    blank = prs.slide_layouts[6]

    with tempfile.TemporaryDirectory() as tmp:
        images = page_images(pdf, pathlib.Path(tmp))
        if not images:
            sys.exit("no pages rendered")
        for i, image in enumerate(images, start=1):
            slide = prs.slides.add_slide(blank)
            slide.shapes.add_picture(str(image), Emu(0), Emu(0),
                                     width=prs.slide_width, height=prs.slide_height)
            for rect, uri in links.get(i, []):
                add_link(slide, rect, uri, page_size, (prs.slide_width, prs.slide_height))
            if i in notes:
                slide.notes_slide.notes_text_frame.text = notes[i]

    prs.save(out_path)
    linked = sum(len(v) for v in links.values())
    print(f"wrote {out_path} — {len(images)} slides, {len(notes)} with speaker notes, "
          f"{linked} clickable links on {len(links)} slides")


if __name__ == "__main__":
    if not 3 <= len(sys.argv) <= 4:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
