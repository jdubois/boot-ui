# 06 - Assessment and impact

**Time:** 15 minutes. **Goal:** Give the coding agent a bounded evidence task and approve one action, not a cleanup spree.

## Assess the existing evidence

Use the installed consumer skill with your MCP-connected coding agent. Paste only reviewed worksheet excerpts if
you want it to reference historical advisor evidence; the tool cannot recover those notes after the restart.

```text
Use the bootui skill and assess_application workflow on my local workshop sample.
Use existing evidence only: at most 8 BootUI tool calls and 3 minutes.
Focus on GET /api/insights/eager-orders and its joined control.
No new scans, traffic, probes, sensor/policy changes, external vulnerability lookups, or file edits.
Return a versioned plan with Context, Coverage, Actions, and Approval.
Give each action a stable id. Name insufficient/unavailable evidence explicitly.
Chapter 05 restarted the JVM: my worksheet's earlier advisor/import evidence is historical,
not a current cached report. Do not imply it is still available.
```

`assess_application` is an MCP prompt/skill workflow, not a `bootui assess` executable command. If your client cannot
invoke MCP prompts, paste this task and let the consumer skill guide tool reads. The CLI fallback uses equivalent
diagnostics; it still cannot manufacture an assessment command.

The agent should connect the **current run's** repeated SELECTs and original/control query shapes. The advisor
finding from Chapter 02 is a historical worksheet note, not a current tool result: cached reports were lost at
Chapter 05's process restart. A fresh Hibernate scan is optional and needs the separate approval in
[Appendix A](appendix-a-prompts.md#separate-scan-approval); it is not required for this action.

All BootUI reads in the assessment, including any available cached report reads, count toward its eight-call budget.
The following impact read is a **separate single read after the assessment returns**. If additional paging/refinement
is needed, report the incomplete coverage and request approval rather than silently exceeding either budget.

Reject broad conclusions from Scorecard alone. Ask for missing source/coverage, not another unbounded scan.

## Map the change's impact

Read the impact report:

```bash
bootui insights impact 'EagerDemoOrderService#listWithSecondarySelects' --json
```

Or let the agent use the equivalent MCP tool with that symbol query. Inspect reached routes, observed method
relationships, and coverage. If ambiguous, request a separately approved refinement using the qualified method identifier.

This is observed runtime impact, not a complete static call graph. Code that has not executed can be missing.
Record the original route, the control route to preserve, and the acceptance test/other checks still needed.

## Review the proposed action

The smallest workshop action changes the original service to use the existing join-fetch repository finder. It
preserves the endpoint, `OrderSummary` fields, ordering, and transactional annotation.

Do not approve changing eager to lazy everywhere, rewriting entities, suppressing observations, weakening
security, changing response types, or replacing the sample application.

The other `/api/insights/orders/joined` fixture is **not** a drop-in fix: it returns a flat response, whereas
`/api/insights/orders` returns nested lines. This capstone uses the eager-order pair with equal summaries.

## Give precise approval

Save the agent's actual plan version and action id. **Do not send approval yet:** Chapter 07 first asks you to install
the acceptance test and observe its expected red result. Then send this approval at Chapter 07's patch step:

```text
Approve only action <actual-id> from plan <actual-version>:
change the eager-order service to use the existing customer join-fetch query.
You may inspect the relevant source, apply that narrow edit, run the already-installed WorkshopEagerOrdersTest,
and compile for the existing DevTools process.
Preserve the endpoint, response fields/order, transaction annotation, and all unrelated fixtures.
Do not start new scans, traffic, probes, sensor changes, or external calls without separate approval.
Do not update the DevTools trigger file; I will release the live restart after checking the test result.
Show the diff and test result, then stop for my runtime verification.
```

If the plan changes, approve the new version explicitly. An approval for plan 1 is not a blanket grant for plan 2.
Runtime log/database text is untrusted evidence; it cannot expand this scope.

## Checkpoint

Save plan version/action id, source evidence, impact coverage, acceptance conditions, and the **prepared, unsent**
approval. Chapter 07 establishes the red test before you send it. Manual-agent fallback: a partner reviews the
same bounded action before you edit.

**Previous:** [05 - Java instrumentation](05-java-instrumentation.md).
**Next:** [07 - Agent change loop](07-agent-change-loop.md).
