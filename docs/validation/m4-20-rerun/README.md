# M4-20 rerun data (`m4-20-protocol-2`)

The data behind the [rerun results](../../V2-VALIDATION-REPORT.md#rerun-results). Everything was produced by the
registered harness on one build of `5bd7cb76e` (engine SHA-256 `e913e36b…`), except the files marked as the operator's
or the maintainer's below.

| File | What it is |
| --- | --- |
| `evidence/` | What `collect.mjs` saved for each run: the full and default `runtime-insights` reports, the agent views, the comparison, traffic summaries, panels, and `run.json` with the commit, engine, and harness hashes |
| `worksheet.json`, `judgments-template.csv` | The worksheet `worksheet.mjs` built from the evidence, and the template both reviewers filled |
| `r1.csv`, `r2.csv` | The two reviewers' judgments: r1 on claude-opus-5.5, r2 on gpt-6-sol |
| `to-adjudicate.csv` | `score.mjs --to-adjudicate`: the 26 rows the maintainer must settle |
| `adjudication.csv`, `recall.csv` | Maintainer: his rulings on the 26 rows and his marks for the 52 recall items, committed as he wrote them (see the report's Adjudication section for how he decided) |
| `score/score.json`, `score/score.md` | The registered scorer's final output, run from a detached checkout of the `m4-20-protocol-2` tag with the command below; Spotless trims `score.md`'s two trailing blank lines, nothing else differs |
| `recall-evidence.md` | Operator: for each known miss and counterexample, the rows and coverage lines that name it. No outcome |
| `investigations.csv`, `investigations-grading.md` | The ten investigations per arm, graded by an independent agent, with call counts from the CLI wrapper's log |
| `ttfo.jsonl`, `ttfo-load.log` | `ttfo.sh`'s measurements, and the load average before and after each one |
| `provisional-score.json` | Operator: the registered `judge()` and `score()` run with no adjudication, as published before the rulings. Superseded by `score/` |

To rescore, from a checkout of `5bd7cb76e` with the tag
`m4-20-protocol-2` fetched (that commit predates this directory, so copy it out of `v2` first):

```bash
D=/path/to/m4-20-rerun
node validation/scoring/score.mjs --worksheet "$D/worksheet.json" --evidence "$D/evidence" \
  --reviewer r1="$D/r1.csv" --reviewer r2="$D/r2.csv" --adjudication "$D/adjudication.csv" \
  --recall-judgments "$D/recall.csv" --investigations "$D/investigations.csv" --ttfo "$D/ttfo.jsonl" --out "$D/score"
```

`score.md`'s time-to-first-observation table prints Kafka's unreached measurement (`seconds: null`) as `0.0` minutes;
the report gives the right value.

Local paths in the evidence were replaced mechanically, and nothing else changed: the rerun worktree by `<rerun>` and the
home directory by `~`, in 44 files (`java-agent.json`, `proof.env`, and the comparisons). No path is read by the
worksheet or the scorer, which accept the redacted evidence.
