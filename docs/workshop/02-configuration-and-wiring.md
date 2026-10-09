# 02 - Configuration and wiring

**Time:** 15 minutes. **Goal:** Explain which route, bean, and configuration actually run.

## Follow the order route

In **Mappings**, search `eager-orders`. Locate both:

- `GET /api/insights/eager-orders`
- `GET /api/insights/eager-orders/joined`

Open their handler details. In your editor follow the controller to `EagerDemoOrderService`, then
`EagerDemoOrderRepository`, under `bootui-spring-sample-app/src/main/java/io/github/jdubois/bootui/sample/insights/`.

Read these two queries without changing anything:

```java
@Query("select o from EagerDemoOrder o order by o.id")
List<EagerDemoOrder> findWithSecondarySelects();

@Query("select o from EagerDemoOrder o join fetch o.customer order by o.id")
List<EagerDemoOrder> findWithCustomerJoin();
```

Find the eager `customer` relationship in the entity. Predict which query can issue secondary SELECTs and why the
join-fetch route is a useful control. Both service methods produce the same `OrderSummary` records.

In **Beans**, search `EagerDemoOrderService`. Inspect its dependencies and proxy information. Use **Transactions**
to see transaction metadata and the runtime capture state. A `@Transactional` annotation is not a captured
transaction; self-invocation and proxy boundaries matter.

## Explain the effective configuration

In **Configuration**, search `spring.datasource.url`, `spring.cache.type`, and `spring.cache.caffeine.spec`.
Record the effective value and source, not just the string in a file.

The dev fixture uses H2 and Caffeine:

```properties
spring.cache.type=caffeine
spring.cache.caffeine.spec=maximumSize=500,expireAfterWrite=5m,recordStats
```

Find the active profiles and a property whose command-line value overrides the file, such as your alternate
`server.port`. Use **Profile Diff** to distinguish different property sources where available.
Keep values masked and do not use a secret as your override demonstration.

Open **Database Connection Pools** and **Cache**. Identify the live datasource and cache provider. Do not execute SQL or clear a
cache just to make the panel look populated. Caffeine can report real hit/miss statistics; absent access capture
is different from zero misses.

## Run one advisor, not every scan

Explicitly run **Hibernate**'s advisor scan. Inspect `HIB-FETCH-001` and `HIB-QUERY-005` if reported. Record the rule id,
severity, and affected sample. Follow the reference explanation before accepting a fix.

An annotation/query advisor can report a risk before you send traffic. You have not yet demonstrated the route's
runtime query count. A cached report read is not a fresh scan, and **Scorecard** is not runtime coverage.

Do not “fix all” the sample. It intentionally contains counterexamples across many advisors.

## Checkpoint

Record the target handler/service/repository, eager relationship, effective datasource source, cache provider,
and one advisor finding. State your prediction and what runtime evidence would confirm it.

**Optional:** Inspect **Architecture**, **REST API**, or **Spring** advisor descriptions without starting an additional
scan. Identify a rule that would need intent/context before changing code.

**Previous:** [01 - Setup and tooling](01-setup-and-tooling.md).
**Next:** [03 - Runtime journal](03-runtime-journal.md).
