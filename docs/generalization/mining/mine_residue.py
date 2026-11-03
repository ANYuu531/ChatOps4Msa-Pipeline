#!/usr/bin/env python3
"""Residue mining: where does a corpus mention a deployed service in an address-shaped
place, and the static layer drew no edge for it?

No LLM and no reference edge set is involved. For every repo in the corpus the batch probe
(MiningProbeTest) has written <name>.mining.json: the graph's nodes and edges (with every
provenance ref) and the ledger (every extracted fact with its file:line). This script

  1. takes the vocabulary of names the repo itself declares (graph nodes plus the raw
     Compose / Kubernetes / service-root names in the ledger);
  2. scans every text file of the checkout for a token-bounded mention of one of those
     names in an address-shaped position: a URL host, host:port, the value of an
     address-like key (HOST, URL, ADDR, ENDPOINT, BROKERS, ...), or a string literal that is
     exactly the name;
  3. decides which module the mention belongs to (the module directory for code, the
     enclosing Compose service or Kubernetes object for descriptors);
  4. keeps the mention as residue when the graph has no edge module -> name, and tags it
     line-read (the ledger read that very line), file-read (the ledger read the file, not
     that line) or unread (no extractor read the file);
  5. groups residue by its shape -- file kind | context class | normalized context -- and
     ranks the groups by the number of repos they occur in, then by mentions.

A group that occurs in many repos is a candidate for one general rule; a group that occurs
in one repo is a candidate for "a single system's idiom" and is left alone. Whether a group
is really a dependency is not decided here: label_clusters.py asks an LLM to judge samples
and the author verifies. Run:

  python3 -I mine_residue.py --list corpus.list --probe-out <dir with *.mining.json> --out <dir>

where corpus.list has lines  name<TAB>/path/to/checkout .
"""
import argparse
import collections
import json
import os
import random
import re
import sys

SKIP_DIRS = {".git", "node_modules", "vendor", "target", "build", "dist", "out", ".venv", "venv",
             "__pycache__", ".gradle", ".mvn", ".idea", "site-packages", "third_party", "generated",
             "testdata", ".next", "bin", "obj", "coverage"}
TEST_SEG = re.compile(r"(^|/)(test|tests|testing|spec|__tests__|e2e|it|integration-test)(/|$)", re.I)
TEST_FILE = re.compile(r"(_test\.go|\.(spec|test|cy)\.(js|jsx|mjs|cjs|ts|tsx)|Tests?\.(java|kt|cs)|^test_.*\.py)$")
DOC_EXT = {".md", ".rst", ".adoc", ".txt", ".html", ".htm", ".svg", ".csv", ".log", ".ipynb"}
BIN_EXT = {".png", ".jpg", ".jpeg", ".gif", ".ico", ".jar", ".zip", ".gz", ".tgz", ".pdf", ".woff",
           ".woff2", ".ttf", ".eot", ".class", ".so", ".dll", ".exe", ".bin", ".pb", ".mp4", ".webp"}
LOCK_FILES = {"package-lock.json", "yarn.lock", "pnpm-lock.yaml", "go.sum", "poetry.lock",
              "Pipfile.lock", "composer.lock", "Gemfile.lock", "Cargo.lock", "packages.lock.json"}
MAX_BYTES = 512 * 1024
STOP = {"api", "app", "web", "www", "server", "service", "client", "main", "common", "test",
        "local", "default", "public", "static", "data", "base", "core", "config", "docs", "src"}
SOURCE_EXT = {".java": "java", ".kt": "kotlin", ".kts": "kotlin", ".py": "python", ".go": "go",
              ".js": "javascript", ".mjs": "javascript", ".cjs": "javascript", ".jsx": "javascript",
              ".ts": "typescript", ".tsx": "typescript", ".php": "php", ".cs": "csharp",
              ".rs": "rust", ".rb": "ruby", ".scala": "scala", ".cpp": "cpp", ".cc": "cpp",
              ".c": "c", ".ex": "elixir", ".exs": "elixir", ".lua": "lua", ".dart": "dart"}
ADDR_KEY = re.compile(r"(host|hostname|url|uri|addr|address|endpoint|server|servers|broker|"
                      r"brokers|dsn|connection|connectionstring|target|upstream|proxy_pass|"
                      r"backend|origin|base|location|nodes|seeds|bootstrap|discovery|zone|"
                      r"service|svc|api|gateway|registry|redirect|issuer|authority)$", re.I)
URL_BEFORE = re.compile(r"[a-z][a-z0-9+.\-]*://(?:[^/\s@'\"]*@)?$", re.I)
CALL_BEFORE = re.compile(r"([A-Za-z_$][\w$]*)\s*\(\s*[^()]*$")
KEY_LINE = re.compile(r"""^\s*(?:-\s+)?(?:export\s+)?["']?([A-Za-z0-9_.\-\[\]/:$@{}]+?)["']?\s*(?::|=|\?\?=|:=)\s*(.*)$""")


def file_kind(rel):
    base = os.path.basename(rel)
    low = base.lower()
    ext = os.path.splitext(low)[1]
    parts = rel.lower().split("/")
    if re.match(r"(docker-)?compose[^/]*\.ya?ml$", low):
        return "compose"
    if low.startswith("dockerfile") or low.endswith(".dockerfile"):
        return "dockerfile"
    if low == ".env" or low.startswith(".env.") or low.endswith(".env"):
        return "dotenv"
    if ext in (".yml", ".yaml"):
        if "templates" in parts and any(p in ("charts", "helm", "chart") or p.endswith("chart") for p in parts):
            return "helm-template"
        if low.startswith("values") and any(p in ("charts", "helm", "chart") for p in parts):
            return "helm-values"
        if re.match(r"(application|bootstrap)([-_.].*)?\.ya?ml$", low):
            return "spring-yaml"
        return "yaml"
    if ext == ".properties":
        return "properties"
    if re.match(r"appsettings.*\.json$", low):
        return "appsettings"
    if ext == ".json":
        return "json"
    if ext == ".toml":
        return "toml"
    if ext in (".conf", ".tmpl", ".template") and "nginx" in rel.lower():
        return "nginx"
    if ext in (".conf", ".cfg", ".ini"):
        return "conf"
    if ext == ".xml":
        return "xml"
    if ext in (".sh", ".bash", ".ps1"):
        return "shell"
    if low in ("makefile",) or ext == ".mk":
        return "makefile"
    if ext in (".tf", ".hcl"):
        return "terraform"
    if ext in SOURCE_EXT:
        return "src:" + SOURCE_EXT[ext]
    return "other:" + (ext or base)


def norm(name):
    n = name.lower()
    n = re.sub(r"-v\d+$", "", n)
    return re.sub(r"[^a-z0-9]", "", n)


def walk(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS and not x.startswith(".")]
        for f in files:
            p = os.path.join(d, f)
            rel = os.path.relpath(p, root).replace(os.sep, "/")
            ext = os.path.splitext(f)[1].lower()
            if ext in BIN_EXT or ext in DOC_EXT or f in LOCK_FILES:
                continue
            if TEST_SEG.search(rel) or TEST_FILE.search(f):
                continue
            if f.endswith((".min.js", ".map")):
                continue
            try:
                if os.path.getsize(p) > MAX_BYTES:
                    continue
                with open(p, "rb") as fh:
                    raw = fh.read()
            except OSError:
                continue
            if b"\0" in raw[:4096]:
                continue
            yield rel, raw.decode("utf-8", "replace")


class Yaml:
    """Indentation-tracked key path and owning object for every line of a YAML file."""

    def __init__(self, text, kind):
        self.paths, self.owners = [], []
        stack = []  # (indent, key)
        owner_doc = None
        compose_service = None
        for line in text.split("\n"):
            if re.match(r"^---", line):
                stack, owner_doc, compose_service = [], None, None
                self.paths.append("")
                self.owners.append(None)
                continue
            s = line.rstrip()
            content = s.lstrip()
            if not content or content.startswith("#"):
                self.paths.append(".".join(k for _, k in stack))
                self.owners.append(compose_service or owner_doc)
                continue
            indent = len(s) - len(content)
            item = content.startswith("- ")
            if item:
                content = content[2:]
                indent += 2
            m = re.match(r"""^["']?([^"':#{}\[\]]+?)["']?\s*:(\s|$)""", content)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            key = m.group(1).strip() if m else None
            if key:
                stack.append((indent, ("[]." if item else "") + key))
            path = ".".join(k for _, k in stack)
            if kind == "compose" and len(stack) >= 2 and stack[0][1] == "services" and stack[0][0] == 0:
                compose_service = stack[1][1]
            elif kind == "compose" and stack and stack[0][1] != "services":
                compose_service = None
            if kind != "compose" and key == "name" and len(stack) == 2 and stack[0][1] == "metadata" and owner_doc is None:
                v = content.split(":", 1)[1].strip().strip("'\"")
                owner_doc = v or None
            self.paths.append(path)
            self.owners.append(compose_service or owner_doc)


# Keys whose values name things but are not addresses: labels and selectors, images,
# a service's own name. A mention there says what an object is called, not whom it calls.
NOT_ADDR_PATH = re.compile(r"(^|\.)(\[\]\.)?(labels|matchLabels|selector|annotations|image|container_name|"
                           r"hostname|name|app|serviceName|serviceAccountName|claimName|configMap|secretName|"
                           r"volumes|depends_on|links|networks|aliases|extends)(\.|$)")


def context_of(line, start, end, name, kind, ypath):
    """Address-shaped context of a mention, or None. Returns (class, detail)."""
    before, after = line[:start], line[end:]
    is_src = kind.startswith("src:")
    if ypath and NOT_ADDR_PATH.search(ypath):
        return None
    if re.search(r"\bimage\s*[:=]", before):
        return None
    if URL_BEFORE.search(before):
        cls = "url"
    elif re.match(r"^:\d{2,5}(?!\d)", after) or re.match(r"^:\$\{", after):
        cls = "hostport"
    else:
        cls = None
    key = None
    m = KEY_LINE.match(line)
    if m and m.start(2) <= start:
        key = m.group(1)
    elif ypath:
        key = ypath.split(".")[-1]
    if cls is None and key is not None:
        last = re.split(r"[.\[\]/:]+", key.strip("${}"))
        last = [x for x in last if x]
        k = last[-1] if last else key
        k2 = re.sub(r"[_\-]", "", k)
        if ADDR_KEY.search(k2) or ADDR_KEY.search(k):
            cls = "key"
    if cls is None and is_src:
        q = before[-1:] if before else ""
        if q in "\"'`" and q and after[:1] == q:
            cls = "literal"
    if cls is None:
        return None
    # detail: what a general rule would key on
    if is_src:
        cm = CALL_BEFORE.search(before)
        callee = cm.group(1).split(".")[-1] if cm else None
        if cls == "key" and key:
            detail = "key " + key_class(key)
        elif callee:
            detail = "call " + callee.lower()
        else:
            am = re.search(r"([A-Za-z_][\w]*)\s*(?::\s*\w+)?\s*:?=\s*[^=]*$", before)
            detail = "assign " + key_class(am.group(1)) if am else "bare"
    else:
        detail = key_class(ypath if ypath else (key or ""), yaml=bool(ypath))
    return cls, detail


def key_class(key, yaml=False):
    """Normalize a key so that the same convention in different repos lands in one group."""
    if not key:
        return "-"
    if yaml:
        # the last three segments name the convention (environment.X_HOST, routes[].uri);
        # compose service and env names are collapsed so that repos land in one group
        segs = [s for s in key.split(".") if s and s != "[]"][-3:]
        out = []
        for j, s in enumerate(segs):
            if re.fullmatch(r"[A-Z0-9_]+", s):
                out.append(env_class(s))
            elif j + 1 < len(segs) and segs[j + 1] in ("environment", "[]environment", "build", "image"):
                out.append("<service>")
            else:
                out.append(s)
        return ".".join(out)
    k = key.strip().strip("${}")
    if re.fullmatch(r"[A-Z0-9_]+", k):
        return env_class(k)
    parts = re.split(r"[.\[\]]+", k)
    return ".".join(p for p in parts[-3:] if p).lower() or "-"


def env_class(k):
    for suf in ("HOST", "HOSTNAME", "URL", "URI", "ADDR", "ADDRESS", "ENDPOINT", "SERVER", "SERVERS",
                "BROKER", "BROKERS", "DSN", "TARGET", "UPSTREAM", "SERVICE", "SVC", "API", "BASE"):
        if k == suf or k.endswith("_" + suf):
            return "*_" + suf
    return "*"


def mine_repo(name, root, mining):
    nodes = mining["nodes"]
    edges = mining["edges"]
    ledger = mining["ledger"]
    vocab = {}
    for n in nodes:
        if n["kind"] in ("external",):
            continue
        vocab[n["id"]] = n["id"]
        first = n["id"].split(".")[0]
        if first != n["id"]:
            vocab.setdefault(first, n["id"])
    for e in ledger:
        if e["section"] in ("compose-service", "k8s-workload", "k8s-service", "service-root"):
            nm = e["fields"].get("name")
            if nm:
                vocab.setdefault(nm, nm)
    names = sorted({v for v in vocab if len(v) >= 3 and v.lower() not in STOP}, key=len, reverse=True)
    if not names:
        return []
    pat = re.compile(r"(?<![A-Za-z0-9_\-])(" + "|".join(re.escape(v) for v in names) + r")(?![A-Za-z0-9_\-])")

    modules = []  # (dir, module name)
    for e in ledger:
        if e["section"] == "service-root":
            modules.append((e["fields"].get("dir", "").strip("/"), e["fields"].get("name")))
    modules.sort(key=lambda x: len(x[0]), reverse=True)
    declared = {norm(v) for v in vocab}

    def module_of(rel):
        for d, m in modules:
            if d in ("", ".") or rel == d or rel.startswith(d + "/"):
                if d not in ("", "."):
                    return m
        segs = rel.split("/")[:-1]
        for s in reversed(segs):  # a directory named exactly like a declared service
            if norm(s) in declared:
                return s
        return None

    have = set()
    for e in edges:
        have.add((norm(e["source"]), norm(e["target"])))
        have.add((norm(e["source"]), norm(e["target"].split(".")[0])))
        have.add((norm(e["source"].split(".")[0]), norm(e["target"].split(".")[0])))
    read_lines = set()
    read_files = set()
    for e in ledger:
        read_files.add(e["file"])
        if e["line"] and e["line"] > 0:
            read_lines.add((e["file"], e["line"]))
    for e in edges:
        for r in e["refs"]:
            m = re.match(r"^\w[\w-]*:\s*(.+?):(\d+)$", r)
            if m:
                read_lines.add((m.group(1), int(m.group(2))))
                read_files.add(m.group(1))

    out = []
    for rel, text in walk(root):
        kind = file_kind(rel)
        lines = text.split("\n")
        yml = Yaml(text, kind) if kind in ("compose", "yaml", "helm-template", "helm-values", "spring-yaml") else None
        mod = module_of(rel)
        for i, line in enumerate(lines):
            if len(line) > 600:
                continue
            for m in pat.finditer(line):
                target = m.group(1)
                ypath = yml.paths[i] if yml and i < len(yml.paths) else None
                ctx = context_of(line, m.start(1), m.end(1), target, kind, ypath)
                if not ctx:
                    continue
                owner = (yml.owners[i] if yml and i < len(yml.owners) and yml.owners[i] else None) or mod
                tn = norm(vocab.get(target, target))
                if owner is not None:
                    on = norm(owner)
                    if on == tn or on == norm(target) or norm(owner.split(".")[0]) == norm(target):
                        continue  # a service naming itself
                    if (on, tn) in have or (on, norm(target)) in have:
                        continue
                else:
                    # nobody owns the file: residue only if no edge to this target cites the file
                    if any(norm(e["target"]) in (tn, norm(target)) and any(rel in r for r in e["refs"]) for e in edges):
                        continue
                lineno = i + 1
                tag = "line-read" if (rel, lineno) in read_lines else ("file-read" if rel in read_files else "unread")
                out.append({
                    "repo": name, "source": owner or "?", "target": target, "file": rel, "line": lineno,
                    "kind": kind, "cls": ctx[0], "detail": ctx[1], "tag": tag,
                    "text": line.strip()[:240],
                })
    return out


def snippet(root, rel, line, k=4):
    """Lines line-k .. line+k of a file, the cited line marked with '>>'."""
    try:
        with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as fh:
            lines = fh.read().split("\n")
    except OSError:
        return ""
    if line <= 0:
        return "\n".join(f"   {l[:200]}" for l in lines[:2 * k + 1])
    lo, hi = max(1, line - k), min(len(lines), line + k)
    return "\n".join((">> " if j == line else "   ") + f"{j}: " + lines[j - 1][:200] for j in range(lo, hi + 1))


def first_mention(root, rel, target):
    """A descriptor edge cites a file without a line: the first line naming the target."""
    try:
        with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as fh:
            lines = fh.read().split("\n")
    except OSError:
        return 0
    t = target.split(".")[0]
    for j, l in enumerate(lines, 1):
        if re.search(r"(?<![A-Za-z0-9_\-])" + re.escape(t) + r"(?![A-Za-z0-9_\-])", l):
            return j
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", required=True)
    ap.add_argument("--probe-out", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--samples", type=int, default=6)
    ap.add_argument("--edges-per-repo", type=int, default=3,
                    help="edges drawn per repo (fixed seed) for the LLM edge audit")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    allres, repos_seen, paths, minings = [], 0, {}, {}
    for row in open(args.list):
        if not row.strip() or row.startswith("#"):
            continue
        name, path = row.rstrip("\n").split("\t")[:2]
        mpath = os.path.join(args.probe_out, name + ".mining.json")
        if not os.path.exists(mpath):
            print(f"skip {name}: no mining.json", file=sys.stderr)
            continue
        repos_seen += 1
        paths[name] = path
        minings[name] = json.load(open(mpath))
        res = mine_repo(name, path, minings[name])
        allres.extend(res)
        print(f"{name}: {len(res)} residue mentions", file=sys.stderr)

    with open(os.path.join(args.out, "residue.tsv"), "w") as f:
        cols = ["repo", "source", "target", "file", "line", "kind", "cls", "detail", "tag", "text"]
        f.write("\t".join(cols) + "\n")
        for r in allres:
            f.write("\t".join(str(r[c]).replace("\t", " ") for c in cols) + "\n")

    groups = collections.defaultdict(list)
    for r in allres:
        groups[(r["kind"], r["cls"], r["detail"])].append(r)
    ranked = sorted(groups.items(), key=lambda kv: (-len({r["repo"] for r in kv[1]}), -len(kv[1]), kv[0]))

    with open(os.path.join(args.out, "clusters.tsv"), "w") as f:
        f.write("rank\tkind\tcls\tdetail\trepos\tmentions\tpairs\ttags\texample\n")
        for i, ((k, c, d), rs) in enumerate(ranked, 1):
            pairs = len({(r["repo"], r["source"], r["target"]) for r in rs})
            tags = collections.Counter(r["tag"] for r in rs)
            ex = rs[0]
            f.write(f"{i}\t{k}\t{c}\t{d}\t{len({r['repo'] for r in rs})}\t{len(rs)}\t{pairs}\t"
                    f"{','.join(f'{t}={n}' for t, n in sorted(tags.items()))}\t"
                    f"{ex['repo']}:{ex['file']}:{ex['line']}\n")

    # samples for the LLM labeler: groups seen in >= 2 repos, one mention per repo first
    with open(os.path.join(args.out, "samples.jsonl"), "w") as f:
        for i, ((k, c, d), rs) in enumerate(ranked, 1):
            nrepo = len({r["repo"] for r in rs})
            if nrepo < 2:
                continue
            picked, seen = [], set()
            for r in rs:
                if r["repo"] not in seen:
                    picked.append(r)
                    seen.add(r["repo"])
                if len(picked) >= args.samples:
                    break
            for r in picked:
                r["context"] = snippet(paths[r["repo"]], r["file"], r["line"])
            f.write(json.dumps({"rank": i, "kind": k, "cls": c, "detail": d, "repos": nrepo,
                                "mentions": len(rs), "samples": picked}, ensure_ascii=False) + "\n")

    # edges the static layer drew, a fixed-seed sample per repo, for the LLM edge audit
    rnd = random.Random(20261103)
    with open(os.path.join(args.out, "edge_samples.jsonl"), "w") as f:
        for name in sorted(minings):
            es = sorted(minings[name]["edges"], key=lambda e: (e["source"], e["target"]))
            for e in rnd.sample(es, min(args.edges_per_repo, len(es))):
                ev = []
                for ref in e["refs"][:2]:
                    m = re.match(r"^(\w[\w-]*):\s*(.+?)(?::(\d+))?$", ref)
                    if not m:
                        ev.append({"ref": ref, "context": ""})
                        continue
                    file, line = m.group(2), int(m.group(3)) if m.group(3) else 0
                    if not line:
                        line = first_mention(paths[name], file, e["target"])
                    ev.append({"ref": ref, "file": file, "line": line,
                               "kind": file_kind(file), "context": snippet(paths[name], file, line)})
                f.write(json.dumps({"repo": name, "source": e["source"], "target": e["target"],
                                    "type": e["type"], "confidence": e["confidence"], "evidence": ev},
                                   ensure_ascii=False) + "\n")

    multi = sum(1 for _, rs in ranked if len({r["repo"] for r in rs}) >= 2)
    print(f"{repos_seen} repos, {len(allres)} residue mentions, {len(ranked)} shapes, "
          f"{multi} shapes in >= 2 repos", file=sys.stderr)


if __name__ == "__main__":
    main()
