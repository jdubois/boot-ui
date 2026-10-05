# BootUI 2.0 validation harness

This directory reruns the external validation of [the validation report](../docs/V2-VALIDATION-REPORT.md) under the
protocol it registers: seven open-source applications (five tuned, two holdouts), their pinned commits and patches,
the traffic each one receives, the collector, the reviewers' worksheet, the rubric, and the scorer. It is a working
tool for the maintainer, not part of BootUI: it is not a Maven module, nothing here is built or published by a release,
and nothing in it runs against the application on port 8080.

## Requirements

- macOS or Linux with bash, Git, curl, Docker, and Node.js 20 or newer.
- A JDK for BootUI's build and for the applications. JHipster's enforcer accepts JDK 21 to 25 only; the harness skips it,
  as the first run did. Quarkus applications run in dev mode on the JDK in `JAVA_HOME`.
- About 6 GB of disk under `validation/.work/` (clones, one Maven repository, logs, evidence), which Git ignores.

## Layout

| Path | What it holds |
| --- | --- |
| `protocol.json` | The registered parameters: registration tag, seed, sample size, applications, agent runs, kinds unlisted by design, reviewers, holdout exposure, gates |
| `RUBRIC.md` | How reviewers judge a fact, an honesty row, and a hidden row |
| `REVIEWER-PROMPT.md` | The prompt each registered reviewer receives once |
| `apps/<app>/pin.env` | Repository, commit, stack, role, and default port |
| `apps/<app>/align*.patch` | What the application needs to build on BootUI's Spring Boot or Quarkus line, applied first |
| `apps/<app>/bootui.patch` | BootUI added exactly as [the setup guide](../docs/SETUP.md) says; `@BOOTUI_VERSION@` is filled in |
| `apps/<app>/change.patch` | The code change of the agent-attached runs (Super Heroes and PetClinic) |
| `apps/<app>/app.sh` | How the application is built, started (with its containers), and stopped |
| `traffic/<app>.mjs` | The application's traffic, a fixed number of iterations in a fixed order, plain HTTP |
| `stubs/llm-stub.mjs` | A deterministic OpenAI- and Ollama-compatible chat model for the AI holdout |
| `bin/` | `build-v2.sh`, `prepare-app.sh`, `run-app.sh`, `rerun.sh`, `collect.mjs`, `ttfo.sh`, and `lib.sh` |
| `scoring/` | `worksheet.mjs`, `score.mjs`, their tests, and the first run as a fixture |
| `recall/known-misses.json` | Known misses and counterexamples per application, registered before the rerun |

## Ports

| Application | Port | Also |
| --- | --- | --- |
| `petclinic` | 18181 | |
| `jhipster` | 18182 | |
| `super-heroes` | 18183 | PostgreSQL through Quarkus Dev Services |
| `webflux-gateway` | 18184 | 18194 must stay free: it stands for a service that does not run |
| `kafka` | 18185, 18186, 18187 | Kafka in a container on 19092 |
| `bookstore` | 18188 | PostgreSQL on 15432 and RabbitMQ on 15672, in containers |
| `timeless` | 18189 | LocalStack on 14566, the chat stub on 18199, PostgreSQL through Dev Services |

`PORT` overrides an application's port, and each `app.sh` names the variables for the others. Port 8080 is refused
everywhere. Containers the harness starts are named `bootui-validation-*` and are removed by `run-app.sh <app> stop`.

## Run it

The maintainer registers the protocol once, right before the rerun and after any addition to the known misses, with an
annotated tag on the merged commit, which is never moved (a `v…` name would start `release.yml`):
`git tag -a m4-20-protocol-1 -m "M4-20 validation protocol" <commit> && git push origin m4-20-protocol-1`.
The tag registers everything under `validation/` except `.work/` and `scoring/fixtures/`. A measured run refuses a
checkout with any change, a checkout that is not the recorded build, and `VALIDATION_APP_ARGS`.

Every command runs from the repository root. `BOOTUI_VALIDATION_M2` (default `validation/.work/m2`) is the one Maven
repository for BootUI and every application; never point it at `~/.m2`, which may hold the released 1.x artifact of the
same version.

```bash
# 1. Build the checked-out v2 branch into the harness repository and record its engine jar's SHA-256.
validation/bin/build-v2.sh

# 2. One measured run per application, without the agent: build, start, prove the build, traffic, collect, stop.
for app in petclinic jhipster super-heroes webflux-gateway kafka bookstore timeless; do
  validation/bin/rerun.sh "$app"
done

# 3. The four agent-attached runs, then the no-change comparisons, as the first run did.
validation/bin/rerun.sh jhipster --agent
validation/bin/rerun.sh bookstore --agent
validation/bin/rerun.sh super-heroes --agent --change
validation/bin/rerun.sh petclinic --agent --change
for app in petclinic jhipster super-heroes webflux-gateway kafka bookstore timeless; do
  validation/bin/rerun.sh "$app" --compare
done

# 4. Time to first observation, the same way on every stack (appends to validation/.work/ttfo.jsonl). Each application
#    is measured once on the rerun's commit; measuring it again needs --reason "<why>".
for app in petclinic jhipster super-heroes webflux-gateway kafka bookstore timeless; do
  validation/bin/ttfo.sh "$app"
done

# 5. The reviewers' worksheet: facts, honesty rows, the seeded hidden-row sample, and a per-kind inventory. It refuses
#    a missing, duplicated, or unregistered run (comparison runs are skipped), and records the registered files' hashes.
node validation/scoring/worksheet.mjs --evidence validation/.work/evidence --out validation/.work/scoring
```

Each run proves it used the recorded build: `GET /bootui/api/runtime-insights` must answer (1.x has no such endpoint),
and the `bootui-engine` jar the running application loads (packed in its Spring Boot jar, or resolved from the harness
repository in Quarkus dev mode) must have the SHA-256 that `build-v2.sh` recorded. `rerun.sh` stops on any failure
and leaves nothing running. A measured run is never replaced silently: running it again needs `--reason`, and keeps
the previous evidence as `<label>.attempt-N`, which the worksheet reports. `--iterations N` makes a smoke run, whose
evidence goes to `validation/.work/smoke/` and never into a rerun.

Evidence for each run lands in `validation/.work/evidence/<app>[+agent]/`: `run.json`, `proof.env`, the traffic
summaries (duration, request count, statuses per route), and per service `runtime-insights.json` (every row, with
`listed` and `unlistedReason`), `insights/<id>.json` (each row's evidence), `agent-default.json` and `agent-all.json`
(the agent's default list and `query=all`), `comparison.json`, `agent-comparison.json`, `journal-status.json`,
`panels.json`, and `java-agent.json`.

## Review and score

1. Give each registered reviewer (`protocol.json`, `reviewers`) the prompt in `REVIEWER-PROMPT.md` once, in a fresh
   session on its registered model, with `validation/.work/scoring/judgments-template.csv`, `RUBRIC.md`, the evidence,
   and the applications' sources under `validation/.work/apps/`. Reviewers work independently and fill in the
   `judgment` and `note` columns only; save their files as `r1.csv` and `r2.csv`.
2. List the rows the maintainer must adjudicate, every disagreement, without any score:

   ```bash
   node validation/scoring/score.mjs --worksheet validation/.work/scoring/worksheet.json \
     --reviewer r1=validation/.work/scoring/r1.csv --reviewer r2=validation/.work/scoring/r2.csv \
     --out validation/.work/scoring/out --to-adjudicate
   ```

3. The maintainer fills `to-adjudicate.csv` in as `adjudication.csv` (`id,judgment,reason`), and writes `recall.csv`
   (`id,outcome,rows,note`) for every item of `recall/known-misses.json`. Two reviewers who agree are never overruled.
4. The final score refuses to run while anything is missing, while the registered files differ from the
   `m4-20-protocol-1` tag, while the worksheet differs from the one the evidence gives, or without the investigations
   and the time to first observation (`--partial` scores without them, marked as partial):

   ```bash
   node validation/scoring/score.mjs --worksheet validation/.work/scoring/worksheet.json \
     --evidence validation/.work/evidence \
     --reviewer r1=validation/.work/scoring/r1.csv --reviewer r2=validation/.work/scoring/r2.csv \
     --adjudication validation/.work/scoring/adjudication.csv \
     --recall-judgments validation/.work/scoring/recall.csv \
     --investigations validation/.work/scoring/investigations.csv --ttfo validation/.work/ttfo.jsonl \
     --out validation/.work/scoring/out
   ```

   `recall.csv` names, in `rows`, the worksheet ids (or, for a hidden row outside the sample, the observation ids) that
   support each outcome.

   `score.md` holds the tables the report needs: scores per group, application, and kind with each gate's outcome,
   honesty, the hidden sample, misleading rows with their adjudication, recall, the agent investigations, and the time
   to first observation. `investigations.csv` has one row per question and arm: `question,arm,result,calls,help_calls,bootui_commit`,
   with `arm` `2.0` or `1.x`, `result` `correct`, `partial`, or `wrong`, and `bootui_commit` the rerun's commit.

## Check the harness

```bash
node --test validation/scoring/            # the worksheet and scorer, including the first run rescored
bash -n validation/bin/*.sh validation/apps/*/app.sh
```

To smoke-test one application without a measured run, pass `--iterations 2` to `rerun.sh`; its evidence is not part of
a rerun.
