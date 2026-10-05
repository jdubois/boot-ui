# Rubric for the BootUI 2.0 validation rerun

Registered with the protocol in [the validation report](../docs/V2-VALIDATION-REPORT.md#protocol-for-the-rerun) on
2026-10-05, before any rerun. Two reviewers apply it independently, on different models, each with the worksheet, the
collected evidence, and the application's source. A reviewer never reads the other reviewer's file, the scores, or a
previous run's judgments of the same row.

## What a row is

The worksheet (`scoring/worksheet.mjs`) has three sections. Each row is judged once.

- **fact**: a default-visible `OBSERVED` or `PARTIAL` row, as a developer sees it first in the panel. A `PARTIAL`
  row's counts are a floor, since a source it reads dropped events: judge it with that limitation. Rows of one application that
  share kind, subject, and sentence once numbers and ids are removed are one fact; `mergedRows` says how many rows it
  stands for. Rows on different routes are different facts, even with one root cause.
- **honesty**: a default-visible `INSUFFICIENT` row, or a check that did not fully run (`INSUFFICIENT`, `PARTIAL`,
  `NOT_APPLICABLE`, `UNAVAILABLE`), with its reason.
- **hidden**: a row left out of the default list, drawn by the seeded sample, with its `unlistedReason` and `stratum`.

Agent-attached runs (`<app>+agent`) contribute only their registered kinds; their other rows are judged on the run
without the agent.

## Facts and hidden rows

Judge the row as written: its sentence, its subject, its evidence (`insights/<id>.json`), and its "what to check". Open
the source at the lines the evidence points to before deciding.

| Judgment | Meaning | Decide it when |
| --- | --- | --- |
| Actionable | True, and worth changing the code or configuration for | You would open a pull request or change a property because of this row, and the row leads you to the right place |
| Informative | True, and worth knowing, but no change is needed | It explains how the application behaves in a way the developer would want to know, such as where a slow route's time goes |
| Noise | True, but not worth the reader's time | It restates the obvious, is too small to matter, or repeats another row |
| Misleading | False, or true in a way that leads to a wrong change | The number is wrong, the cause it names is wrong, or following "what to check" would change the wrong thing |

Rules:

- Truth comes first. A row whose sentence is right but whose "what to check" points to the wrong fix is Misleading.
- An intentional behaviour presented as a problem (a deliberate 404, BCrypt cost, a demo endpoint that throws) is
  Misleading when the row suggests fixing it, Noise when it only reports it.
- Judge the row, not the kind: a good kind can produce a noisy row, and a weak kind a useful one.
- Size matters only through consequence: a 3 ms route breakdown is Noise unless something in it is surprising.
- For a hidden row, ask the same question as if it were listed. A hidden row you judge Actionable means the default
  list hides value, whatever the other reviewer says; say why in the note.
- Write one sentence of justification with `file:line` in `note`. Leave `judgment` empty only if you cannot decide; the
  scorer then reports the row as missing.

## Honesty rows

| Judgment | Meaning |
| --- | --- |
| Honest | The status and its reason are true for this application, and nothing real was hidden by it |
| Hides | The status is true, but the application did something real here that the reader would want to know and the report does not say (for example, SQL over R2DBC reported as `EVALUATED` with nothing eligible) |
| Misleading | The status or its reason is false (for example, `NOT_APPLICABLE` when the source it needs is present, or "not enough evidence" read as "no change") |

## After the reviews

The maintainer adjudicates, with a reason, every row the two reviewers judged differently, from a list that shows no
score. Two reviewers who agree are never overruled, Misleading included. An adjudication never turns a row into one
"useful to both reviewers": the score counts only rows both reviewers judged Actionable or Informative.
