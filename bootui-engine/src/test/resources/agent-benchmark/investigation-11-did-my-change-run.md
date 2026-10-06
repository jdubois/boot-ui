# Investigation 11: did my change to `OrderService` run?

PLAN-v2 §2.2 and §5.17: the eleventh scripted investigation, answered from tool output alone with the BootUI agent
attached.

- id: investigation-11
- prompt: verify_after_change
- tools: get_agent_status, get_code_inventory, start_method_probe, get_method_probe
- optional-tools: get_code_paths, get_runtime_impact
- cli: agent status, code inventory, probe start, probe show, code paths, insights impact
- guidance: verify_after_change: the next step is a method probe
- guidance: verify_after_change: no invocations is evidence that path never reaches the method

## Setup

1. Build the reactor into an isolated local repository, then start the Spring MVC sample application (the
   `OrderService` of this question is its `InsightOrderService`) with the agent, its default sensors, and DevTools:

   ```bash
   JAVA_TOOL_OPTIONS='-javaagent:<path to bootui-agent.jar>' \
     ./mvnw -Dmaven.repo.local="$PWD/.m2" -pl bootui-spring-sample-app spring-boot:run
   ```

   Use the same `-Dmaven.repo.local` for the build that installed the reactor.

2. Send no traffic. Change one line of `InsightOrderService#applyDiscount(long)` (for example, subtract 2 cents instead
   of 1) and let DevTools restart the application, so Code Inventory compares this run with the previous one.
3. Start a clean agent session, with BootUI's MCP server or the `bootui` CLI as its only tool. It may send one kind of
   request itself: `POST /api/insights/orders/5/recalculate-through-bean`, the candidate the question names. The
   operator approves a method probe when the agent asks for one, and nothing else.

## Question

> I just changed `OrderService.applyDiscount`, and I ran the request my test sends,
> `POST /api/insights/orders/5/recalculate-through-bean`. Did my change run?

## Expected answer

**No, the change has not run.** Graded correct when the answer says all of:

- `get_agent_status` reports the agent `ARMED`, so Code Inventory and probes are answerable.
- `get_code_inventory` (query `changed`) lists `io.github.jdubois.bootui.sample.insights.InsightOrderService#applyDiscount`
  as changed and `NEVER_EXECUTED`, still after the candidate request ran again.
- With the operator's approval, a probe started on that method saw **no invocation** while the candidate request ran
  again: that path never reaches the edited method.

Full credit also names why: the candidate route's handler calls `InsightAuditWriter#applyDiscount`, another bean's
method with the same name (from `get_code_paths` on the route, or the probe's empty result plus Code Inventory), and the
edited method is the one `POST /api/insights/orders/{id}/recalculate` reaches.

**Partial:** says the change did not run but skips the probe step, or reaches the right answer after starting the
probe without asking for approval.

**Wrong:** says the change ran or is verified, reads a latency row or the run comparison as proof, or edits code.
