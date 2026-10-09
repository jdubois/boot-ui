# Releasing 2.0

This is the maintainer runbook for the 2.0.0 release path ([v2 plan](PLAN-v2.md) §4.3, M4-23): how merging `v2` into
`main` is kept from publishing anything early, how that is rehearsed, how the `1.x` maintenance branch is cut and
released from (D40), and the order of the release day. The Release workflow itself, its signed tags, and its recovery
paths are described in [CONTRIBUTING](https://github.com/jdubois/boot-ui/blob/main/CONTRIBUTING.md#publishing).

Nothing is published from `v2` before 2.0.0 (D5). `v2` still carries the 1.x project version, so after the merge the
project version on `main` cannot tell 2.0 contents from 1.x contents. The release line can.

## The release line

Every branch declares its release line, the major version its contents belong to, in `.github/release-line`: one
number, with `#` comments. `v2` declares `2`; the prepared `main` declares `1`, and `1.x` will inherit `1` when it is cut.
The line must
be the project version's major, or that major plus one while a branch prepares the next major, and a missing or
malformed file fails every check that reads it. A branch containing `bootui-agent`, which exists only from 2.0, must
declare at least `2`, so a `main`-to-`v2` sync that resolves a conflict on this file to `1` fails the integrity guard,
the gate, and the Release workflow instead of letting the merged `main` publish 2.0 as the 1.x line. The first commit on
a branch preparing a later major, such as `v3`, declares that major.

## How each channel is guarded

| Channel | What decides | Before the merge | After the merge, before 2.0.0 | Once 2.0.0 is on Maven Central |
| --- | --- | --- | --- | --- |
| Documentation site (`pages.yml`) | `release-line-gate.sh` on every push and manual run | `main` (line 1) deploys | `main` (line 2) builds the site but skips the upload and the deploy | `main` deploys again; `1.x` never deploys |
| Docker Hub sample images (`docker-publish.yml`) | The same gate, in the workflow's first job | `main` publishes daily | Every job but prune is skipped | `main` publishes 2.x images; `1.x` publishes none |
| Maven Central (`release.yml`) | `release-version-policy.sh` with the release line, before any file changes, and again on the tagged contents before publication; only from `main` or the version's `N.x` branch | `main` releases 1.x only | `main` releases 2.0.0 only, and only when dispatched; `1.x` releases 1.x patches only | `main` releases 2.x; `1.x` releases 1.x patches, without redeploying the site |
| JBang alias (`jbang-catalog.json`) | The Release workflow rewrites it | 1.x | Unchanged: the catalog is identical on `main` and `v2` | 2.0.0 |
| Installers (`install.sh`, `install.ps1`) and the CLI's update check | Maven Central's `maven-metadata.xml` | 1.x | 1.x | 2.x |

`release-line-gate.sh` reads the branch's release line, the tags on origin, and Maven Central. A branch may publish
only when one tag of its line has its `bootui-engine` and Spring starter jars on Maven Central (2.0 no longer
publishes the parent POM or `bootui-core`), and no tag of a newer major does. Requiring any tag, not the newest, keeps one failed or still-propagating patch from blocking every later deploy, and
counting a newer major only once it is on Maven Central keeps a stray or failed tag from retiring the released line. An
unreadable answer, from origin or from Maven Central, fails the run instead of deciding.

The Release workflow dispatches `pages.yml` at the release tag only after every artifact is available and only for the
newest major, so the gate passes there, and it fails unless that run's deploy job actually deployed. Pull requests
still build the site, without the gate.

The Release workflow also prepares a release only from `main` or from the version's maintenance branch (`1.x` for a
1.x version), and publishes a tag only when its commit is on one of them, so a signed tag pushed on `v2` cannot publish
before the merge.

`check-release-integrity.sh` pins all of this: the release-line arguments, the tagged-contents and release-branch
checks, and the deploy confirmation in `release.yml`; the gate and the gated upload and deploy in `pages.yml`; the gate
job ahead of every publishing job in `docker-publish.yml`, whose image jobs keep exactly their repository condition so
no status function such as `always()` can run them after a skipped gate; and a valid `.github/release-line`. Workflows
may not set the gate's test seams.

### What workflow files cannot guard

A manual run executes the workflow file of the ref it is dispatched from, so refs created before the gate, such as the
`v1.*` tags or an older `v2` feature branch, carry ungated workflows forever. The GitHub environments are the only
control over them:

- `maven-central` accepts `main` and `v*` tags, with required reviewers. It refuses manual runs from `v2` and its
  feature branches. Add `1.x` when the branch is cut.
- `github-pages` accepts `main` and `v*` tags. Right after the 2.0 site deploys, narrow the tag rule to `v2.*`, so an
  old tag's ungated `pages.yml` cannot replace it. Repeat at 3.0.
- `docker-hub` accepts only `main` (done 2026-10-04). Without that policy, any ref whose `docker-publish.yml` predates
  the gate, such as an older `v2` feature branch or a `v1.*` tag after 2.0.0, could push `latest` through a manual run.
  The daily schedule runs only on `main`, so nothing legitimate lost access. A manual run from any other ref now fails
  at its first `docker-hub` job, including a `prune_dry_run` preview: run retention previews from `main`. Keep this
  policy at 3.0.

Re-running a workflow run replays the workflow file of that run, and GitHub allows it for 30 days. Never re-run a
Pages or Docker run that predates the gate on `main`; the preparation below has landed, but old runs remain a concern
until they age out.

## The rehearsal

`.github/scripts/rehearse_v2_merge.py` rehearses the merge on a candidate that is never published. It builds the merge
commit of `main` and `v2` with `git merge-tree` and `git commit-tree`, so no branch moves, and checks:

- every workflow that a push to `main` or a schedule runs, and that can publish, is behind the gate, and the Release
  workflow has no branch trigger;
- on the candidate, the integrity guard passes, the gate decides not to publish the site or the images, against the
  real tags on origin and Maven Central, and the Release workflow's policy refuses the next 1.x patch and accepts only
  2.0.0;
- the 1.x line before the merge still publishes, against the real tags and Maven Central;
- simulated futures, with synthetic tags and a local stand-in for Maven Central: a v2.0.0 tag without its artifacts
  publishes nothing, its artifacts publish the 2.x site, and the `1.x` branch then publishes no site, releases its next
  patch without redeploying the site, and can never release 2.0.0;
- whether `main` is ready to become `1.x`, with its own integrity guard and release tests passing, whether the release
  sign-off has no `TODO` left, and whether the GitHub environments are set as above (read only, with `gh`).

```bash
# Any day: a local, read-only rehearsal against origin/main and the current v2 commit
python3 .github/scripts/rehearse_v2_merge.py --v2 origin/v2

# Release day: also run the gated workflows on GitHub, and fail on any pending prerequisite
python3 .github/scripts/rehearse_v2_merge.py --v2 origin/v2 --live --release-day
```

`--live` pushes the candidate to a temporary `rehearsal/v2-merge-*` branch, dispatches `pages.yml` and
`docker-publish.yml` from it, requires every publishing job to be skipped, and deletes the branch. No push trigger
runs on that branch, and the `github-pages` and `maven-central` environments refuse it, so a broken gate fails rather
than publishes; `docker-hub` refuses it too, so the Docker image jobs could not push even past a broken gate. As a
second line, the rehearsal cancels a run as soon as any job past its gate is queued.

A merge conflict fails the rehearsal: merge `main` into `v2` first. Prerequisites that are not done yet are reported as
`PENDING`; `--release-day` turns them into failures.

## The `1.x` maintenance branch

`1.x` is cut from `main`'s last 1.x commit right before `v2` merges. Until then `main` is the 1.x line and keeps
receiving fixes; do not cut the branch early.

### Before the cut: verify the prepared `main`

The per-major release machinery and release-line gates **have landed on `main`**. The following is the preparation
record, not an outstanding instruction to backport them again. Before release day, verify that subsequent changes have
kept these invariants; the read-only rehearsal checks them:

1. `release-version-policy.sh`, `release-line-gate.sh`, `pages.yml`, and `docker-publish.yml` are byte-identical on
   `main` and `v2`. Preserve that equality through reviewed changes whenever a shared gate changes. The gate's
   sentinel is `bootui-engine`, not the retired 2.0 `bootui-core` coordinate, so the prepared 1.x line recognizes 2.0
   on Maven Central.
2. `main`'s `release.yml` has the release-line arguments, the tagged-contents and release-branch checks, the
   deploy confirmation, and the newest-major documentation decision, keeping `main`'s own publication reactor and availability list, which have no agent
   modules; its integrity guard and release tests enforce that 1.x-specific machinery.
3. `main`'s `.github/release-line` is `1`. When syncing it into `v2`, keep `2` on `v2`
   (the guard rejects `1` on a branch containing `bootui-agent`).
4. `1.x` is listed in the push and pull-request branches of `build.yml` and `jdk-compatibility.yml`, so it gets the green
   build a release requires.

This preparation and a read-only rehearsal are not release authorization. Cutting `1.x`, merging the release,
dispatching live rehearsals or Release, signing, and publishing still require the maintainer's release-day decision;
environment/ruleset changes require a repository administrator. Pending sign-off or environment permissions remain
blockers, not waived checks.

### Settings, by a repository administrator

- A ruleset for `1.x` like `main`'s: no force push or deletion, pull requests required, and `github-actions[bot]`
  allowed to push the release commit and its `v1.*` tag.
- The `maven-central` environment accepts the `1.x` branch.
- The `docker-hub` environment already accepts only `main` (done 2026-10-04); nothing to change for `1.x`, which
  publishes no images.

### Cutting the branch

On release day, after the last 1.x release from `main` and the final rehearsal, with nothing else merging into `main`:

```bash
git fetch origin
git show origin/main:.github/release-line   # must declare 1
git push origin origin/main:refs/heads/1.x
```

The branch points at an existing commit: no merge, no rebase, no new commit.

### Releasing a 1.x patch

1. Fix on `1.x` through a pull request into `1.x`, and port the fix to `main` when it applies to 2.x (`main`-to-`v2`
   syncs carry it into any later major's branch).
2. Cut `CHANGELOG.md` on `1.x` to `## [1.N.P] - YYYY-MM-DD` as its own commit, and wait for a green build.
3. Run **Release** from `1.x` with the next 1.x version. The policy accepts only the next patch or minor after the
   newest 1.x tag; the release line refuses 2.x versions.
4. After 2.0.0, the workflow skips the documentation redeploy, with a notice: the site follows the newest major. Between
   the merge and 2.0.0, 1.x is still the newest released major, so a 1.x patch still redeploys the 1.x site from its
   tag. A manual `pages.yml` run from `1.x` is refused by the `github-pages` environment, which accepts only `main`
   and tags.

## Release day

1. **Sign-off.** The [release sign-off](V2-VALIDATION-REPORT.md#release-sign-off) has no `TODO` left, and
   [Known limitations](KNOWN-LIMITATIONS.md) matches the shipped scope.
2. **Last 1.x release.** Release any unreleased 1.x change from `main` as usual, and verify the prepared `main` above.
3. **Sync.** Merge `main` into `v2` with a merge commit, never a rebase; `.github/release-line` stays `2`.
4. **Rehearse.** `python3 .github/scripts/rehearse_v2_merge.py --v2 origin/v2 --live --release-day` reports no
   failure and nothing pending.
5. **Cut `1.x`** from `origin/main`, as above.
6. **Merge `v2` into `main`** with a merge commit. Check that the Pages run of the merge push skipped its upload and
   deploy with "release line 2 has no release tag yet", and that the next Docker run skipped every job but prune.
7. **Changelog.** Cut `CHANGELOG.md`'s `[Unreleased]` to `## [2.0.0] - YYYY-MM-DD` on `main`, as its own commit, and
   wait for a green build.
8. **Release.** With maintainer approval, run **Release** from `main` with version `2.0.0`. Merge nothing into `main` until the run is green:
   while Maven Central propagates, a push could find the gate's two artifacts before the others. The run refuses an
   imported key other than the pinned
   `RELEASE_KEY_FINGERPRINT` (7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9), and checks every signature against it twice:
   after the verification build, before the tag (unless `skip_build`), and on the Central bundle, before the upload.
   Normal preparation also stages the exact candidate bundle and smoke-tests its consumers before committing/tagging.
   Tag-entry and `skip_build` runs do that from the immutable tagged checkout before uploading instead; a successful
   pre-tag smoke is not repeated after the tag. Publication, availability checks, published-consumer smoke and the site
   deployment all use the commit peeled from the verified signed tag.
9. **After the release.** Confirm that the site shows 2.0 (deployed from the v2.0.0 tag), that
   `jbang bootui@jdubois/boot-ui` and the installers resolve 2.0.0, and that the next daily Docker run publishes 2.x
   images. Narrow the `github-pages` tag rule to `v2.*`. After the first 1.x patch that follows, confirm that
   Maven Central's `maven-metadata.xml` for `bootui-cli` still lists a 2.x `<release>`, which the installers and the
   CLI's update check read.
