#!/usr/bin/env python3
"""Turns the calibration CSVs into curve figures (SVG), because a grid of numbers is
not readable — the 2026-09-14 feedback asked for curves instead of tables.

    python3 docs/charts/plot_calibration.py [--in target/qa-calibration] [--out docs/charts]

Reads whatever is there and skips the rest, so it runs on a machine that has only
some of the experiments:

    sweep.csv              -> router-coverage-vs-t.svg, router-wrong-vs-t.svg,
                              router-operating-curve.svg
    topk-ablation.csv      -> topk-recall.svg, topk-cost.svg
    subgraph-limits.csv    -> subgraph-hops.svg, subgraph-nodes.svg,
                              subgraph-size-vs-seeds.svg
    subgraph-hops-external.csv -> corpus-reach-vs-hops.svg, corpus-slice-vs-hops.svg,
                              corpus-diameters.svg
    pure-llm-baseline.csv  -> baseline-arms.svg

Standard library only (no matplotlib): the figures are hand-written SVG so they drop
straight into the deck and the thesis. Palette: the validated categorical slots
(blue, orange, aqua, yellow, magenta) on a white surface; every series is direct-
labelled as well as coloured, because those hues sit below 3:1 against white.
"""

import argparse
import csv
import math
import os
from collections import defaultdict

SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"]
INK, MUTED, GRID, RULE = "#1c1c1c", "#6f6f6f", "#e8e8e6", "#b9b9b5"
W, H = 720, 400
PAD = {"l": 72, "r": 132, "t": 56, "b": 56}


def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def nice_ticks(lo, hi, count=5):
    """Round tick values covering [lo, hi]."""
    if hi <= lo:
        hi = lo + 1
    raw = (hi - lo) / count
    mag = 10 ** math.floor(math.log10(raw))
    step = min((s for s in (1, 2, 2.5, 5, 10) if s * mag >= raw), default=10) * mag
    start = math.floor(lo / step) * step
    ticks = []
    v = start
    while v < hi + step * 0.5:
        if v >= lo - step * 1e-9:
            ticks.append(round(v, 10))
        v += step
    return ticks


def line_chart(path, title, subtitle, series, x_label, y_label, x_ticks=None,
               y_fmt=lambda v: f"{v:g}", x_fmt=lambda v: f"{v:g}", y_from_zero=True,
               rules=(), points_only=False, annotations=()):
    """series: list of (name, [(x, y), ...]). rules: (orientation, value, label)."""
    xs = [x for _, pts in series for x, _ in pts]
    ys = [y for _, pts in series for _, y in pts]
    for orient, value, _ in rules:
        (ys if orient == "y" else xs).append(value)
    if not xs or not ys:
        return False
    x_lo, x_hi = min(xs), max(xs)
    y_lo, y_hi = (0 if y_from_zero else min(ys)), max(ys)
    if y_hi == y_lo:
        y_hi = y_lo + 1
    y_ticks = nice_ticks(y_lo, y_hi)
    y_lo, y_hi = min(y_lo, y_ticks[0]), max(y_hi, y_ticks[-1])
    x_ticks = x_ticks if x_ticks is not None else nice_ticks(x_lo, x_hi)
    plot_w = W - PAD["l"] - PAD["r"]
    plot_h = H - PAD["t"] - PAD["b"]

    def px(x):
        return PAD["l"] + (x - x_lo) / (x_hi - x_lo or 1) * plot_w

    def py(y):
        return PAD["t"] + plot_h - (y - y_lo) / (y_hi - y_lo or 1) * plot_h

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
           f'font-family="-apple-system,BlinkMacSystemFont,\'Segoe UI\',Helvetica,Arial,sans-serif">',
           f'<rect width="{W}" height="{H}" fill="#ffffff"/>',
           f'<text x="{PAD["l"]}" y="26" font-size="15" font-weight="600" fill="{INK}">{esc(title)}</text>']
    if subtitle:
        out.append(f'<text x="{PAD["l"]}" y="44" font-size="11.5" fill="{MUTED}">{esc(subtitle)}</text>')

    for t in y_ticks:                                            # recessive grid first
        y = py(t)
        out.append(f'<line x1="{PAD["l"]}" y1="{y:.1f}" x2="{PAD["l"] + plot_w}" y2="{y:.1f}" stroke="{GRID}" stroke-width="1"/>')
        out.append(f'<text x="{PAD["l"] - 10}" y="{y + 4:.1f}" font-size="11" fill="{MUTED}" text-anchor="end">{esc(y_fmt(t))}</text>')
    for t in x_ticks:
        if not (x_lo - 1e-9 <= t <= x_hi + 1e-9):
            continue
        x = px(t)
        out.append(f'<line x1="{x:.1f}" y1="{PAD["t"] + plot_h}" x2="{x:.1f}" y2="{PAD["t"] + plot_h + 4}" stroke="{RULE}" stroke-width="1"/>')
        out.append(f'<text x="{x:.1f}" y="{PAD["t"] + plot_h + 18:.1f}" font-size="11" fill="{MUTED}" text-anchor="middle">{esc(x_fmt(t))}</text>')
    out.append(f'<line x1="{PAD["l"]}" y1="{PAD["t"] + plot_h}" x2="{PAD["l"] + plot_w}" y2="{PAD["t"] + plot_h}" stroke="{RULE}" stroke-width="1"/>')

    for orient, value, label in rules:
        if orient == "y":
            y = py(value)
            out.append(f'<line x1="{PAD["l"]}" y1="{y:.1f}" x2="{PAD["l"] + plot_w}" y2="{y:.1f}" stroke="{RULE}" stroke-width="1.5" stroke-dasharray="5 4"/>')
            out.append(f'<text x="{PAD["l"] + plot_w - 4}" y="{y - 6:.1f}" font-size="11" fill="{MUTED}" text-anchor="end">{esc(label)}</text>')
        else:
            x = px(value)
            out.append(f'<line x1="{x:.1f}" y1="{PAD["t"]}" x2="{x:.1f}" y2="{PAD["t"] + plot_h}" stroke="{RULE}" stroke-width="1.5" stroke-dasharray="5 4"/>')
            out.append(f'<text x="{x + 5:.1f}" y="{PAD["t"] + 12:.1f}" font-size="11" fill="{MUTED}">{esc(label)}</text>')

    for i, (name, pts) in enumerate(series):
        colour = SERIES[i % len(SERIES)]
        pts = sorted(pts)
        if not pts:
            continue
        if not points_only:
            d = " ".join(f"{'M' if j == 0 else 'L'}{px(x):.1f},{py(y):.1f}" for j, (x, y) in enumerate(pts))
            out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>')
        r = 2.2 if points_only else 4
        for x, y in pts:
            out.append(f'<circle cx="{px(x):.1f}" cy="{py(y):.1f}" r="{r}" fill="{colour}" stroke="#ffffff" stroke-width="{1 if points_only else 2}"/>')
        if not points_only:                                      # direct label at the end
            lx, ly = pts[-1]
            out.append(f'<text x="{px(lx) + 10:.1f}" y="{py(ly) + 4:.1f}" font-size="11.5" fill="{INK}">{esc(name)}</text>')

    for x, y, text in annotations:
        out.append(f'<circle cx="{px(x):.1f}" cy="{py(y):.1f}" r="6" fill="none" stroke="{INK}" stroke-width="2"/>')
        out.append(f'<text x="{px(x) + 10:.1f}" y="{py(y) - 8:.1f}" font-size="11.5" fill="{INK}">{esc(text)}</text>')

    out.append(f'<text x="{PAD["l"] + plot_w / 2:.0f}" y="{H - 10}" font-size="11.5" fill="{MUTED}" text-anchor="middle">{esc(x_label)}</text>')
    out.append(f'<text transform="translate(18,{PAD["t"] + plot_h / 2:.0f}) rotate(-90)" font-size="11.5" fill="{MUTED}" text-anchor="middle">{esc(y_label)}</text>')
    if len(series) > 1 and not points_only:                      # legend: identity never by colour alone
        x = PAD["l"]
        for i, (name, _) in enumerate(series):
            colour = SERIES[i % len(SERIES)]
            out.append(f'<circle cx="{x + 5:.0f}" cy="{H - 34}" r="4" fill="{colour}"/>')
            out.append(f'<text x="{x + 15:.0f}" y="{H - 30}" font-size="11.5" fill="{MUTED}">{esc(name)}</text>')
            x += 22 + 7.2 * len(str(name))
    out.append("</svg>")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print("wrote", path)
    return True


def read_csv(path):
    if not os.path.exists(path):
        return None
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def pct(v):
    return f"{v * 100:.0f}%"


def router_charts(rows, out_dir):
    all_rows = [r for r in rows if r["group"] == "all"]
    if not all_rows:
        return
    # The H that the selected combination uses, else the most common one.
    hs = sorted({float(r["H"]) for r in all_rows})
    h_fixed = 0.80 if 0.80 in hs else hs[0]
    fixed = [r for r in all_rows if abs(float(r["H"]) - h_fixed) < 1e-9]
    wanted_m = [0.0, 0.04, 0.08, 0.10, 0.12]
    for metric, title, name, fmt in (
            ("coverage", "Router coverage vs the threshold T", "router-coverage-vs-t.svg", pct),
            ("confident_wrong_rate", "Confident-but-wrong vs the threshold T", "router-wrong-vs-t.svg", pct)):
        series = []
        for m in wanted_m:
            pts = [(float(r["T"]), float(r[metric])) for r in fixed if abs(float(r["M"]) - m) < 1e-9]
            if pts:
                series.append((f"M={m:.2f}", pts))
        line_chart(os.path.join(out_dir, name), title,
                   f"102 hold-out questions, H={h_fixed:.2f}; each curve is one margin M",
                   series, "threshold T", metric.replace("_", " "), y_fmt=fmt)

    # The operating curve: every grid point, coverage against the error it admits.
    pts = [(float(r["confident_wrong_rate"]), float(r["coverage"])) for r in all_rows]
    chosen = [(float(r["confident_wrong_rate"]), float(r["coverage"]))
              for r in all_rows if abs(float(r["T"]) - 0.51) < 1e-9 and abs(float(r["M"]) - 0.10) < 1e-9
              and abs(float(r["H"]) - 0.80) < 1e-9]
    old = [(float(r["confident_wrong_rate"]), float(r["coverage"]))
           for r in all_rows if abs(float(r["T"]) - 0.58) < 1e-9 and abs(float(r["M"]) - 0.04) < 1e-9
           and abs(float(r["H"]) - 0.85) < 1e-9]
    notes = []
    if chosen:
        notes.append((chosen[0][0], chosen[0][1], "selected 0.51 / 0.10 / 0.80"))
    if old:
        notes.append((old[0][0], old[0][1], "old 0.58 / 0.04 / 0.85"))
    line_chart(os.path.join(out_dir, "router-operating-curve.svg"),
               "What a threshold combination buys: coverage against confident-but-wrong",
               f"{len(pts)} combinations of T, M and H; up and to the left is better",
               [("grid", pts)], "confident-but-wrong rate", "coverage",
               y_fmt=pct, x_fmt=pct, points_only=True, annotations=notes)


def topk_charts(rows, out_dir):
    by_mode = defaultdict(list)
    for r in rows:
        by_mode[r["mode"]].append(r)
    recall = [(mode, [(int(r["k"]), float(r["recall_at_k"])) for r in rs]) for mode, rs in by_mode.items()]
    line_chart(os.path.join(out_dir, "topk-recall.svg"), "Retrieval: Recall@k",
               "one real report archive, labelled questions; RRF is what production runs",
               recall, "k (passages in the context)", "Recall@k", y_fmt=lambda v: f"{v:.1f}")
    rrf = by_mode.get("RRF") or next(iter(by_mode.values()), [])
    if rrf:
        line_chart(os.path.join(out_dir, "topk-cost.svg"), "What k costs: context length",
                   "average characters of passages put in the prompt",
                   [("RRF", [(int(r["k"]), float(r["avg_context_chars"])) for r in rrf])],
                   "k (passages in the context)", "characters", y_fmt=lambda v: f"{v:,.0f}",
                   rules=(("y", 24000, "passage budget 24 000"), ("y", 18000, "75% of budget")))


def subgraph_charts(rows, out_dir):
    hops = defaultdict(dict)
    nodes = defaultdict(dict)
    sizes = defaultdict(dict)
    for r in rows:
        if r["section"] == "hops":
            hops[r["metric"]][int(r["key"])] = float(r["value"])
        elif r["section"] == "nodes":
            nodes[r["metric"]][int(r["key"])] = float(r["value"])
        elif r["section"] == "natural-size":
            sizes[r["key"]][int(r["project"].split("=")[1])] = float(r["value"])
    if hops:
        series = [(name.replace("_", " "), sorted(vals.items()))
                  for name, vals in hops.items() if name in ("coverage", "false_connection", "j")]
        line_chart(os.path.join(out_dir, "subgraph-hops.svg"),
                   "Hop limit: connecting a flow without inventing one",
                   "pairs inside one documented flow vs pairs across two, Bank of Anthos + train-ticket",
                   series, "hop limit", "share of pairs within the limit", y_fmt=lambda v: f"{v:.2f}",
                   x_ticks=[1, 2, 3, 4, 5, 6, 8, 12])
    if nodes.get("untruncated"):
        line_chart(os.path.join(out_dir, "subgraph-nodes.svg"),
                   "Node cap: how often a slice gets cut",
                   "242 seed sets over 10 documented flows",
                   [("slices left whole", sorted(nodes["untruncated"].items()))],
                   "node cap", "slices drawn in full", y_fmt=pct,
                   rules=(("y", 0.9, "90% rule"), ("x", 20, "readability ceiling (Ghoniem 2005)")))
    if sizes:
        series = [(name, sorted(vals.items())) for name, vals in sizes.items()]
        line_chart(os.path.join(out_dir, "subgraph-size-vs-seeds.svg"),
                   "How big a slice gets: nodes vs how many services the question names",
                   "no cap applied; the cap only has to catch the tail",
                   series, "seeds named in the question", "nodes in the slice",
                   rules=(("y", 20, "cap = 20"),), x_ticks=[1, 2, 3, 4, 6, 8])


def corpus_charts(rows, out_dir):
    reach, p90, diameters = {}, {}, defaultdict(int)
    for r in rows:
        if r["section"] == "corpus-reach":
            reach[int(r["key"])] = float(r["value"])
        elif r["section"] == "corpus-slice" and r["metric"] == "p90":
            p90[int(r["key"])] = float(r["value"])
        elif r["section"] == "corpus-depth":
            diameters[int(float(r["value"]))] += 1
    if reach:
        line_chart(os.path.join(out_dir, "corpus-reach-vs-hops.svg"),
                   "How many hops a real dependency graph needs",
                   "22 real microservice graphs (MicroDepGraph + ours), 568 reachable service pairs pooled",
                   [("pairs connected", sorted(reach.items()))], "hop limit", "share of reachable pairs",
                   y_fmt=pct, rules=(("x", 4, "production limit"),), x_ticks=[1, 2, 3, 4, 5, 6])
    if p90:
        line_chart(os.path.join(out_dir, "corpus-slice-vs-hops.svg"),
                   "What a bigger hop limit costs the picture: nothing measurable",
                   "p90 nodes in the slice of a two-service question, same 22 graphs",
                   [("p90 slice size", sorted(p90.items()))], "hop limit", "nodes in the slice",
                   rules=(("y", 20, "readability ceiling (Ghoniem 2005)"),), x_ticks=[1, 2, 3, 4, 6, 8, 12])
    if diameters:
        line_chart(os.path.join(out_dir, "corpus-diameters.svg"),
                   "Diameter of 22 real microservice dependency graphs",
                   "the longest shortest path in each system; none is deeper than 3",
                   [("graphs", sorted(diameters.items()))], "diameter (hops)", "number of graphs",
                   x_ticks=sorted(diameters))


def baseline_chart(rows, out_dir):
    by_arm = defaultdict(list)
    for r in rows:
        # Questions whose graph answer is "nothing" are read by hand, not averaged.
        if str(r.get("negative", "")).lower() == "true":
            continue
        by_arm[r["arm"]].append(r)
    order = ["report-only", "rag", "depweaver"]
    arms = [a for a in order if a in by_arm] or list(by_arm)
    series = []
    for metric in ("recall", "hit_precision"):
        pts = []
        for i, arm in enumerate(arms):
            vals = [float(r[metric]) for r in by_arm[arm] if r[metric] not in ("", "NaN")]
            if vals:
                pts.append((i + 1, sum(vals) / len(vals)))
        if pts:
            series.append((metric.replace("hit_", ""), pts))
    line_chart(os.path.join(out_dir, "baseline-arms.svg"),
               "Pure LLM vs the grounded pipeline",
               "same questions, same model; answers scored on the services they name",
               series, "arm: " + " · ".join(f"{i + 1}={a}" for i, a in enumerate(arms)), "score",
               y_fmt=lambda v: f"{v:.1f}", x_ticks=list(range(1, len(arms) + 1)),
               x_fmt=lambda v: arms[int(v) - 1] if 1 <= v <= len(arms) else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default="target/qa-calibration")
    ap.add_argument("--out", dest="out", default="docs/charts")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    for name, fn in (("sweep.csv", router_charts), ("topk-ablation.csv", topk_charts),
                     ("subgraph-limits.csv", subgraph_charts), ("subgraph-hops-external.csv", corpus_charts),
                     ("pure-llm-baseline.csv", baseline_chart)):
        rows = read_csv(os.path.join(args.src, name))
        if rows is None:
            print("skipped (not found):", os.path.join(args.src, name))
            continue
        fn(rows, args.out)


if __name__ == "__main__":
    main()
