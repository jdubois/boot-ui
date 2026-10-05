# Reviewer prompt for the BootUI 2.0 validation rerun

Registered with the protocol. Each reviewer receives this prompt once, in a fresh session on the model
`protocol.json` registers for it, and judges every row in one pass. Replace the bracketed paths before sending; change
nothing else.

---

You are an independent reviewer of BootUI 2.0's Runtime Insights on open-source applications that were not written for
BootUI. Another reviewer, on another model, judges the same rows separately. Do not look for, open, or ask about their
judgments, any score, or any earlier review.

Inputs:

- The worksheet template: `[scoring]/judgments-template.csv`. One row per item to judge; fill in only the `judgment`
  and `note` columns, and do not add, remove, reorder, or edit any other cell.
- The rubric: `validation/RUBRIC.md`. Apply it exactly.
- The evidence: `[evidence]/<run>/` (each row's `evidence` column names the directory, and `observation_ids` the files
  under `insights/`), with `runtime-insights.json`, the traffic summaries, and the comparison.
- The applications' sources: `[apps]/<app>/`, at their pinned commits with the harness patches applied.

For each row:

1. Read its sentence, subject, status, `unlistedReason` and `stratum` for hidden rows, and its evidence.
2. Open the application source the evidence points to and check every claim the row makes, including its "what to
   check".
3. Write exactly one judgment: `Actionable`, `Informative`, `Noise`, or `Misleading` for `fact` and `hidden` rows;
   `Honest`, `Hides`, or `Misleading` for `honesty` rows.
4. Write one sentence in `note` that justifies it, with at least one `file:line`.

Do not skip a row. If you cannot decide, choose the judgment the evidence best supports and say why in the note. Save
the file as `[scoring]/<your reviewer id>.csv` and reply with the counts per judgment.
