# Semantic router threshold sweep

- embedding model: `text-embedding-3-small`
- intents: 15 (dependencies-of, dependents-of, impact-of, startup-needs, path, uncovered, observed-edges, db-users, deploy-order, undeployed, externals, async, mentioned-only, subgraph, about-report)
- hold-out: 102 questions; routable 92; no-intent 10
- grid: T 0.40–0.85 step 0.01 × M 0.00–0.12 step 0.01 × H {0.80, 0.85, 0.90, 0.95, off} = 2990 points
- coverage = confident / all; confident-wrong = confident but not an expected intent (any confident route on a no-intent row); precision = confident-correct / confident; top-1 = nearest intent after rules is expected, over routable rows

## Current thresholds (T=0.51 M=0.10 H=0.80)

| group | n | coverage | confident-wrong | precision | top-1 | useful coverage |
|---|---|---|---|---|---|---|
| all | 102 | 35.3% | 0 (0.0%) | 100.0% | 77.2% | 39.1% |
| project=bank-of-anthos | 34 | 41.2% | 0 (0.0%) | 100.0% | 80.0% | 46.7% |
| project=train-ticket | 34 | 29.4% | 0 (0.0%) | 100.0% | 64.5% | 32.3% |
| project=sock-shop | 34 | 35.3% | 0 (0.0%) | 100.0% | 87.1% | 38.7% |
| lang=zh | 51 | 35.3% | 0 (0.0%) | 100.0% | 78.3% | 39.1% |
| lang=en | 51 | 35.3% | 0 (0.0%) | 100.0% | 76.1% | 39.1% |
| has_node=yes | 43 | 23.3% | 0 (0.0%) | 100.0% | 71.1% | 26.3% |
| has_node=no | 59 | 44.1% | 0 (0.0%) | 100.0% | 81.5% | 48.1% |

## Selected: confident-wrong = 0, max coverage (ties → higher T, larger M, higher H)

**T=0.51 M=0.10 H=0.80**

| group | n | coverage | confident-wrong | precision | top-1 | useful coverage |
|---|---|---|---|---|---|---|
| all | 102 | 35.3% | 0 (0.0%) | 100.0% | 77.2% | 39.1% |
| project=bank-of-anthos | 34 | 41.2% | 0 (0.0%) | 100.0% | 80.0% | 46.7% |
| project=train-ticket | 34 | 29.4% | 0 (0.0%) | 100.0% | 64.5% | 32.3% |
| project=sock-shop | 34 | 35.3% | 0 (0.0%) | 100.0% | 87.1% | 38.7% |
| lang=zh | 51 | 35.3% | 0 (0.0%) | 100.0% | 78.3% | 39.1% |
| lang=en | 51 | 35.3% | 0 (0.0%) | 100.0% | 76.1% | 39.1% |
| has_node=yes | 43 | 23.3% | 0 (0.0%) | 100.0% | 71.1% | 26.3% |
| has_node=no | 59 | 44.1% | 0 (0.0%) | 100.0% | 81.5% | 48.1% |

## Selected: confident-wrong ≤ 1, max coverage

**T=0.51 M=0.08 H=0.80**

| group | n | coverage | confident-wrong | precision | top-1 | useful coverage |
|---|---|---|---|---|---|---|
| all | 102 | 43.1% | 1 (1.0%) | 97.7% | 77.2% | 46.7% |
| project=bank-of-anthos | 34 | 44.1% | 0 (0.0%) | 100.0% | 80.0% | 50.0% |
| project=train-ticket | 34 | 41.2% | 1 (2.9%) | 92.9% | 64.5% | 41.9% |
| project=sock-shop | 34 | 44.1% | 0 (0.0%) | 100.0% | 87.1% | 48.4% |
| lang=zh | 51 | 45.1% | 1 (2.0%) | 95.7% | 78.3% | 47.8% |
| lang=en | 51 | 41.2% | 0 (0.0%) | 100.0% | 76.1% | 45.7% |
| has_node=yes | 43 | 25.6% | 0 (0.0%) | 100.0% | 71.1% | 28.9% |
| has_node=no | 59 | 55.9% | 1 (1.7%) | 97.0% | 81.5% | 59.3% |

## Score bands (best intent score after masking)

| band | n | min | p10 | median | p90 | max |
|---|---|---|---|---|---|---|
| example typed verbatim | 159 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| example, leave-one-out (nearest OTHER example) | 159 | 0.335 | 0.514 | 0.670 | 0.836 | 0.937 |
| hold-out, top-1 correct | 71 | 0.466 | 0.521 | 0.641 | 0.778 | 0.911 |
| hold-out, top-1 wrong | 21 | 0.462 | 0.510 | 0.603 | 0.696 | 0.784 |
| hold-out, no matching intent (none) | 10 | 0.281 | 0.281 | 0.372 | 0.507 | 0.631 |

Leave-one-out: the nearest other example belongs to the same intent for 105 / 159 examples.

## Leave-one-project-out (select on two projects with the zero-wrong rule, evaluate on the third)

| held-out project | selected on the other two | train coverage | train conf-wrong | test n | test coverage | test conf-wrong | test precision |
|---|---|---|---|---|---|---|---|
| bank-of-anthos | T=0.51 M=0.10 H=0.80 | 32.4% | 0 | 34 | 41.2% | 0 | 100.0% |
| train-ticket | T=0.51 M=0.08 H=off | 44.1% | 0 | 34 | 38.2% | 1 | 92.3% |
| sock-shop | T=0.55 M=0.10 H=0.80 | 35.3% | 0 | 34 | 29.4% | 0 | 100.0% |

## Confident-wrong rows at the current thresholds

(none)
