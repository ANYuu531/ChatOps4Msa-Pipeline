#!/usr/bin/env python3
"""The LLM step of rule mining. Runs on machine A or B only (it calls OpenAI).

Two tasks, both reading files that mine_residue.py prepared locally (the code context is
already inside them, so this machine needs no checkout):

  --task clusters   samples.jsonl: one residue group per line (a shape that occurs in >= 2
                    repos, up to 6 samples from distinct repos). The model judges every
                    sample (dependency / not-dependency / wrong-source / unsure) and says
                    whether one general, syntax-level rule separates the dependencies from
                    the rest. Its proposal is a candidate only: the author verifies every
                    sample before a rule is written, and the rule is measured on hold-out E.
  --task edges      edge_samples.jsonl: edges the static layer drew (fixed-seed sample per
                    repo) with the cited lines. The model judges whether the evidence shows
                    a runtime dependency. This estimates precision on systems nobody wrote a
                    reference edge set for; disagreements are checked by the author.

Same model and settings as the rest of the paper: gpt-4.1-mini, temperature 0, JSON mode.
Only the standard library. API key from OPENAI_API_KEY or openai.api.key in
src/main/resources/application.properties.

  python3 -I docs/generalization/mining/label_clusters.py --task clusters \
      --in docs/generalization/mining/out/samples.jsonl --out docs/generalization/mining/llm
  python3 -I docs/generalization/mining/label_clusters.py --task edges \
      --in docs/generalization/mining/out/edge_samples.jsonl --out docs/generalization/mining/llm
  (add --dry-run to write the prompts without calling the API)
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAX_OUTPUT_TOKENS = 4000

CLUSTER_PROMPT = """You are helping to improve a static dependency extractor for microservice systems.
The extractor reads a repository (source code, Docker Compose, Kubernetes/Helm, config files) and
draws an edge "A -> B" when component A calls, connects to, or sends messages to component B at runtime.

Below are places where a name that the repository itself declares as a component (TARGET) appears
in an address-like position, but the extractor drew NO edge from the component that owns the line
(SOURCE) to TARGET. All samples share one syntactic shape:
  file kind = {kind}; position = {cls}; context key = {detail}
They come from {repos} different repositories ({mentions} mentions in total).

For each sample, judge whether the marked line (>>) shows that SOURCE depends on TARGET at runtime:
  "dependency"     - SOURCE really calls / connects to / sends to TARGET
  "wrong-source"   - it is a runtime dependency on TARGET, but of a different component than SOURCE
                     (say which one in "reason", e.g. the component a config file is mounted into)
  "not-dependency" - e.g. a label, a name, documentation, monitoring metadata, a dashboard query,
                     a test fixture, a value that is never used to reach TARGET
  "unsure"         - the context is not enough
Then say whether ONE general rule, stated in terms of syntax and file kind only (it must not name any
specific project, service, host or company), would turn the "dependency" samples into edges without
also drawing the "not-dependency" ones. If the samples are mixed and no syntactic feature separates
them, say so with "general": false.

Answer as JSON:
{{"samples": [{{"i": <index>, "verdict": "...", "reason": "<one sentence>"}}],
  "rule": {{"general": true|false, "trigger": "<file kind + syntactic position>",
           "edge": "<which component -> which component>", "exclusions": "<when not to draw>",
           "confidence": "high|medium|low"}},
  "summary": "<one sentence>"}}

Samples:
{samples}
"""

EDGE_PROMPT = """You are auditing a static dependency extractor for microservice systems. For each edge
"SOURCE -> TARGET" below, the extractor cites the line(s) it drew the edge from (marked >>). Judge
whether the cited evidence shows that SOURCE depends on TARGET at runtime (calls it, connects to it,
sends messages to it, or is routed to it by a gateway/proxy that SOURCE is):
  "correct"  - the evidence supports the edge as drawn
  "wrong"    - the evidence does not support it (say why: wrong direction, wrong source component,
               declaration never used, documentation/metadata, test-only, ...)
  "unsure"   - the shown context is not enough to decide
Do not judge by plausibility of names; judge by the evidence shown.

Answer as JSON: {{"edges": [{{"i": <index>, "verdict": "...", "reason": "<one sentence>"}}]}}

Edges:
{edges}
"""


def load_api():
    key = os.environ.get("OPENAI_API_KEY", "")
    model = os.environ.get("OPENAI_MODEL", "")
    props = ROOT / "src/main/resources/application.properties"
    if props.exists():
        text = props.read_text(encoding="utf-8")
        if not key:
            m = re.search(r"^openai\.api\.key=(.*)$", text, re.M)
            key = m.group(1).strip() if m else ""
        if not model:
            m = re.search(r"^openai\.api\.model=(.*)$", text, re.M)
            model = m.group(1).strip() if m else ""
    return key, (model or "gpt-4.1-mini")


def chat(key, model, prompt, retries=4):
    body = {"model": model, "temperature": 0, "response_format": {"type": "json_object"},
            "max_tokens": MAX_OUTPUT_TOKENS, "messages": [{"role": "user", "content": prompt}]}
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                data = json.load(r)
            choice = data["choices"][0]
            usage = dict(data.get("usage", {}))
            usage["response_model"] = data.get("model", "")
            usage["request_params"] = {k: v for k, v in body.items() if k != "messages"}
            return choice["message"]["content"], usage, choice.get("finish_reason", "")
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="replace")
            if e.code == 429 and "insufficient_quota" in msg:
                raise SystemExit("OpenAI: no credits remaining — top up the account, then rerun.")
            if e.code in (429, 500, 502, 503) and attempt + 1 < retries:
                time.sleep(10 * (attempt + 1))
                continue
            raise SystemExit(f"OpenAI HTTP {e.code}: {msg[:400]}")
        except (urllib.error.URLError, TimeoutError):
            if attempt + 1 < retries:
                time.sleep(10 * (attempt + 1))
                continue
            raise


def cluster_prompts(rows):
    for row in rows:
        parts = []
        for i, s in enumerate(row["samples"]):
            parts.append(f"[{i}] repo={s['repo']} SOURCE={s['source']} TARGET={s['target']} "
                         f"file={s['file']}:{s['line']}\n{s.get('context', '')}")
        yield f"cluster-{row['rank']:04d}", CLUSTER_PROMPT.format(
            kind=row["kind"], cls=row["cls"], detail=row["detail"], repos=row["repos"],
            mentions=row["mentions"], samples="\n\n".join(parts)), row


def edge_prompts(rows, batch=10):
    for b in range(0, len(rows), batch):
        chunk = rows[b:b + batch]
        parts = []
        for i, e in enumerate(chunk):
            ev = "\n".join(f"  cited {x['ref']}\n{x.get('context', '')}" for x in e["evidence"])
            parts.append(f"[{i}] repo={e['repo']} {e['source']} -> {e['target']} (type {e['type']})\n{ev}")
        yield f"edges-{b // batch:04d}", EDGE_PROMPT.format(edges="\n\n".join(parts)), chunk


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", choices=["clusters", "edges"], required=True)
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()

    rows = [json.loads(l) for l in open(args.inp, encoding="utf-8") if l.strip()]
    jobs = list(cluster_prompts(rows) if args.task == "clusters" else edge_prompts(rows))
    out = Path(args.out)
    (out / "prompts").mkdir(parents=True, exist_ok=True)
    (out / "raw").mkdir(parents=True, exist_ok=True)
    for jid, prompt, _ in jobs:
        (out / "prompts" / f"{jid}.txt").write_text(prompt, encoding="utf-8")
    print(f"{len(jobs)} prompts, {sum(len(p) for _, p, _ in jobs)} chars", file=sys.stderr)
    if args.dry_run:
        return
    key, model = load_api()
    if not key:
        raise SystemExit("no API key: set OPENAI_API_KEY or openai.api.key in application.properties")

    def run(job):
        jid, prompt, payload = job
        cache = out / "raw" / f"{jid}.json"
        if cache.exists():  # rerunning resumes; delete raw/ to start over
            return json.loads(cache.read_text(encoding="utf-8"))
        text, usage, finish = chat(key, model, prompt)
        rec = {"id": jid, "model": model, "usage": usage, "finish_reason": finish, "response": text,
               "payload": payload}
        cache.write_text(json.dumps(rec, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{jid}: {finish}", file=sys.stderr)
        return rec

    with ThreadPoolExecutor(args.workers) as ex:
        recs = list(ex.map(run, jobs))
    tokens = sum(r["usage"].get("prompt_tokens", 0) for r in recs)
    print(f"done: {len(recs)} calls, {tokens} prompt tokens", file=sys.stderr)


if __name__ == "__main__":
    main()
