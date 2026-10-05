"""Agreement of one probe summary against the MicroDepGraph graphml: how many of the
dataset's edges the tool drew. Same question ExternalTruthAgreementTest asks, for one
project and a summary that lives outside docs/."""
import re, sys, xml.etree.ElementTree as ET

summary, graphml = sys.argv[1], sys.argv[2]
ns = {"g": "http://graphml.graphdrawing.org/xmlns"}
dataset = set()
for e in ET.parse(graphml).getroot().iter("{http://graphml.graphdrawing.org/xmlns}edge"):
    dataset.add((e.get("source"), e.get("target")))

drawn = set()
in_edges = False
for line in open(summary, encoding="utf-8"):
    if line.startswith("## "):
        in_edges = line.startswith("## edges")
        continue
    if in_edges:
        m = re.match(r"- (\S+) -> (\S+)", line)
        if m:
            drawn.add((m.group(1), m.group(2)))

hit = sorted(dataset & drawn)
miss = sorted(dataset - drawn)
print(f"dataset edges: {len(dataset)}  drawn: {len(hit)}  agreement: {len(hit)/len(dataset):.2f}")
for s, t in miss:
    print(f"  missing: {s} -> {t}")
