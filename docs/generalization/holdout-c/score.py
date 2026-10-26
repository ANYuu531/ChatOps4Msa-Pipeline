#!/usr/bin/env python3
"""Hold-out 計分：和 GeneralizationScoreTest 同一套規則，只是讀 holdout-c/ 底下的檔案（第二組 hold-out，set C）。

用法（在 repo 根目錄）：
    python3 -I docs/generalization/holdout-c/score.py [--tag r1]

讀 truth/<name>.tsv 與 <name>.<tag>.summary.md，寫 scores-<tag>.md。
規則（逐字照 GeneralizationScoreTest）：
  - 邊＝(source, target) 有向對，名字必須完全相同；
  - truth 的 variant 類不進分子分母：工具畫了不算錯、沒畫不算漏；
  - P = 對 / 工具畫的（不含 variant），R = 對 / truth 非 variant；
  - 每類 P/R：錯的邊沒有 class，目標名含 db/queue 線索（DependencyGraph.classifyKind）算 data，否則算 business；control 只報 R。
另外列出：工具畫到的 variant 邊（例如遙測管線），以及 causes.tsv 裡人工標的原因。
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TRUTH_DIR = HERE / "truth"
TAG = "r1"
if "--tag" in sys.argv:
    TAG = sys.argv[sys.argv.index("--tag") + 1]

# DependencyGraph.DB_HINTS / QUEUE_HINTS（substring，小寫）
DB_HINTS = ["-db", "database", "mongo", "mysql", "postgres", "postgresql",
            "redis", "cassandra", "mariadb", "couch", "elasticsearch", "hsqldb"]
QUEUE_HINTS = ["rabbitmq", "kafka", "nats", "activemq", "rabbit", "amqp"]
EDGE_LINE = re.compile(r"^- (\S+) -> (\S+)\s+\(([\w-]+), (\w+)\)")


def is_data_kind(name: str) -> bool:
    n = name.lower()
    return any(h in n for h in QUEUE_HINTS) or any(h in n for h in DB_HINTS)


def read_truth(path: Path):
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t")
        assert len(cols) >= 3, f"truth row needs source, target, class: {line}"
        s, t, c = cols[0].strip(), cols[1].strip(), cols[2].strip()
        out[f"{s} -> {t}"] = (s, t, c, cols[3].strip() if len(cols) > 3 else "")
    return out


def read_drawn(path: Path):
    drawn, conf, prov = [], {}, {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = EDGE_LINE.match(line)
        if not m:
            continue
        k = f"{m.group(1)} -> {m.group(2)}"
        if k not in drawn:
            drawn.append(k)
        conf[k] = f"{m.group(3)}, {m.group(4)}"
        prov[k] = line[m.end():].strip()
    return drawn, conf, prov


def read_causes(path: Path):
    causes = {}
    if not path.exists():
        return causes
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) < 5:
            continue
        causes[(cols[0].strip(), cols[1].strip(), cols[2].strip())] = (cols[3].strip(), cols[4].strip())
    return causes


def ratio(a, b):
    return "—" if b == 0 else f"{a / b:.2f}"


def per_class(truth, drawn, wrong, cls):
    tot = [k for k, v in truth.items() if v[2] == cls]
    if not tot:
        return "—"
    hit = sum(1 for k in tot if k in drawn)
    if cls == "control":
        return "— / " + ratio(hit, len(tot))
    fp = sum(1 for w in wrong if (cls == "data") == is_data_kind(w.split(" -> ")[1]))
    return ratio(hit, hit + fp) + " / " + ratio(hit, len(tot))


def main():
    causes = read_causes(HERE / "causes.tsv")
    md = []
    md.append(f"# Hold-out set C：靜態 greenfield 圖 vs 參考邊集（程式計分，{TAG}）\n")
    md.append("由 `docs/generalization/holdout-c/score.py` 產生，規則與 `GeneralizationScoreTest` 相同："
              "讀 `truth/<name>.tsv` 與 `<name>." + TAG + ".summary.md`，`variant` 類不計。"
              "P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。\n")
    md.append("| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |")
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    details = []
    totals = {"drawn": 0, "right": 0, "wrong": 0, "missed": 0, "scorable": 0}

    for truth_file in sorted(TRUTH_DIR.glob("*.tsv")):
        name = truth_file.stem
        summary = HERE / f"{name}.{TAG}.summary.md"
        if not summary.exists():
            details.append(f"\n## {name}\n\n（沒有 {summary.name}，略過）\n")
            continue
        truth = read_truth(truth_file)
        drawn, conf, prov = read_drawn(summary)
        scorable = [k for k, v in truth.items() if v[2] != "variant"]
        right = [d for d in drawn if d in truth and truth[d][2] != "variant"]
        wrong = [d for d in drawn if d not in truth]
        variant_hit = [d for d in drawn if d in truth and truth[d][2] == "variant"]
        missed = [k for k in scorable if k not in drawn]
        n = len(right) + len(wrong)
        totals["drawn"] += n
        totals["right"] += len(right)
        totals["wrong"] += len(wrong)
        totals["missed"] += len(missed)
        totals["scorable"] += len(scorable)

        ext_tot = [k for k, v in truth.items() if v[2] == "external"]
        ext_hit = sum(1 for k in ext_tot if k in drawn)
        ext_cell = "—" if not ext_tot else ("— / " + ratio(ext_hit, len(ext_tot)))

        md.append(f"| {name} | {n} | {len(right)} | {len(wrong)} | {len(missed)} | "
                  f"{ratio(len(right), n)} | {ratio(len(right), len(scorable))} | "
                  f"{per_class(truth, drawn, wrong, 'business')} | {per_class(truth, drawn, wrong, 'data')} | "
                  f"{per_class(truth, drawn, wrong, 'control')} | {ext_cell} | {len(variant_hit)} |")

        by_class = {}
        for k in scorable:
            by_class[truth[k][2]] = by_class.get(truth[k][2], 0) + 1
        n_variant = sum(1 for v in truth.values() if v[2] == "variant")
        details.append(f"\n## {name}\n")
        details.append("參考邊集：" + "、".join(f"{c} {by_class[c]}" for c in ["business", "data", "external", "control"] if c in by_class)
                       + f"（可計分 {len(scorable)}）＋ variant {n_variant}。\n")

        def cause_of(kind, edge):
            c = causes.get((name, kind, edge))
            return f"  ·  **{c[0]}**：{c[1]}" if c else ""

        if wrong:
            details.append(f"\n錯（工具畫了、truth 沒有，{len(wrong)} 條）：\n")
            for w in wrong:
                details.append(f"- {w}  ({conf[w]})  {prov.get(w, '')}{cause_of('wrong', w)}")
        inferred = sum(1 for r in right if conf[r].endswith("inferred"))
        if inferred:
            details.append(f"\n對的裡面有 {inferred} 條標 inferred（名字推的，不是表查到的）。\n")
        if missed:
            details.append(f"\n漏（truth 有、工具沒畫，{len(missed)} 條）：\n")
            for m in missed:
                details.append(f"- {m}  ·  {truth[m][3]}{cause_of('missed', m)}")
        if variant_hit:
            details.append(f"\n工具另外畫到的 variant 邊（不計分，{len(variant_hit)} 條）：\n")
            for v in variant_hit:
                details.append(f"- {v}  ({conf[v]})  ·  {truth[v][3]}")
        if not wrong and not missed:
            details.append("\n全對。\n")

    md.append(f"| **合計** | {totals['drawn']} | {totals['right']} | {totals['wrong']} | {totals['missed']} | "
              f"{ratio(totals['right'], totals['drawn'])} | {ratio(totals['right'], totals['scorable'])} | | | | | |")
    md.append("\n`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。\n")
    md.extend(details)
    out = HERE / f"scores-{TAG}.md"
    out.write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n".join(md))


if __name__ == "__main__":
    main()
