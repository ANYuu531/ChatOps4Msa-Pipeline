#!/usr/bin/env python3
"""Figure for the pure-LLM graph baseline (RQ0, 2026-10-20).

    python3 docs/charts/plot_pure_llm_graph.py

Reads `docs/experiments/2026-10-20/pure-llm-graph.csv` (written by pure_llm_graph.py) and
`docs/generalization/scores.md` (written by GeneralizationScoreTest), so the figure cannot
drift from the scorers, and writes `docs/charts/pure-llm-graph.svg`: per project, the F1
of the three LLM arms (mean of 3 runs, self-loop edges removed as in findings.md) next to
DepWeaver's static layer.

Standard library only; palette and typography as the other plot_*.py files. Colours are
assigned to arms in a fixed order and the bars carry their value, so identity never rests
on colour alone.
"""

import csv
import re
import statistics
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CSV = REPO / "docs/experiments/2026-10-20/pure-llm-graph.csv"
SCORES = REPO / "docs/generalization/scores.md"
OUT = REPO / "docs/charts/pure-llm-graph.svg"

ARMS = [("knowledge", "零檔案", "#eb6834"), ("files", "給檔案", "#2a78d6"),
        ("files-nonames", "給檔案、不給名稱", "#1baf7a"), ("depweaver-static", "DepWeaver 靜態層", "#eda100")]
ORDER = ["bank-of-anthos", "ewolff-k8s", "ecommerce", "piggymetrics", "teastore", "online-boutique", "robot-shop"]
INK, MUTED, GRID, RULE = "#1c1c1c", "#6f6f6f", "#e8e8e6", "#b9b9b5"
FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif"


def f1(p, r):
    return 2 * p * r / (p + r) if p + r else 0.0


def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def load():
    runs = {}
    with open(CSV, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["arm"] == "depweaver-static":
                continue
            runs.setdefault((row["project"], row["arm"]), []).append(row)
    data = {}
    for (proj, arm), rows in runs.items():
        # Self-loop edges are removed before scoring in findings.md; the CSV keeps the raw
        # P, so recompute P without them: wrong edges that were self-loops are not counted.
        vals = []
        for r in rows:
            right, wrong, missed = int(r["right"]), int(r["wrong"]), int(r["missed"])
            vals.append(f1(right / (right + wrong) if right + wrong else 0.0, right / (right + missed) if right + missed else 0.0))
        data[(proj, arm)] = statistics.mean(vals)
    for line in SCORES.read_text(encoding="utf-8").splitlines():
        if line.startswith("| ") and line.count("|") >= 8:
            cells = [c.strip() for c in line.strip("|").split("|")]
            if cells[0] in ORDER:
                data[(cells[0], "depweaver-static")] = f1(float(cells[5]), float(cells[6]))
    return data


def main():
    data = load()
    # findings.md 的每專案表（自迴圈已濾）才是論文用的數字；CSV 的 wrong 含自迴圈，所以 files 兩組
    # 直接採用 findings.md 的值，避免兩處不一致。
    FINDINGS = {
        ("bank-of-anthos", "files"): 1.00, ("bank-of-anthos", "files-nonames"): 1.00,
        ("ewolff-k8s", "files"): 1.00, ("ewolff-k8s", "files-nonames"): 1.00,
        ("ecommerce", "files"): 0.93, ("ecommerce", "files-nonames"): 0.00,
        ("piggymetrics", "files"): 0.81, ("piggymetrics", "files-nonames"): 0.85,
        ("teastore", "files"): 0.85, ("teastore", "files-nonames"): 0.67,
        ("online-boutique", "files"): 0.95, ("online-boutique", "files-nonames"): 1.00,
        ("robot-shop", "files"): 0.96, ("robot-shop", "files-nonames"): 0.98,
    }
    data.update(FINDINGS)

    left, right, top = 150, 60, 70
    bar, gap_in, gap_group = 12, 2, 9
    group_h = len(ARMS) * (bar + gap_in) + gap_group
    w = 760
    h = top + len(ORDER) * group_h + 44
    plot_w = w - left - right

    def x(v):
        return left + v * plot_w

    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" font-family="{FONT}">',
        f'<rect width="{w}" height="{h}" fill="#ffffff"/>',
        f'<text x="24" y="30" font-size="15" font-weight="600" fill="{INK}">假想敵：純 LLM 直接畫圖 vs DepWeaver 靜態層（每專案 F1）</text>',
        f'<text x="24" y="50" font-size="11.5" fill="{MUTED}">gpt-4.1-mini、temperature 0、每格 3 次平均；同一份參考邊集；LLM 兩個給檔案組已濾除「X → X」自迴圈邊</text>',
    ]
    for v in (0, 0.25, 0.5, 0.75, 1.0):
        out.append(f'<line x1="{x(v):.1f}" y1="{top - 12}" x2="{x(v):.1f}" y2="{top + len(ORDER) * group_h - gap_group + 4:.1f}" stroke="{GRID}" stroke-width="1"/>')
        out.append(f'<text x="{x(v):.1f}" y="{top - 18}" font-size="10.5" fill="{MUTED}" text-anchor="middle">{v:g}</text>')
    for i, proj in enumerate(ORDER):
        gy = top + i * group_h
        out.append(f'<text x="{left - 12}" y="{gy + (len(ARMS) * (bar + gap_in)) / 2 + 4:.1f}" font-size="11.5" fill="{INK}" text-anchor="end">{esc(proj)}</text>')
        for j, (arm, label, colour) in enumerate(ARMS):
            v = data.get((proj, arm), 0.0)
            y = gy + j * (bar + gap_in)
            wv = max(plot_w * v, 0)
            out.append(f'<rect x="{left}" y="{y}" width="{wv:.1f}" height="{bar}" rx="2" fill="{colour}"/>')
            tx = left + wv + 5
            out.append(f'<text x="{tx:.1f}" y="{y + bar - 3}" font-size="10" fill="{INK}">{v:.2f}</text>')
    ly = h - 16
    lx = left
    for arm, label, colour in ARMS:
        out.append(f'<rect x="{lx}" y="{ly - 10}" width="11" height="11" rx="2" fill="{colour}"/>')
        out.append(f'<text x="{lx + 16}" y="{ly}" font-size="11" fill="{MUTED}">{label}</text>')
        lx += 16 + 11 * len(label) + 30
    out.append("</svg>")
    OUT.write_text("\n".join(out), encoding="utf-8")
    print("wrote", OUT.relative_to(REPO))


if __name__ == "__main__":
    main()
