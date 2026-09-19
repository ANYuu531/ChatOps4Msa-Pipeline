# MicroDepGraph dataset (vendored)

These 20 GraphML files are **not ours**. They are the dependency graphs of 20
open-source microservice projects from:

> Mohammad Imranur Rahman, Sebastiano Panichella, Davide Taibi,
> *A Curated Dataset of Microservices-Based Systems*, Joint Proceedings of the
> Summer School on Software Maintenance and Evolution (SattoSE 2019),
> CEUR-WS Vol-2520 — <https://ceur-ws.org/Vol-2520/paper1a.pdf>

- Source repository: <https://github.com/clowee/MicroserviceDataset>
  (the tool that produced them: <https://github.com/clowee/MicroDepGraph>)
- Retrieved: 2026-09-18, commit `5a8fdce` of branch `master` (files dated 2021-02-26)
- Licence: **LGPL-3.0**, as stated by the source repository's `LICENSE`. Redistributed
  here unmodified, with this notice.
- Only the `.graphml` files are copied; the repository's `.svg` renderings are not.

## Why they are in this repository

`SubgraphLimitsExperimentTest` measures how deep real microservice dependency graphs
are, to justify the partial graph's hop limit on more than the two graphs DepWeaver
itself produced (both of which turned out to have diameter 3). See
`docs/threshold-design.md` section 8.

## What the edges mean — read before quoting any number

MicroDepGraph derives dependencies from **Docker Compose `depends_on` and internal
API calls**, so an edge is a *deployment/declared* dependency, not an observed call.
Infrastructure (kafka, zookeeper, mysql, consul, zipkin…) appears as ordinary nodes,
where DepWeaver's `GraphNormalizer` would treat several of them as platform infra.
Distances measured here are therefore an upper bound on how far apart two *business*
services are, and the corpus says nothing about runtime traffic.
