#!/usr/bin/env python3
"""Summarize the LLM step (llm/clusters, llm/edges, llm/edges-v2) into llm/summary.md.

No API call; reads the cached raw responses that label_clusters.py wrote on machine A/B.
The first edge audit (llm/edges) is kept although its evidence for line-less descriptor
refs was the wrong line; llm/edges-v2 is the corrected one (README, "邊審查第二版").
  python3 -I docs/generalization/mining/summarize_labels.py
"""
import collections
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
LLM = HERE / "llm"
VERDICTS = ("correct", "declared-only", "wrong", "unsure", "missing")


def load(task):
    out = []
    raw = LLM / task / "raw"
    if not raw.exists():
        return out
    for p in sorted(raw.glob("*.json")):
        rec = json.loads(p.read_text(encoding="utf-8"))
        try:
            rec["parsed"] = json.loads(rec["response"])
        except json.JSONDecodeError:
            rec["parsed"] = None
        out.append(rec)
    return out


def edge_rows(recs):
    for r in recs:
        verdicts = {e["i"]: e for e in (r["parsed"] or {}).get("edges", []) if isinstance(e, dict) and "i" in e}
        for i, e in enumerate(r["payload"]):
            v = verdicts.get(i, {})
            yield v.get("verdict", "missing"), v.get("reason", ""), e


def edge_table(title, recs):
    rows = list(edge_rows(recs))
    n = len(rows)
    ev = collections.Counter(v for v, _, _ in rows)
    lines = [f"## 已畫的邊（每個 repo 固定種子抽 ≤3 條）：{title}", "", "| 判定 | 條數 | 比例 |", "|---|---|---|"]
    lines += [f"| {k} | {ev[k]} | {ev[k] / n:.0%} |" for k in VERDICTS if ev[k]]
    grade = collections.Counter((e["confidence"], v) for v, _, e in rows)
    lines += ["", "依信心等級：", "", "| 等級 | 判定 | 條數 |", "|---|---|---|"]
    lines += [f"| {g} | {v} | {c} |" for (g, v), c in sorted(grade.items())]
    lines += ["", "非 correct 的邊（作者逐條查證用）：", "",
              "| 判定 | repo | 邊 | 等級 | 出處 | LLM 理由 |", "|---|---|---|---|---|---|"]
    for v, why, e in rows:
        if v != "correct":
            ref = e["evidence"][0]["ref"] if e["evidence"] else ""
            lines.append(f"| {v} | {e['repo']} | {e['source']} → {e['target']} | {e['confidence']} | `{ref}` | {why} |")
    return lines + [""]


def main():
    clusters, edges, edges2 = load("clusters"), load("edges"), load("edges-v2")
    allrecs = clusters + edges + edges2
    usage = sum(r["usage"].get("prompt_tokens", 0) for r in allrecs)
    models = {r["usage"].get("response_model", "") for r in allrecs}
    lines = ["# LLM 判讀彙整（gpt-4.1-mini，機器 B）", "",
             f"呼叫 {len(allrecs)} 次（群 {len(clusters)}、邊審查第一版 {len(edges)}、第二版 {len(edges2)}），"
             f"prompt token {usage}，模型 {', '.join(sorted(models))}。", ""]
    if edges2:
        lines += edge_table("第二版", edges2)
    if edges:
        lines += edge_table("第一版（沒有行號的描述檔出處配錯了行，保留紀錄）", edges)

    rows = []
    for r in clusters:
        c, p = r["payload"], r["parsed"] or {}
        vc = collections.Counter(s.get("verdict") for s in p.get("samples", []) if isinstance(s, dict))
        rows.append((c["rank"], c, vc, p.get("rule", {}) or {}, p.get("summary", "")))
    rows.sort(key=lambda x: x[0])
    lines += ["## 殘餘群（≥ 2 個 repo）", "",
              "| 排名 | 形狀 | repo | 樣本判定 dep／wrong-src／not／unsure | 通用規則 | 信心 | LLM 摘要 |",
              "|---|---|---|---|---|---|---|"]
    for rank, c, vc, rule, summ in rows:
        lines.append(f"| {rank} | `{c['kind']} · {c['cls']} · {c['detail']}` | {c['repos']} | {vc.get('dependency', 0)}／"
                     f"{vc.get('wrong-source', 0)}／{vc.get('not-dependency', 0)}／{vc.get('unsure', 0)} | "
                     f"{'是' if rule.get('general') else '否'} | {rule.get('confidence', '')} | {summ} |")
    tot = collections.Counter()
    for _, _, vc, _, _ in rows:
        tot.update(vc)
    general = [x for x in rows if x[3].get("general")]
    lines += ["", f"樣本合計 {sum(tot.values())}：dependency {tot['dependency']}、wrong-source {tot['wrong-source']}、"
                  f"not-dependency {tot['not-dependency']}、unsure {tot['unsure']}；LLM 認為有通用規則的群 {len(general)}／{len(rows)}。", "",
              "## LLM 提出的通用規則（原文）", ""]
    for rank, c, vc, rule, _ in general:
        lines.append(f"- **#{rank}** `{c['kind']} · {c['cls']} · {c['detail']}`（{c['repos']} repo，dep {vc.get('dependency', 0)}）："
                     f"trigger＝{rule.get('trigger', '')}；edge＝{rule.get('edge', '')}；exclusions＝{rule.get('exclusions', '')}")
    (LLM / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[:16]))


if __name__ == "__main__":
    main()
