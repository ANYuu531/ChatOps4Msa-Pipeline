#!/usr/bin/env python3
"""Render a .drawio (mxGraph XML) file to a standalone SVG.

Not a general mxGraph renderer — it covers exactly the shapes and styles used by
the DepWeaver diagrams, so that the figure shown in the deck comes from the same
source file that gets edited in Draw.io, rather than being redrawn by hand.
"""
import html
import re
import sys
import xml.etree.ElementTree as ET


def parse_style(style):
    out = {}
    for part in (style or "").split(";"):
        if not part:
            continue
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
        else:
            out[part.strip()] = True
    return out


def label_lines(value):
    """mxGraph labels are HTML. Reduce to plain text lines, keeping <b> as a flag."""
    if not value:
        return []
    v = value.replace("&nbsp;", " ")
    v = re.sub(r"<br\s*/?>", "\n", v, flags=re.I)
    lines = []
    for raw in v.split("\n"):
        bold = bool(re.search(r"<b>", raw, re.I))
        small = bool(re.search(r'font-size:\s*1[01]px', raw, re.I))
        text = re.sub(r"<[^>]+>", "", raw)
        text = html.unescape(text).strip()
        if text:
            lines.append((text, bold, small))
    return lines


def esc(t):
    return html.escape(t, quote=True)


def grad_defs(nodes):
    """One linearGradient per fill/gradient pair actually used.

    Not decoration: the diagram encodes 〔程式碼〕 as blue and 〔AI〕 as purple, and a
    step that is BOTH (traffic driving: the LLM writes the journey, our runner sends
    it) is drawn as a blue-to-purple gradient. Flattening it to the base colour makes
    that step indistinguishable from a pure-code one — including in the legend, where
    the distinction is the whole point.
    """
    out, seen = [], {}
    for n in nodes.values():
        g = n["style"].get("gradientColor")
        if not g:
            continue
        f = n["style"].get("fillColor", "#ffffff")
        key = f + g
        if key in seen:
            continue
        gid = "g%d" % len(seen)
        seen[key] = gid
        n["style"]["_grad"] = gid
        out.append('<linearGradient id="%s" x1="0" y1="0" x2="1" y2="0">'
                   '<stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/>'
                   '</linearGradient>' % (gid, f, g))
    for n in nodes.values():
        g = n["style"].get("gradientColor")
        if g:
            n["style"]["_grad"] = seen[n["style"].get("fillColor", "#ffffff") + g]
    return "".join(out)


def render(path, out_path):
    root = ET.parse(path).getroot()
    model = root.find("diagram").find("mxGraphModel")
    cells = model.find("root").findall("mxCell")

    nodes, edges = {}, []
    for c in cells:
        g = c.find("mxGeometry")
        st = parse_style(c.get("style"))
        if c.get("vertex") == "1" and g is not None:
            nodes[c.get("id")] = {
                "x": float(g.get("x", 0)), "y": float(g.get("y", 0)),
                "w": float(g.get("width", 0)), "h": float(g.get("height", 0)),
                "style": st, "value": c.get("value", ""),
            }
        elif c.get("edge") == "1":
            pts = []
            if g is not None:
                arr = g.find("Array")
                if arr is not None:
                    pts = [(float(p.get("x")), float(p.get("y"))) for p in arr.findall("mxPoint")]
            edges.append({"src": c.get("source"), "tgt": c.get("target"),
                          "style": st, "value": c.get("value", ""), "pts": pts})

    xs = [n["x"] for n in nodes.values()] + [n["x"] + n["w"] for n in nodes.values()]
    ys = [n["y"] for n in nodes.values()] + [n["y"] + n["h"] for n in nodes.values()]
    # Edge waypoints too: a route that swings out past every node (Draw.io does this
    # to keep a long feedback line clear of the boxes) would otherwise be cropped.
    for e in edges:
        for (px, py) in e["pts"]:
            xs.append(px)
            ys.append(py)
    pad = 16
    minx, miny = min(xs) - pad, min(ys) - pad
    W, H = max(xs) - minx + pad, max(ys) - miny + pad

    def X(v): return round(v - minx, 1)
    def Y(v): return round(v - miny, 1)

    svg = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W:.0f} {H:.0f}" '
        f'width="100%" role="img" aria-label="DepWeaver 流程圖">',
        '<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" '
        'markerHeight="7" orient="auto-start-reverse">'
        '<path d="M0,1 L9,5 L0,9 z" fill="#5b6770"/></marker>' + grad_defs(nodes) + '</defs>',
        f'<rect width="{W:.0f}" height="{H:.0f}" fill="#ffffff"/>',
    ]

    # ---- edges first, so nodes sit on top ----
    for e in edges:
        s, t = nodes.get(e["src"]), nodes.get(e["tgt"])
        if not s or not t:
            continue
        st = e["style"]

        def anchor(n, ex, ey):
            if ex is not None:
                return n["x"] + n["w"] * float(ex), n["y"] + n["h"] * float(ey)
            return n["x"] + n["w"] / 2, n["y"] + n["h"] / 2

        x1, y1 = anchor(s, st.get("exitX"), st.get("exitY"))
        x2, y2 = anchor(t, st.get("entryX"), st.get("entryY"))

        # Default anchors: leave from the side facing the target.
        if "exitX" not in st:
            if abs(t["y"] - s["y"]) > abs(t["x"] - s["x"]):
                y1 = s["y"] + s["h"] if t["y"] > s["y"] else s["y"]
                x1 = s["x"] + s["w"] / 2
            else:
                x1 = s["x"] + s["w"] if t["x"] > s["x"] else s["x"]
                y1 = s["y"] + s["h"] / 2
        if "entryX" not in st:
            if abs(t["y"] - s["y"]) > abs(t["x"] - s["x"]):
                y2 = t["y"] if t["y"] > s["y"] else t["y"] + t["h"]
                x2 = t["x"] + t["w"] / 2
            else:
                x2 = t["x"] if t["x"] > s["x"] else t["x"] + t["w"]
                y2 = t["y"] + t["h"] / 2

        way = e["pts"]
        d = [f"M {X(x1)} {Y(y1)}"]
        prev = (x1, y1)
        for (px, py) in way:
            d.append(f"L {X(px)} {Y(prev[1])} L {X(px)} {Y(py)}")
            prev = (px, py)
        # Orthogonal finish. Which way to turn FIRST depends on the side the edge left
        # from: leaving a node's left/right face and turning vertically first stacks
        # every such edge onto one shared column (the three checkpoint branches came
        # out as a T). Leave sideways -> go horizontal first, and vice versa.
        left_right = st.get("exitX") in ("0", "1") or (
            "exitX" not in st and abs(t["x"] - s["x"]) >= abs(t["y"] - s["y"]))
        if abs(prev[1] - y2) > 1 and abs(prev[0] - x2) > 1:
            if way:
                d.append(f"L {X(x2)} {Y(prev[1])} L {X(x2)} {Y(y2)}")
            elif left_right:
                midx = (prev[0] + x2) / 2
                d.append(f"L {X(midx)} {Y(prev[1])} L {X(midx)} {Y(y2)} L {X(x2)} {Y(y2)}")
            else:
                midy = (prev[1] + y2) / 2
                d.append(f"L {X(prev[0])} {Y(midy)} L {X(x2)} {Y(midy)} L {X(x2)} {Y(y2)}")
        else:
            d.append(f"L {X(x2)} {Y(y2)}")

        stroke = st.get("strokeColor", "#5b6770")
        dash = ' stroke-dasharray="5 4"' if st.get("dashed") == "1" else ""
        svg.append(f'<path d="{" ".join(d)}" fill="none" stroke="{stroke}" '
                   f'stroke-width="1.4"{dash} marker-end="url(#a)"/>')

        if e["value"]:
            lines = label_lines(e["value"])
            if lines:
                mx_, my_ = (x1 + x2) / 2, (y1 + y2) / 2
                txt = lines[0][0]
                wpx = len(txt) * 6.2
                svg.append(f'<rect x="{X(mx_) - wpx/2 - 3:.1f}" y="{Y(my_) - 8:.1f}" '
                           f'width="{wpx + 6:.1f}" height="15" fill="#ffffff" opacity=".9"/>')
                svg.append(f'<text x="{X(mx_):.1f}" y="{Y(my_) + 3:.1f}" font-size="10" '
                           f'fill="{st.get("fontColor", "#5b6770")}" text-anchor="middle" '
                           f'font-family="Helvetica, Arial, sans-serif">{esc(txt)}</text>')

    # ---- nodes ----
    for n in nodes.values():
        st, x, y, w, h = n["style"], X(n["x"]), Y(n["y"]), n["w"], n["h"]
        fill = st.get("fillColor", "#f5f5f5")
        if fill == "none":
            fill = "none"
        stroke = st.get("strokeColor", "#888888")
        dash = ' stroke-dasharray="5 4"' if st.get("dashed") == "1" else ""
        shape = st.get("shape", "")
        is_text = "text" in st and "rounded" not in st and fill == "none"

        if shape == "cylinder3":
            r = 8
            svg.append(
                f'<path d="M {x} {y+r} a {w/2} {r} 0 0 1 {w} 0 v {h-2*r} '
                f'a {w/2} {r} 0 0 1 {-w} 0 z" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'
                f'<path d="M {x} {y+r} a {w/2} {r} 0 0 0 {w} 0" fill="none" '
                f'stroke="{stroke}" stroke-width="1.2"/>')
        elif st.get("rhombus"):
            svg.append(f'<path d="M {x+w/2} {y} L {x+w} {y+h/2} L {x+w/2} {y+h} L {x} {y+h/2} z" '
                       f'fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>')
        elif not is_text:
            rx = 8 if st.get("rounded") == "1" else 0
            paint = f'url(#{st["_grad"]})' if st.get("_grad") else fill
            svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
                       f'fill="{paint}" stroke="{stroke}" stroke-width="1.2"{dash}/>')

        lines = label_lines(n["value"])
        if not lines:
            continue
        align = st.get("align", "center")
        valign = st.get("verticalAlign", "middle")
        base_size = float(st.get("fontSize", 12))
        lh = base_size * 1.45
        total = sum(10 if s else lh for _, _, s in lines)
        if valign == "top":
            ty = y + (float(st.get("spacingTop", 6)) or 6) + base_size
        else:
            ty = y + h / 2 - total / 2 + base_size
        if align == "left":
            tx, anch = x + float(st.get("spacingLeft", 8) or 8), "start"
        else:
            tx, anch = x + w / 2, "middle"

        for text, bold, small in lines:
            size = 10 if small else base_size
            weight = "700" if bold else "400"
            color = st.get("fontColor", "#1f2a37" if not small else "#5b6770")
            svg.append(f'<text x="{tx:.1f}" y="{ty:.1f}" font-size="{size}" font-weight="{weight}" '
                       f'fill="{color}" text-anchor="{anch}" '
                       f'font-family="Helvetica, Arial, sans-serif">{esc(text)}</text>')
            ty += 13 if small else lh

    svg.append("</svg>")
    open(out_path, "w").write("\n".join(svg))
    print(f"{path} -> {out_path}  ({len(nodes)} nodes, {len(edges)} edges, {W:.0f}x{H:.0f})")


if __name__ == "__main__":
    render(sys.argv[1], sys.argv[2])
