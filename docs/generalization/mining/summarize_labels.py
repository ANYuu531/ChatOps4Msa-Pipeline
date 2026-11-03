#!/usr/bin/env python3
"""Summarize the LLM step (llm/clusters/raw, llm/edges/raw) into llm/summary.md.

No API call; reads the cached raw responses that label_clusters.py wrote on machine A/B.
  python3 -I docs/generalization/mining/summarize_labels.py
"""
import collections
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
LLM = HERE / "llm"


def load(task):
    out = []
    for p in sorted((LLM / task / "raw").glob("*.json")):
        rec = json.loads(p.read_text(encoding="utf-8"))
        try:
            rec["parsed"] = json.loads(rec["response"])
        except json.JSONDecodeError:
            rec["parsed"] = None
        out.append(rec)
    return out


def main():
    clusters = load("clusters")
    edges = load("edges")
    lines = ["# LLM 判讀彙整（gpt-4.1-mini，機器 B）", ""]

    usage = sum(r["usage"].get("prompt_tokens", 0) for r in clusters + edges)
    models = {r["usage"].get("response_model", "") for r in clusters + edges}
    lines += [f"呼叫 {len(clusters) + len(edges)} 次（群 {len(clusters)}、邊 {len(edges)}），prompt token {usage}，模型 {', '.join(sorted(models))}。", ""]

    # ---- edges: precision estimate on the corpus
    ev = collections.Counter()
    wrong = []
    for r in edges:
        chunk = r["payload"]
        verdicts = {e["i"]: e for e in (r["parsed"] or {}).get("edges", []) if isinstance(e, dict) and "i" in e}
        for i, e in enumerate(chunk):
            v = verdicts.get(i, {}).get("verdict", "missing")
            ev[v] += 1
            if v != "correct":
                wrong.append((v, e, verdicts.get(i, {}).get("reason", "")))
    n = sum(ev.values())
    lines += ["## 已畫的邊（每個 repo 固定種子抽 ≤3 條）", "",
              "| 判定 | 條數 | 比例 |", "|---|---|---|"]
    for k in ("correct", "wrong", "unsure", "missing"):
        if ev[k]:
            lines.append(f"| {k} | {ev[k]} | {ev[k] / n:.0%} |")
    lines += ["", "非 correct 的邊（作者逐條查證用）：", "",
              "| 判定 | repo | 邊 | 型態 | 出處 | LLM 理由 |", "|---|---|---|---|---|---|"]
    for v, e, why in wrong:
        ref = e["evidence"][0]["ref"] if e["evidence"] else ""
        lines.append(f"| {v} | {e['repo']} | {e['source']} → {e['target']} | {e['type']} | `{ref}` | {why} |")

    # ---- clusters
    lines += ["", "## 殘餘群（≥ 2 個 repo）", "",
              "| 排名 | 形狀 | repo | 樣本判定 dep／wrong-src／not／unsure | 通用規則 | 信心 | LLM 摘要 |",
              "|---|---|---|---|---|---|---|"]
    rows = []
    for r in clusters:
        c = r["payload"]
        p = r["parsed"] or {}
        vc = collections.Counter(s.get("verdict") for s in p.get("samples", []) if isinstance(s, dict))
        rule = p.get("rule", {}) or {}
        rows.append((c["rank"], c, vc, rule, p.get("summary", "")))
    for rank, c, vc, rule, summ in sorted(rows, key=lambda x: x[0]):
        shape = f"{c['kind']} · {c['cls']} · {c['detail']}"
        lines.append(f"| {rank} | `{shape}` | {c['repos']} | {vc.get('dependency', 0)}／{vc.get('wrong-source', 0)}／"
                     f"{vc.get('not-dependency', 0)}／{vc.get('unsure', 0)} | {'是' if rule.get('general') else '否'} | "
                     f"{rule.get('confidence', '')} | {summ} |")

    tot = collections.Counter()
    for _, _, vc, _, _ in rows:
        tot.update(vc)
    general = [x for x in rows if x[3].get("general")]
    lines += ["", f"樣本合計：dependency {tot['dependency']}、wrong-source {tot['wrong-source']}、"
                  f"not-dependency {tot['not-dependency']}、unsure {tot['unsure']}；LLM 認為有通用規則的群 {len(general)}／{len(rows)}。", ""]
    lines += ["## LLM 提出的通用規則（原文）", ""]
    for rank, c, vc, rule, summ in sorted(general, key=lambda x: x[0]):
        lines.append(f"- **#{rank}** `{c['kind']} · {c['cls']} · {c['detail']}`（{c['repos']} repo，dep {vc.get('dependency', 0)}）："
                     f"trigger＝{rule.get('trigger', '')}；edge＝{rule.get('edge', '')}；exclusions＝{rule.get('exclusions', '')}")
    (LLM / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[:12]))


if __name__ == "__main__":
    main()
