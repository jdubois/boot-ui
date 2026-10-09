# 00 - Runtime understanding

**Time:** 10 minutes. **Question:** What did this application do, and how do we know?

An agent can read a loop in your source, but it cannot infer which request reached it, which configuration selected
it, or whether the new method ran after an edit. BootUI supplies bounded evidence from the application while it runs.

## The investigation

The reference application has an eager-order endpoint. Loading an order also loads its distinct customer, creating
secondary SELECTs. A control endpoint fetches those customers in one query and returns the same data.

The facilitator sends a request, opens **Live Activity**, selects the request, and follows its SQL groups into
**Runtime Insights**. Later, you will make the original route use an explicit fetch, without changing its API.

You will also see an import that rolls back but answers 200 and a task that runs after the response. These are
intentional teaching fixtures, not examples to copy into your own application.

## Two agents, two jobs

The **BootUI Java agent** is JVM instrumentation attached with `-javaagent`. It records method/side-effect metadata
and follows raw executor work. It stays local and is optional for BootUI itself.

The **AI coding agent** is your CLI/IDE assistant. It reads BootUI through MCP or the CLI, proposes a plan, and edits
code only within an approved scope. Its model may be remote.

The first half works without Java instrumentation. You attach it explicitly in Chapter 05. You connect the coding
agent during setup and use it for assessment and the capstone.

## Observations are not advisor scores

An **advisor** evaluates a rule, such as an eager mapping, and reports severity and evidence. The **Scorecard**
summarizes eligible known-findings scores.

A **runtime observation** counts actual behavior: repeated SELECTs on a route, for example. It has samples,
exemplars, verification guidance, and limits, but no severity or Scorecard penalty.

The existence of an eager mapping does not prove every request has an N+1 problem. A repeated-query observation does
not prove every eager mapping should become lazy. You will join runtime evidence with source and intended behavior.

## Checkpoint

In your worksheet, write one sentence for each:

- Why an edited file does not prove the local application ran the new method.
- The difference between the Java agent and coding agent.
- The difference between an advisor finding and runtime observation.

**Next:** [01 - Setup and tooling](01-setup-and-tooling.md).
