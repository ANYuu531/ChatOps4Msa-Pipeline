#!/usr/bin/env python3
"""Figures for the third-party reference edge set (the 2026-09-25 feedback's second point).

    python3 docs/charts/plot_external_agreement.py

Reads `docs/generalization/external-agreement.csv` — written by
`ExternalTruthAgreementTest`, so the figure and the table cannot drift apart — and
writes two SVGs into `docs/charts/`:

    external-agreement.svg          how much of each project's dataset edge set is drawn
    external-agreement-rules.svg    the same agreement before and after rules 11-13

The "before" column of the second figure is not in the CSV: it is the agreement measured
on the same checkouts before the Compose-vocabulary and Compose-v1 rules existed, recorded
in docs/generalization-external.md §1.6. It is written out below with that provenance.

Standard library only, same palette and typography as plot_calibration.py.
"""

import csv
import os

SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"]
INK, MUTED, GRID, RULE = "#1c1c1c", "#6f6f6f", "#e8e8e6", "#b9b9b5"
FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif"

# Agreement before rules 11-13, on the same checkouts (docs/generalization-external.md §1.6).
BEFORE = {
    "spring-petclinic": 0.00,
    "microservices-book": 0.00,
    "tap-and-eat": 0.00,
    "spring-cloud-netflix": 0.04,
    "spring-cloud-microservice": 0.00,
    "lakeside-mutual": 1.00,
    "robot-shop": 1.00,
}


def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def head(w, h, title, subtitle):
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
        f'font-family="{FONT}">',
        f'<rect width="{w}" height="{h}" fill="#ffffff"/>',
        f'<text x="24" y="30" font-size="15" font-weight="600" fill="{INK}">{esc(title)}</text>',
        f'<text x="24" y="50" font-size="11.5" fill="{MUTED}">{esc(subtitle)}</text>',
    ]


def stacked_bars(path, rows):
    """One row per project: the dataset's edges, with the drawn part filled."""
    left, right, top, gap, bar = 208, 132, 74, 12, 22
    h = top + len(rows) * (bar + gap) + 56
    w = 760
    plot_w = w - left - right
    widest = max(r["dataset"] for r in rows)
    out = head(w, h, "第三方參考邊集：資料集的邊，工具畫到多少",
               "參考邊集＝MicroDepGraph 資料集自己發表的圖（SattoSE 2019）；"
               "每個 repo checkout 到資料集擷取日之前的 commit")

    for i, r in enumerate(rows):
        y = top + i * (bar + gap)
        full = plot_w * r["dataset"] / widest
        drawn = plot_w * r["drawn"] / widest
        out.append(f'<text x="{left - 12}" y="{y + bar * 0.72:.1f}" font-size="11.5" fill="{INK}" '
                   f'text-anchor="end">{esc(r["project"])}</text>')
        out.append(f'<rect x="{left}" y="{y}" width="{full:.1f}" height="{bar}" fill="{GRID}"/>')
        out.append(f'<rect x="{left}" y="{y}" width="{drawn:.1f}" height="{bar}" fill="{SERIES[0]}"/>')
        # The counts sit in fixed columns rather than at the end of each bar: a label that
        # moves with the bar makes the column unreadable down the page.
        out.append(f'<text x="{left + plot_w + 46:.1f}" y="{y + bar * 0.72:.1f}" font-size="11.5" '
                   f'fill="{INK}" text-anchor="end">{r["drawn"]}/{r["dataset"]}</text>')
        out.append(f'<text x="{left + plot_w + 104:.1f}" y="{y + bar * 0.72:.1f}" font-size="11.5" '
                   f'font-weight="600" text-anchor="end" '
                   f'fill="{SERIES[2] if r["agreement"] >= 1 else INK}">{r["agreement"]:.2f}</text>')

    total_d = sum(r["dataset"] for r in rows)
    total_w = sum(r["drawn"] for r in rows)
    y = top + len(rows) * (bar + gap) + 4
    out.append(f'<line x1="{left}" y1="{y - 8}" x2="{left + plot_w}" y2="{y - 8}" stroke="{RULE}" stroke-width="1"/>')
    out.append(f'<text x="{left - 12}" y="{y + 12}" font-size="11.5" font-weight="600" fill="{INK}" '
               f'text-anchor="end">合計</text>')
    out.append(f'<text x="{left}" y="{y + 12}" font-size="11.5" fill="{INK}">7 個專案</text>')
    out.append(f'<text x="{left + plot_w + 46:.1f}" y="{y + 12}" font-size="11.5" font-weight="600" '
               f'fill="{INK}" text-anchor="end">{total_w}/{total_d}</text>')
    out.append(f'<text x="{left + plot_w + 104:.1f}" y="{y + 12}" font-size="11.5" font-weight="600" '
               f'fill="{INK}" text-anchor="end">{total_w / total_d:.2f}</text>')
    out.append(f'<rect x="{left}" y="{h - 20}" width="11" height="11" fill="{SERIES[0]}"/>')
    out.append(f'<text x="{left + 17}" y="{h - 11}" font-size="11" fill="{MUTED}">工具畫到</text>')
    out.append(f'<rect x="{left + 84}" y="{h - 20}" width="11" height="11" fill="{GRID}"/>')
    out.append(f'<text x="{left + 101}" y="{h - 11}" font-size="11" fill="{MUTED}">資料集有、工具沒畫</text>')
    out.append("</svg>")
    write(path, out)


def before_after(path, rows):
    """Agreement before and after the Compose vocabulary rules, as a slope per project."""
    left, right, top = 208, 128, 78
    row_h, h = 34, 0
    h = top + len(rows) * row_h + 44
    w = 760
    plot_w = w - left - right
    out = head(w, h, "規則 11–13 的效果：同一份 checkout，改動前後的一致度",
               "改動前的數字取自 docs/generalization-external.md §1.6（同一份 checkout 重跑）")

    def x(v):
        return left + v * plot_w

    for v in (0, 0.25, 0.5, 0.75, 1.0):
        out.append(f'<line x1="{x(v):.1f}" y1="{top - 14}" x2="{x(v):.1f}" y2="{top + len(rows) * row_h - 10:.1f}" '
                   f'stroke="{GRID}" stroke-width="1"/>')
        out.append(f'<text x="{x(v):.1f}" y="{top - 20}" font-size="10.5" fill="{MUTED}" '
                   f'text-anchor="middle">{v:g}</text>')

    for i, r in enumerate(rows):
        y = top + i * row_h
        b, a = r["before"], r["agreement"]
        out.append(f'<text x="{left - 12}" y="{y + 4}" font-size="11.5" fill="{INK}" '
                   f'text-anchor="end">{esc(r["project"])}</text>')
        colour = SERIES[2] if a > b else MUTED
        if a > b:
            out.append(f'<line x1="{x(b):.1f}" y1="{y}" x2="{x(a):.1f}" y2="{y}" stroke="{colour}" '
                       f'stroke-width="3" stroke-linecap="round"/>')
        out.append(f'<circle cx="{x(b):.1f}" cy="{y}" r="4.5" fill="#ffffff" stroke="{MUTED}" stroke-width="2"/>')
        out.append(f'<circle cx="{x(a):.1f}" cy="{y}" r="5" fill="{colour}"/>')
        # Fixed columns for the labels, as in the other figure.
        out.append(f'<text x="{left + plot_w + 34:.1f}" y="{y + 4}" font-size="11.5" fill="{INK}" '
                   f'text-anchor="end">{a:.2f}</text>')
        note = f'原 {b:.2f}' if a > b else '未變'
        out.append(f'<text x="{left + plot_w + 44:.1f}" y="{y + 4}" font-size="10.5" fill="{MUTED}">{note}</text>')

    out.append(f'<circle cx="{left + 6}" cy="{h - 17}" r="4.5" fill="#ffffff" stroke="{MUTED}" stroke-width="2"/>')
    out.append(f'<text x="{left + 18}" y="{h - 13}" font-size="11" fill="{MUTED}">規則 11–13 之前</text>')
    out.append(f'<circle cx="{left + 122}" cy="{h - 17}" r="5" fill="{SERIES[2]}"/>')
    out.append(f'<text x="{left + 134}" y="{h - 13}" font-size="11" fill="{MUTED}">之後</text>')
    out.append("</svg>")
    write(path, out)


def write(path, lines):
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", path)


def main():
    source = "docs/generalization/external-agreement.csv"
    if not os.path.exists(source):
        raise SystemExit("run ExternalTruthAgreementTest first: " + source + " is missing")
    rows = []
    with open(source, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append({
                "project": r["project"],
                "dataset": int(r["dataset_edges"]),
                "drawn": int(r["drawn"]),
                "agreement": float(r["agreement"]),
                "before": BEFORE.get(r["project"]),
            })
    rows.sort(key=lambda r: (-r["agreement"], -r["dataset"]))
    out = os.path.join("docs", "charts")
    stacked_bars(os.path.join(out, "external-agreement.svg"), rows)
    missing = [r["project"] for r in rows if r["before"] is None]
    if missing:
        print("no before-figure for", missing, "— skipping the second chart")
        return
    before_after(os.path.join(out, "external-agreement-rules.svg"),
                 sorted(rows, key=lambda r: (r["before"] - r["agreement"], r["project"])))


if __name__ == "__main__":
    main()
