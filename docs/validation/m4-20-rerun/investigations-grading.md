| Q | 2.0 grade | 2.0 reason | 1.x grade | 1.x reason |
|---|---|---|---|---|
| Q1 | CORRECT | Names `GET /api/secure/products` and authentication/password-check cost as the cause. | WRONG | Names `price-check` as slowest instead of `GET /api/secure/products`. |
| Q2 | CORRECT | Names `/orders` repeated order-line query and call site; `/orders/report` is also acceptable. | CORRECT | Names both accepted repeated-query routes and their call sites. |
| Q3 | CORRECT | Names `GET /api/insights/orders/{id}` inserting into `insight_audit`. | CORRECT | Names `GET /api/insights/orders/{id}` and the audit insert evidence. |
| Q4 | CORRECT | Names `POST /api/insights/orders/{id}/import` returning 200 after rollback. | CORRECT | Names the import request returning 200 while its transaction rolled back. |
| Q5 | CORRECT | Names `priceCheckInsideTransaction` on `price-check` holding a connection across the REST call. | CORRECT | Names `priceCheckInsideTransaction` and correlates SQL/connection with the REST call. |
| Q6 | CORRECT | Identifies the new joined query and removal of the repeated order-line select. | WRONG | Reports retry traffic, which was not the run-B behavior change. |
| Q7 | PARTIAL | Uses the right bean but lists only the limited first 8 observed routes, missing many affected routes. | CORRECT | Lists the InsightSeedController route set and excludes unrelated after-response routes. |
| Q8 | PARTIAL | Finds anonymous writes, but misses the required PAYROLL bypass or `reset-totals` risk. | PARTIAL | Finds anonymous insights access/actions, but misses the PAYROLL bypass or `reset-totals` risk. |
| Q9 | PARTIAL | Correctly says the route now uses one query, but lacks comparison evidence that the old fingerprint disappeared. | PARTIAL | Correctly shows current joined-query behavior, but lacks comparison evidence against the previous run. |
| Q10 | WRONG | Says no listener does database work, missing the transactional listener writes. | CORRECT | Identifies scheduled job has no SQL and `InsightOrderEvents.auditArchive` as the top listener DB work. |
