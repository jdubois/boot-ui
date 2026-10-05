# Recall evidence (operator gathering, no outcomes)

For each registered item: the worksheet rows (fact, hidden sample, honesty) and every row of the full report
(`query=all`) whose kind is one of the item's kinds or whose subject or sentence names one of its subjects, plus the
checks, coverage, and not-exercised lines when the item is about coverage. The maintainer marks the outcome.

## petclinic
### PC-1 (miss, run `petclinic`, first run: found)
The pet-type selector re-runs the pet-types query 5 to 6 times per form render, from PetTypeFormatter.parse (PetTypeFormatter.java:52-53, fragments/selectField.html:13-14).
Kinds: repeated-selects; subjects: GET /owners/{ownerId}/pets/new, POST /owners/{ownerId}/pets/new, GET /owners/{ownerId}/pets/{petId}/edit, POST /owners/{ownerId}/pets/{petId}/edit

Worksheet rows:
- `petclinic/fact/05eafb9a` [fact] lazy-sql-after-handler OBSERVED: `POST /owners/{ownerId}/pets/{petId}/edit` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` after its handler returned, 915 times in 183 of 367 requests, outside a transaction, while the view was rendered.
- `petclinic/fact/0ec63efe` [fact] lazy-sql-after-handler OBSERVED: `GET /owners/{ownerId}/pets/{petId}/edit` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` after its handler returned, 915 times in 183 of 183 requests, outside a transaction, while the view was rendered.
- `petclinic/fact/12b74f6b` [fact] repeated-selects OBSERVED: `POST /owners/{ownerId}/pets/{petId}/edit` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 367 requests, up to 7 times in one.
- `petclinic/fact/173c24f4` [fact] lazy-sql-after-handler OBSERVED: `POST /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` after its handler returned, 915 times in 183 of 366 requests, outside a transaction, while the view was rendered.
- `petclinic/fact/41f7e90d` [fact] repeated-selects OBSERVED: `POST /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 366 requests, up to 7 times in one.
- `petclinic/fact/7d8b8716` [fact] lazy-sql-after-handler OBSERVED: `GET /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` after its handler returned, 1098 times in 183 of 183 requests, outside a transaction, while the view was rendered.
- `petclinic/fact/d1ce3167` [fact] repeated-selects OBSERVED: `GET /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 183 requests, up to 7 times in one.
- `petclinic/fact/eb5bb5fb` [fact] repeated-selects OBSERVED: `GET /owners/{ownerId}/pets/{petId}/edit` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 183 requests, up to 6 times in one.
- `petclinic/hidden/868f3d4d` [hidden] gc-inflated-latency OBSERVED: A stop-the-world pause completed during 4 of `POST /owners/{ownerId}/pets/new`'s 37 slowest requests (11 %), against 0 % of its other 329 requests; those pauses total 7.0 ms.
- `petclinic/hidden/cd939c68` [hidden] route-time-breakdown OBSERVED: `GET /owners/{ownerId}/pets/{petId}/edit`: warm median 3.8 ms over 183 requests; Response write 60 %, Handler, other work 31 %, Other filters 8 %. Median CPU 3.7 ms and 2.0 MB allocated per request.
Other rows in the full report (not in the worksheet):
- petclinic `route-time-breakdown:1c02750660` listed=False route-time-breakdown OBSERVED: `POST /owners/{ownerId}/pets/{petId}/edit`: warm median 3.4 ms over 367 requests; Handler, other work 49 %, Response write 34 %, Other filters 8 %. Median CPU 3.4 ms and 480 KB allocated per request. (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- petclinic `route-time-breakdown:82279a9845` listed=False route-time-breakdown OBSERVED: `POST /owners/{ownerId}/pets/new`: warm median 3.3 ms over 366 requests; Handler, other work 49 %, Response write 35 %, Other filters 8 %. Median CPU 3.2 ms and 380 KB allocated per request. (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- petclinic `route-time-breakdown:27b548538c` listed=False route-time-breakdown OBSERVED: `GET /owners/{ownerId}/pets/new`: warm median 3.6 ms over 183 requests; Response write 63 %, Handler, other work 27 %, Other filters 8 %. Median CPU 3.5 ms and 1.9 MB allocated per request. (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- petclinic `gc-inflated-latency:1c02750660` listed=False gc-inflated-latency OBSERVED: A stop-the-world pause completed during 3 of `POST /owners/{ownerId}/pets/{petId}/edit`'s 37 slowest requests (8 %), against 0 % of its other 330 requests; those pauses total 10 ms. (unlisted: Garbage collection and heap rows are reached from the Memory panel rather than listed by default.)
- petclinic `gc-inflated-latency:35a8dacc0c` listed=False gc-inflated-latency OBSERVED: A stop-the-world pause completed during 2 of `GET /owners/{ownerId}/pets/{petId}/edit`'s 19 slowest requests (11 %), against 0 % of its other 164 requests; those pauses total 4.0 ms. (unlisted: Garbage collection and heap rows are reached from the Memory panel rather than listed by default.)

### PC-2 (miss, run `petclinic`, first run: found)
The owner list renders one pagination link per page (owners/ownersList.html:34-37), so GET /owners has a heavy tail that grows with the owner table.
Kinds: route-time-breakdown; subjects: GET /owners

Worksheet rows:
- `petclinic/hidden/151407e4` [hidden] route-time-breakdown OBSERVED: `GET /owners`: warm median 4.2 ms over 549 requests; Response write 84 %, Handler, other work 12 %, Other filters 2 %. Median CPU 4.1 ms and 2.8 MB allocated per request.
Other rows in the full report (not in the worksheet):
- petclinic `gc-inflated-latency:5fb8278694` listed=False gc-inflated-latency OBSERVED: A stop-the-world pause completed during 12 of `GET /owners`'s 55 slowest requests (22 %), against 4 % of its other 494 requests; those pauses total 63 ms. (unlisted: Garbage collection and heap rows are reached from the Memory panel rather than listed by default.)

### PC-3 (miss, run `petclinic`, first run: hidden)
Tomcat logs an ERROR for every GET /oups that no request owns, 972 in the first run.
Kinds: framework-warnings-by-route; subjects: No request

Worksheet rows: none.
Checks, coverage, and limitations:
- petclinic check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- petclinic check framework-warnings-by-route EVALUATED eligible=5128 findings=0: 122 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- petclinic check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- petclinic limitation: The journal evicted 31251 older events, so requests before the oldest retained event are not projected.
- petclinic limitation: 122 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.

### PC-4 (miss, run `petclinic`, first run: hidden)
Every write route is anonymous, since the application has no security at all; a check that needs authorization data must say it saw none rather than report a clean result.
Kinds: anonymous-data-reach, anonymous-success-on-restricted-route; subjects: (check)

Worksheet rows:
- `petclinic/honesty/1b89b7cb` [honesty] anonymous-data-reach INSUFFICIENT: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- `petclinic/honesty/bad21e50` [honesty] anonymous-success-on-restricted-route INSUFFICIENT: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
Checks, coverage, and limitations:
- petclinic check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- petclinic check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- petclinic check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- petclinic limitation: The journal evicted 31251 older events, so requests before the oldest retained event are not projected.
- petclinic limitation: 122 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.

### PC-5 (miss, run `petclinic+agent`, first run: not run)
In the agent run, change.patch changes Owner.getPet(String), which nothing calls: changed-code-not-executed must report it.
Kinds: changed-code-not-executed; subjects: org.springframework.samples.petclinic.owner.Owner

Worksheet rows:
- `petclinic+agent/fact/fe60e045` [fact] changed-code-not-executed OBSERVED: Your change has not run yet: 1 changed method of `Owner` was not executed in this run.

### PC-6 (miss, run `petclinic+agent`, first run: not run)
In the agent run, change.patch changes Vet.addSpecialty, which nothing calls: changed-code-not-executed must report it.
Kinds: changed-code-not-executed; subjects: org.springframework.samples.petclinic.vet.Vet

Worksheet rows:
- `petclinic+agent/fact/e1a21005` [fact] changed-code-not-executed OBSERVED: Your change has not run yet: 1 changed method of `Vet` was not executed in this run.

### PC-C1 (counterexample, run `petclinic`)
GET /oups throws on purpose to show the error page (CrashController.java:31-34).
Kinds: -; subjects: GET /oups

Worksheet rows:
- `petclinic/fact/b1f1e46f` [fact] exception-hotspots OBSERVED: `GET /oups` recorded `RuntimeException` in 183 of 183 requests (183 occurrences).
- `petclinic/hidden/e44d6dea` [hidden] route-time-breakdown OBSERVED: `GET /oups`: warm median 0.5 ms over 183 requests; Handler, other work 67 %, Other filters 33 %. Median CPU 0.5 ms and 85 KB allocated per request.
Checks, coverage, and limitations:
- petclinic check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- petclinic check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- petclinic check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- petclinic limitation: The journal evicted 31251 older events, so requests before the oldest retained event are not projected.
- petclinic limitation: 122 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.

### PC-C2 (counterexample, run `petclinic`)
The repeated pet-type SELECTs come from a view formatter, not lazy loading: open-in-view and fetch joins do not apply (application.properties:11).
Kinds: -; subjects: GET /owners/{ownerId}/pets/new

Worksheet rows:
- `petclinic/fact/7d8b8716` [fact] lazy-sql-after-handler OBSERVED: `GET /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` after its handler returned, 1098 times in 183 of 183 requests, outside a transaction, while the view was rendered.
- `petclinic/fact/d1ce3167` [fact] repeated-selects OBSERVED: `GET /owners/{ownerId}/pets/new` ran `select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name` 5 or more times after another statement in 183 of 183 requests, up to 7 times in one.
Other rows in the full report (not in the worksheet):
- petclinic `route-time-breakdown:27b548538c` listed=False route-time-breakdown OBSERVED: `GET /owners/{ownerId}/pets/new`: warm median 3.6 ms over 183 requests; Response write 63 %, Handler, other work 27 %, Other filters 8 %. Median CPU 3.5 ms and 1.9 MB allocated per request. (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
Checks, coverage, and limitations:
- petclinic check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- petclinic check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- petclinic check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- petclinic limitation: The journal evicted 31251 older events, so requests before the oldest retained event are not projected.
- petclinic limitation: 122 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.

### PC-C3 (counterexample, run `petclinic`)
Vets are cached (VetRepository.java:44-46); their reads are not an N+1.
Kinds: -; subjects: GET /vets, GET /vets.html

Worksheet rows:
- `petclinic/hidden/f6dc433c` [hidden] route-time-breakdown OBSERVED: `GET /vets.html`: warm median 3.4 ms over 183 requests; Response write 82 %, Handler, other work 11 %, Other filters 7 %. Median CPU 3.3 ms and 3.2 MB allocated per request.
Other rows in the full report (not in the worksheet):
- petclinic `route-time-breakdown:665a7fb2e8` listed=False route-time-breakdown OBSERVED: `GET /vets`: warm median 0.8 ms over 183 requests; Handler, other work 47 %, Response write 27 %, Other filters 26 %. Median CPU 0.8 ms and 79 KB allocated per request. (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- petclinic `gc-inflated-latency:c96b43f4ab` listed=False gc-inflated-latency OBSERVED: A stop-the-world pause completed during 3 of `GET /vets.html`'s 19 slowest requests (16 %), against 0 % of its other 164 requests; those pauses total 13 ms. (unlisted: Garbage collection and heap rows are reached from the Memory panel rather than listed by default.)
Checks, coverage, and limitations:
- petclinic check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- petclinic check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- petclinic check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- petclinic limitation: The journal evicted 31251 older events, so requests before the oldest retained event are not projected.
- petclinic limitation: 122 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.

### PC-C4 (counterexample, run `petclinic+agent`)
In the agent run, change.patch changes Owner.addVisit, which every valid visit runs: changed-code-not-executed must not report it.
Kinds: -; subjects: org.springframework.samples.petclinic.owner.Owner

Worksheet rows:
- `petclinic+agent/fact/fe60e045` [fact] changed-code-not-executed OBSERVED: Your change has not run yet: 1 changed method of `Owner` was not executed in this run.
Checks, coverage, and limitations:
- petclinic check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 122 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- petclinic check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- petclinic check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- petclinic limitation: The journal evicted 31251 older events, so requests before the oldest retained event are not projected.
- petclinic limitation: 122 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic+agent check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 121 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic+agent check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 121 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic+agent check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 121 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic+agent check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. 121 requests or executions started before events the journal evicted or cleared, so they are left out: some of their events may be missing.
- petclinic+agent check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- petclinic+agent limitation: The journal evicted 31173 older events, so requests before the oldest retained event are not projected.
- petclinic+agent limitation: 121 requests or executions and 120 ERROR logs without a request id started before events the journal evicted or cleared, so they are left out: some of their events may be missing.

## jhipster
### JH-1 (miss, run `jhipster and jhipster+agent`, first run: missed)
POST /api/admin/users answers 201 while its @Async activation email fails against the SMTP server that does not run, and the exception is swallowed (UserResource.java:119, MailService.java:54-80, application-dev.yml:51-53).
Kinds: errors-behind-2xx, work-after-response; subjects: POST /api/admin/users

Worksheet rows:
- `jhipster/fact/1c61d2f0` [fact] errors-behind-2xx OBSERVED: `POST /api/admin/users` answered 2xx in 13 of 13 successful requests: 13 requests whose own task failed.
- `jhipster/fact/56bdfa9e` [fact] route-time-breakdown OBSERVED: `POST /api/admin/users`: warm median 98 ms over 12 requests; Handler, other work 94 %, Hibernate flushes 2 %, Response write 1 %. Median CPU 92 ms and 570 KB allocated per request. First request 255 ms (cold).

### JH-2 (miss, run `jhipster`, first run: missed)
The dev profile's DEBUG logging and LoggingAspect dominate handler time and allocation (application-dev.yml:18, LoggingAspectConfiguration.java:13).
Kinds: route-time-breakdown; subjects: POST /api/admin/users, POST /api/authenticate

Worksheet rows:
- `jhipster/fact/1c61d2f0` [fact] errors-behind-2xx OBSERVED: `POST /api/admin/users` answered 2xx in 13 of 13 successful requests: 13 requests whose own task failed.
- `jhipster/fact/56bdfa9e` [fact] route-time-breakdown OBSERVED: `POST /api/admin/users`: warm median 98 ms over 12 requests; Handler, other work 94 %, Hibernate flushes 2 %, Response write 1 %. Median CPU 92 ms and 570 KB allocated per request. First request 255 ms (cold).
- `jhipster/fact/6a1818fd` [fact] route-time-breakdown OBSERVED: `POST /api/authenticate`: warm median 87 ms over 38 requests; Handler, other work 96 %, Response write 3 %, Other filters 1 %. Median CPU 84 ms and 283 KB allocated per request. First request 833 ms (cold).
Other rows in the full report (not in the worksheet):
- jhipster `exception-hotspots:2c56b900a3` listed=False exception-hotspots OBSERVED: `POST /api/authenticate` recorded `BadCredentialsException` in 13 of 39 requests (13 occurrences). (unlisted: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.)

### JH-3 (miss, run `jhipster`, first run: found)
POST /api/account/reset-password/init with a JSON-quoted email fails validation and never reaches the reset logic (AccountResource.java:162).
Kinds: exception-hotspots; subjects: POST /api/account/reset-password/init

Worksheet rows:
- `jhipster/fact/2aa57137` [fact] anonymous-data-reach OBSERVED: `POST /api/account/reset-password/init` wrote table `jhi_user` in 13 of 13 successful anonymous requests.
- `jhipster/fact/c25fff20` [fact] errors-behind-2xx OBSERVED: `POST /api/account/reset-password/init` answered 2xx in 13 of 13 successful requests: 13 requests whose own task failed.
- `jhipster/hidden/1a1d80e9` [hidden] exception-hotspots OBSERVED: `POST /api/account/reset-password/init` recorded `ConstraintViolationException` in 13 of 26 requests (13 occurrences).
- `jhipster/hidden/6b9f27ba` [hidden] exception-hotspots OBSERVED: `POST /api/account/reset-password/init` recorded `MailSendException` in 13 of 26 requests (13 occurrences).
Other rows in the full report (not in the worksheet):
- jhipster `route-time-breakdown:291ed86f12` listed=False route-time-breakdown OBSERVED: `POST /api/account/reset-password/init`: warm median 6.6 ms over 25 requests; Handler, other work 50 %, Hibernate flushes 29 %, Other filters 10 %. Median CPU 7.2 ms and 624 KB allocated per request. First request 28 ms  (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)

### JH-4 (miss, run `jhipster and jhipster+agent`, first run: not run)
A valid POST /api/account/reset-password/init answers 200 while its @Async reset email fails against the SMTP server that does not run (AccountResource.java:162-173, MailService.java:115-118). Its request is new in the rerun's traffic.
Kinds: errors-behind-2xx, work-after-response; subjects: POST /api/account/reset-password/init

Worksheet rows:
- `jhipster/fact/2aa57137` [fact] anonymous-data-reach OBSERVED: `POST /api/account/reset-password/init` wrote table `jhi_user` in 13 of 13 successful anonymous requests.
- `jhipster/fact/c25fff20` [fact] errors-behind-2xx OBSERVED: `POST /api/account/reset-password/init` answered 2xx in 13 of 13 successful requests: 13 requests whose own task failed.
- `jhipster/hidden/1a1d80e9` [hidden] exception-hotspots OBSERVED: `POST /api/account/reset-password/init` recorded `ConstraintViolationException` in 13 of 26 requests (13 occurrences).
- `jhipster/hidden/6b9f27ba` [hidden] exception-hotspots OBSERVED: `POST /api/account/reset-password/init` recorded `MailSendException` in 13 of 26 requests (13 occurrences).
Other rows in the full report (not in the worksheet):
- jhipster `route-time-breakdown:291ed86f12` listed=False route-time-breakdown OBSERVED: `POST /api/account/reset-password/init`: warm median 6.6 ms over 25 requests; Handler, other work 50 %, Hibernate flushes 29 %, Other filters 10 %. Median CPU 7.2 ms and 624 KB allocated per request. First request 28 ms  (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)

### JH-C1 (counterexample, run `jhipster`)
BCrypt on authentication and user creation is deliberate cost (SecurityConfiguration.java:41).
Kinds: -; subjects: POST /api/authenticate, POST /api/admin/users

Worksheet rows:
- `jhipster/fact/1c61d2f0` [fact] errors-behind-2xx OBSERVED: `POST /api/admin/users` answered 2xx in 13 of 13 successful requests: 13 requests whose own task failed.
- `jhipster/fact/56bdfa9e` [fact] route-time-breakdown OBSERVED: `POST /api/admin/users`: warm median 98 ms over 12 requests; Handler, other work 94 %, Hibernate flushes 2 %, Response write 1 %. Median CPU 92 ms and 570 KB allocated per request. First request 255 ms (cold).
- `jhipster/fact/6a1818fd` [fact] route-time-breakdown OBSERVED: `POST /api/authenticate`: warm median 87 ms over 38 requests; Handler, other work 96 %, Response write 3 %, Other filters 1 %. Median CPU 84 ms and 283 KB allocated per request. First request 833 ms (cold).
Other rows in the full report (not in the worksheet):
- jhipster `exception-hotspots:2c56b900a3` listed=False exception-hotspots OBSERVED: `POST /api/authenticate` recorded `BadCredentialsException` in 13 of 39 requests (13 occurrences). (unlisted: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.)
Checks, coverage, and limitations:
- jhipster check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- jhipster check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- jhipster check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- jhipster check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- jhipster check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application
- jhipster limitation: The application's declared routes could not be read, so the routes no request reached are not listed: that does not mean every route was exercised.

### JH-C2 (counterexample, run `jhipster`)
Invalid bodies, bad credentials, anonymous calls, and missing ids answer the intended 400, 401, and 404.
Kinds: -; subjects: (several)

Worksheet rows: none.
Checks, coverage, and limitations:
- jhipster check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- jhipster check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- jhipster check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- jhipster check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- jhipster check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application
- jhipster limitation: The application's declared routes could not be read, so the routes no request reached are not listed: that does not mean every route was exercised.

### JH-C3 (counterexample, run `jhipster`)
Actuator requests and requests Spring Security rejects reach no application handler: their time is not application code.
Kinds: -; subjects: GET /management/health, GET /management/info, GET /api/account

Worksheet rows:
- `jhipster/hidden/0c875dfe` [hidden] route-time-breakdown INSUFFICIENT: `GET /management/health`: warm median 2.9 ms over 13 requests; all of them reached no handler BootUI marks (an Actuator endpoint, a request the security filters answered, or a servlet outside Spring MVC), so the time is not split into phases. Recorded calls: C
Other rows in the full report (not in the worksheet):
- jhipster `route-time-breakdown:acd55ae4fa` listed=False route-time-breakdown OBSERVED: `GET /api/account`: warm median 7.8 ms over 13 requests; Handler, other work 64 %, Other filters 15 %, Response write 12 %. Median CPU 7.3 ms and 278 KB allocated per request. First request 17 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
Checks, coverage, and limitations:
- jhipster check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- jhipster check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- jhipster check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- jhipster check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- jhipster check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application
- jhipster limitation: The application's declared routes could not be read, so the routes no request reached are not listed: that does not mean every route was exercised.

## super-heroes
### SH-1 (miss, run `super-heroes`, first run: hidden)
DELETE /api/villains (not exercised) loads every villain and deletes them one by one (VillainService.java:130-136); the routes the traffic never reaches must be listed as not exercised.
Kinds: coverage; subjects: DELETE /api/villains, PUT /api/villains

Worksheet rows: none.
Checks, coverage, and limitations:
- super-heroes check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- super-heroes check anonymous-data-reach NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to capture authenticati
- super-heroes check anonymous-success-on-restricted-route NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to cap
- super-heroes check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTra
- super-heroes check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- super-heroes check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransact
- super-heroes check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against Configura
- super-heroes check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- super-heroes check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application w
- super-heroes check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applica
- super-heroes not exercised (2 + 0 omitted): "DELETE /api/villains"; "PUT /api/villains"

### SH-2 (miss, run `super-heroes`, first run: found)
GET /api/villains is not paginated, and GET /api/villains/random runs a count and an offset query per request (VillainService.java:46-57, Villain.java:36-51).
Kinds: route-time-breakdown; subjects: GET /api/villains, GET /api/villains/random

Worksheet rows:
- `super-heroes/hidden/f3cf2bff` [hidden] route-time-breakdown OBSERVED: `GET /api/villains`: warm median 5.4 ms over 62 requests; SQL 49 %, Handler, other work 33 %, Other filters 11 %. First request 28 ms (cold).
Other rows in the full report (not in the worksheet):
- super-heroes `route-time-breakdown:f85ec4f404` listed=False route-time-breakdown OBSERVED: `GET /api/villains/random`: warm median 7.6 ms over 20 requests; SQL 63 %, Handler, other work 26 %, Other filters 9 %. First request 20 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)

### SH-3 (miss, run `super-heroes`, first run: found)
GET / renders the complete villain list rather than a bounded page (UIResource.java:31-40).
Kinds: route-time-breakdown; subjects: GET /

Worksheet rows:
- `super-heroes/hidden/2ea3ae37` [hidden] route-time-breakdown OBSERVED: `GET /`: warm median 7.0 ms over 43 requests; Handler, other work 52 %, SQL 33 %, Other filters 7 %. First request 311 ms (cold).

### SH-4 (miss, run `super-heroes+agent`, first run: not run)
In the agent run, change.patch changes deleteAllVillains, which nothing runs: changed-code-not-executed must report it.
Kinds: changed-code-not-executed; subjects: io.quarkus.sample.superheroes.villain.service.VillainService

Worksheet rows:
- `super-heroes+agent/fact/f379ff0d` [fact] changed-code-not-executed OBSERVED: Your change has not run yet: 1 changed method of `VillainService` was not executed in this run.

### SH-5 (miss, run `super-heroes+agent`, first run: not run)
In the agent run, change.patch changes VillainResource.deleteAllVillains (DELETE /api/villains), which the traffic never calls: changed-code-not-executed must report it.
Kinds: changed-code-not-executed; subjects: io.quarkus.sample.superheroes.villain.rest.VillainResource

Worksheet rows:
- `super-heroes+agent/fact/3ee46b2c` [fact] changed-code-not-executed OBSERVED: Your change has not run yet: 1 changed method of `VillainResource` was not executed in this run.

### SH-C1 (counterexample, run `super-heroes`)
Invalid villains answer 400 through bean validation, and missing or non-numeric ids answer 404, on purpose.
Kinds: -; subjects: POST /api/villains, GET /api/villains/{id}

Worksheet rows:
- `super-heroes/hidden/8c617d97` [hidden] route-time-breakdown OBSERVED: `GET /api/villains/{id}`: warm median 4.0 ms over 83 requests; Handler, other work 41 %, SQL 38 %, Other filters 17 %. First request 13 ms (cold).
- `super-heroes/hidden/daf6376e` [hidden] route-time-breakdown OBSERVED: `POST /api/villains`: warm median 4.4 ms over 41 requests; Handler, other work 59 %, SQL 23 %, Hibernate flushes 9 %. First request 84 ms (cold).
- `super-heroes/hidden/e7fd48a2` [hidden] exception-hotspots OBSERVED: `POST /api/villains` recorded `ResteasyReactiveViolationException` in 21 of 42 requests (21 occurrences).
Other rows in the full report (not in the worksheet):
- super-heroes `exception-hotspots:653fe5bc87` listed=False exception-hotspots OBSERVED: `GET /api/villains/{id}` recorded `NotFoundException` in 21 of 84 requests (21 occurrences). (unlisted: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.)
Checks, coverage, and limitations:
- super-heroes check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- super-heroes check anonymous-data-reach NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to capture authenticati
- super-heroes check anonymous-success-on-restricted-route NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to cap
- super-heroes check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTra
- super-heroes check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- super-heroes check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransact
- super-heroes check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against Configura
- super-heroes check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- super-heroes check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application w
- super-heroes check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applica
- super-heroes not exercised (2 + 0 omitted): "DELETE /api/villains"; "PUT /api/villains"

### SH-C2 (counterexample, run `super-heroes`)
Health, OpenAPI, and hello endpoints are framework or trivial endpoints, not application performance problems.
Kinds: -; subjects: GET /q/health, GET /q/openapi, GET /api/villains/hello

Worksheet rows:
- `super-heroes/hidden/a649fc2b` [hidden] route-time-breakdown INSUFFICIENT: `GET /q/health`: warm median 2.0 ms over 5 requests; all of them reached no handler BootUI marks (a framework endpoint such as /q/health, a Vert.x route or static resource, or a request an HTTP security policy answered), so the time is not split into phases. F
- `super-heroes/hidden/ba2e94e3` [hidden] route-time-breakdown INSUFFICIENT: `GET /q/openapi`: warm median 0.2 ms over 5 requests; all of them reached no handler BootUI marks (a framework endpoint such as /q/health, a Vert.x route or static resource, or a request an HTTP security policy answered), so the time is not split into phases.
Other rows in the full report (not in the worksheet):
- super-heroes `route-time-breakdown:f541cb85a4` listed=False route-time-breakdown INSUFFICIENT: `GET /api/villains/hello`: 0 of 5 warm requests needed. First request 1.6 ms (cold). (unlisted: It has fewer than 5 warm requests, so where its time goes is not known yet, and fewer than 2 of them or a warm median under 100 ms.)
Checks, coverage, and limitations:
- super-heroes check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- super-heroes check anonymous-data-reach NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to capture authenticati
- super-heroes check anonymous-success-on-restricted-route NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to cap
- super-heroes check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTra
- super-heroes check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- super-heroes check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransact
- super-heroes check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against Configura
- super-heroes check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- super-heroes check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application w
- super-heroes check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applica
- super-heroes not exercised (2 + 0 omitted): "DELETE /api/villains"; "PUT /api/villains"

### SH-C3 (counterexample, run `super-heroes`)
Blocking endpoints run on virtual threads (@RunOnVirtualThread), not on the event loop.
Kinds: -; subjects: (event-loop-blocking)

Worksheet rows: none.
Checks, coverage, and limitations:
- super-heroes check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- super-heroes check anonymous-data-reach NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to capture authenticati
- super-heroes check anonymous-success-on-restricted-route NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to cap
- super-heroes check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTra
- super-heroes check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- super-heroes check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransact
- super-heroes check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against Configura
- super-heroes check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- super-heroes check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application w
- super-heroes check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applica
- super-heroes not exercised (2 + 0 omitted): "DELETE /api/villains"; "PUT /api/villains"

### SH-C4 (counterexample, run `super-heroes+agent`)
In the agent run, change.patch changes findAllVillainsHavingName, which the traffic runs: changed-code-not-executed must not report it.
Kinds: -; subjects: io.quarkus.sample.superheroes.villain.service.VillainService

Worksheet rows:
- `super-heroes+agent/fact/f379ff0d` [fact] changed-code-not-executed OBSERVED: Your change has not run yet: 1 changed method of `VillainService` was not executed in this run.
Checks, coverage, and limitations:
- super-heroes check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- super-heroes check anonymous-data-reach NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to capture authenticati
- super-heroes check anonymous-success-on-restricted-route NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to cap
- super-heroes check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTra
- super-heroes check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- super-heroes check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransact
- super-heroes check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against Configura
- super-heroes check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- super-heroes check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application w
- super-heroes check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applica
- super-heroes not exercised (2 + 0 omitted): "DELETE /api/villains"; "PUT /api/villains"
- super-heroes+agent check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- super-heroes+agent check anonymous-data-reach NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension) to capture authen
- super-heroes+agent check anonymous-success-on-restricted-route NOT_APPLICABLE eligible=0 findings=0: The security-logs panel, whose evidence this reads, is not available in this application: Quarkus security events are disabled. Set quarkus.security.events.enabled=true (with a security extension)
- super-heroes+agent check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against Configura
- super-heroes+agent check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- super-heroes+agent check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTr
- super-heroes+agent check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against Con
- super-heroes+agent check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- super-heroes+agent check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- super-heroes+agent not exercised (2 + 0 omitted): "DELETE /api/villains"; "PUT /api/villains"

## webflux-gateway
### WF-1 (miss, run `webflux-gateway`, first run: hidden)
GET /api/admin/users loads the whole user and authority join over R2DBC and pages, sorts, and limits it in memory (UserRepository.java:100-114); BootUI cannot see R2DBC, and must say so.
Kinds: coverage; subjects: GET /api/admin/users

Worksheet rows:
- `webflux-gateway/hidden/fc1557c2` [hidden] route-time-breakdown INSUFFICIENT: `GET /api/admin/users`: warm median 4.7 ms over 71 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 4.3 ms and 628 KB allocated per request. First request 12 ms (cold).
Checks, coverage, and limitations:
- webflux-gateway check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC. Its SQL and connectio
- webflux-gateway check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Spring WebFlux has no open session in view, so no statement runs while the response is written.
- webflux-gateway check event-loop-blocking UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- webflux-gateway check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the appl
- webflux-gateway limitation: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway limitation: The application's declared routes could not be read, so the routes no request reached are not listed: that does not mean every route was exercised.

### WF-2 (miss, run `webflux-gateway`, first run: found)
Each successful user creation hides a failed activation email behind its 201 (UserResource.java:132, MailService.java:53-56).
Kinds: errors-behind-2xx; subjects: POST /api/admin/users

Worksheet rows:
- `webflux-gateway/fact/18d872b0` [fact] errors-behind-2xx OBSERVED: `POST /api/admin/users` answered 2xx in 24 of 24 successful requests: 24 requests that recorded an exception.
- `webflux-gateway/hidden/716264e7` [hidden] exception-hotspots OBSERVED: `POST /api/admin/users` recorded `MailSendException` in 24 of 48 requests (24 occurrences).
- `webflux-gateway/honesty/45559ea2` [honesty] route-time-breakdown INSUFFICIENT: `POST /api/admin/users`: warm median 22 ms over 47 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 21 ms and 3.9 MB allocated per request. First request 473 ms (cold).

### WF-3 (miss, run `webflux-gateway`, first run: found)
Every PUT /api/admin/users/{login} fails, since the body carries no id and the path's login is not used (UserResource.java:154-167).
Kinds: exception-hotspots; subjects: PUT /api/admin/users/{login}

Worksheet rows:
- `webflux-gateway/hidden/13b00168` [hidden] route-time-breakdown INSUFFICIENT: `PUT /api/admin/users/{login}`: warm median 8.9 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 8.6 ms and 1.7 MB allocated per request. First request 25 ms (cold).
- `webflux-gateway/hidden/25818fa9` [hidden] exception-hotspots OBSERVED: `PUT /api/admin/users/{login}` recorded `EmailAlreadyUsedException` in 24 of 24 requests (24 occurrences).

### WF-4 (miss, run `webflux-gateway`, first run: found)
Gateway routes to a service that does not run answer 500 rather than a gateway error or fallback (application.yml:116-135).
Kinds: exception-hotspots; subjects: GET /services/absent/api/orders, GET /services/absent/management/health/readiness

Worksheet rows:
- `webflux-gateway/fact/54542269` [fact] exception-hotspots OBSERVED: `GET /services/absent/management/health/readiness` recorded `AbstractChannel$AnnotatedConnectException` in 24 of 24 requests (24 occurrences).
- `webflux-gateway/fact/726b62eb` [fact] exception-hotspots OBSERVED: `GET /services/absent/api/orders` recorded `AbstractChannel$AnnotatedConnectException` in 24 of 24 requests (24 occurrences).
Other rows in the full report (not in the worksheet):
- webflux-gateway `route-time-breakdown:579b87cea6` listed=False route-time-breakdown INSUFFICIENT: `GET /services/absent/management/health/readiness`: warm median 6.4 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 5.5 ms and 1.2 MB a (unlisted: Its time is not split into phases, since its requests reached no handler BootUI marks or named nothing at all, and its warm median is under 20 ms.)
- webflux-gateway `route-time-breakdown:68efaaa172` listed=False route-time-breakdown INSUFFICIENT: `GET /services/absent/api/orders`: warm median 7.3 ms over 23 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 6.2 ms and 1.3 MB allocated per requ (unlisted: Its time is not split into phases, since its requests reached no handler BootUI marks or named nothing at all, and its warm median is under 20 ms.)

### WF-5 (miss, run `webflux-gateway`, first run: missed)
Declared routes the traffic never reaches must be listed as not exercised (at least seven in the first run).
Kinds: coverage; subjects: (not exercised)

Worksheet rows: none.
Checks, coverage, and limitations:
- webflux-gateway check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC. Its SQL and connectio
- webflux-gateway check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Spring WebFlux has no open session in view, so no statement runs while the response is written.
- webflux-gateway check event-loop-blocking UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- webflux-gateway check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the appl
- webflux-gateway limitation: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway limitation: The application's declared routes could not be read, so the routes no request reached are not listed: that does not mean every route was exercised.

### WF-C1 (counterexample, run `webflux-gateway`)
Anonymous readiness and the deliberate 401 probes are configured behaviour (SecurityConfiguration.java:81-82).
Kinds: -; subjects: GET /api/account, GET /api/admin/users

Worksheet rows:
- `webflux-gateway/hidden/fc1557c2` [hidden] route-time-breakdown INSUFFICIENT: `GET /api/admin/users`: warm median 4.7 ms over 71 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 4.3 ms and 628 KB allocated per request. First request 12 ms (cold).
Other rows in the full report (not in the worksheet):
- webflux-gateway `route-time-breakdown:acd55ae4fa` listed=False route-time-breakdown INSUFFICIENT: `GET /api/account`: warm median 5.2 ms over 47 requests, none of it in a recorded call; Spring WebFlux marks no phases, so where that time went is not known. Median CPU 4.9 ms and 869 KB allocated per request. First requ (unlisted: Its time is not split into phases, since its requests reached no handler BootUI marks or named nothing at all, and its warm median is under 20 ms.)
Checks, coverage, and limitations:
- webflux-gateway check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC. Its SQL and connectio
- webflux-gateway check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Spring WebFlux has no open session in view, so no statement runs while the response is written.
- webflux-gateway check event-loop-blocking UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- webflux-gateway check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the appl
- webflux-gateway limitation: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway limitation: The application's declared routes could not be read, so the routes no request reached are not listed: that does not mean every route was exercised.

### WF-C2 (counterexample, run `webflux-gateway`)
WebFlux marks no phases: time is not attributed to application code it cannot see.
Kinds: -; subjects: (route-time-breakdown)

Worksheet rows: none.
Checks, coverage, and limitations:
- webflux-gateway check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC. Its SQL and connectio
- webflux-gateway check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Spring WebFlux has no open session in view, so no statement runs while the response is written.
- webflux-gateway check event-loop-blocking UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- webflux-gateway check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- webflux-gateway check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the appl
- webflux-gateway limitation: This application's database access is not recorded: it uses R2DBC, and BootUI records JDBC statements through a traced DataSource, not R2DBC.
- webflux-gateway limitation: The application's declared routes could not be read, so the routes no request reached are not listed: that does not mean every route was exercised.

## kafka
### K-1 (miss, run `kafka`, first run: hidden)
The payment and stock listeners read in a read-only transaction and save in a second one, with a repeated select during the merge (payment and stock OrderManageService.java:24-39).
Kinds: split-transaction-writes, repeated-selects; subjects: consume kafka:orders

Worksheet rows: none.
Checks, coverage, and limitations:
- kafka/order-service check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no trace
- kafka/order-service check lazy-sql-after-handler UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- kafka/order-service check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applic
- kafka/order-service check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the
- kafka/order-service not exercised (5 + 0 omitted): "GET /swagger-ui.html"; "GET /v3/api-docs"; "GET /v3/api-docs.yaml"; "GET /v3/api-docs/swagger-config"; "POST /orders/generate"
- kafka/order-service limitation: Kafka Streams is on this application's classpath, and BootUI does not record stream processing: the records a topology consumes and produces are neither executions nor messages here, so a service that only processes streams can show no obse
- kafka/payment-service check route-time-breakdown INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check errors-behind-2xx INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check repeated-selects EVALUATED eligible=400 findings=0:
- kafka/payment-service check safe-method-dml INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.

### K-2 (miss, run `kafka`, first run: hidden)
Listener-only services do SQL, transaction, and ORM work per consumed message that must be visible per execution.
Kinds: route-time-breakdown, coverage; subjects: consume kafka:orders

Worksheet rows: none.
Other rows in the full report (not in the worksheet):
- (same kind, other subject) kafka/order-service `route-time-breakdown:3bb9ca6432` listed=False route-time-breakdown OBSERVED: `POST /orders`: warm median 1.2 ms over 199 requests; Handler, other work 60 %, Response write 21 %, Other filters 19 %. Median CPU 1.1 ms and 33 KB allocated per request. First request 44 ms (cold).
- (same kind, other subject) kafka/order-service `route-time-breakdown:eb33c83b20` listed=False route-time-breakdown OBSERVED: `GET /orders`: warm median 2.1 ms over 41 requests; Handler, other work 60 %, Response write 29 %, Other filters 12 %. Median CPU 1.8 ms and 224 KB allocated per request. First request 56 ms (cold).
Checks, coverage, and limitations:
- kafka/order-service check route-time-breakdown EVALUATED eligible=240 findings=2: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use. Its SQL and connection evidence i
- kafka/order-service check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no trace
- kafka/order-service check lazy-sql-after-handler UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- kafka/order-service check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applic
- kafka/order-service check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the
- kafka/order-service not exercised (5 + 0 omitted): "GET /swagger-ui.html"; "GET /v3/api-docs"; "GET /v3/api-docs.yaml"; "GET /v3/api-docs/swagger-config"; "POST /orders/generate"
- kafka/order-service limitation: Kafka Streams is on this application's classpath, and BootUI does not record stream processing: the records a topology consumes and produces are neither executions nor messages here, so a service that only processes streams can show no obse
- kafka/payment-service check route-time-breakdown INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check errors-behind-2xx INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check safe-method-dml INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.

### K-3 (miss, run `kafka`, first run: hidden)
Kafka Streams consumes, joins, and materializes the order table in order-service (OrderApp.java:68-94), which the journal does not record; a coverage line must say so.
Kinds: coverage; subjects: (coverage)

Worksheet rows: none.
Checks, coverage, and limitations:
- kafka/order-service check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no trace
- kafka/order-service check lazy-sql-after-handler UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- kafka/order-service check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applic
- kafka/order-service check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the
- kafka/order-service not exercised (5 + 0 omitted): "GET /swagger-ui.html"; "GET /v3/api-docs"; "GET /v3/api-docs.yaml"; "GET /v3/api-docs/swagger-config"; "POST /orders/generate"
- kafka/order-service limitation: Kafka Streams is on this application's classpath, and BootUI does not record stream processing: the records a topology consumes and produces are neither executions nor messages here, so a service that only processes streams can show no obse
- kafka/payment-service check route-time-breakdown INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check errors-behind-2xx INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check safe-method-dml INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.

### K-4 (miss, run `kafka`, first run: found)
GET /orders scans the whole Kafka Streams store on every request and does not close its KeyValueIterator (OrderController.java:51-61).
Kinds: route-time-breakdown; subjects: GET /orders

Worksheet rows:
- `kafka/hidden/be122012` [hidden] route-time-breakdown OBSERVED: `GET /orders`: warm median 2.1 ms over 41 requests; Handler, other work 60 %, Response write 29 %, Other filters 12 %. Median CPU 1.8 ms and 224 KB allocated per request. First request 56 ms (cold).

### K-C1 (counterexample, run `kafka`)
Saga rejections for stock or payment are the intended outcome of the traffic.
Kinds: -; subjects: (listeners)

Worksheet rows: none.
Checks, coverage, and limitations:
- kafka/order-service check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no trace
- kafka/order-service check lazy-sql-after-handler UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- kafka/order-service check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applic
- kafka/order-service check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the
- kafka/order-service not exercised (5 + 0 omitted): "GET /swagger-ui.html"; "GET /v3/api-docs"; "GET /v3/api-docs.yaml"; "GET /v3/api-docs/swagger-config"; "POST /orders/generate"
- kafka/order-service limitation: Kafka Streams is on this application's classpath, and BootUI does not record stream processing: the records a topology consumes and produces are neither executions nor messages here, so a service that only processes streams can show no obse
- kafka/payment-service check route-time-breakdown INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check errors-behind-2xx INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check safe-method-dml INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.

### K-C2 (counterexample, run `kafka`)
POST /orders does not wait for the broker: the asynchronous acknowledgement is not handler time (OrderController.java:37-42).
Kinds: -; subjects: POST /orders

Worksheet rows:
- `kafka/hidden/08a0a568` [hidden] route-time-breakdown OBSERVED: `POST /orders`: warm median 1.2 ms over 199 requests; Handler, other work 60 %, Response write 21 %, Other filters 19 %. Median CPU 1.1 ms and 33 KB allocated per request. First request 44 ms (cold).
Checks, coverage, and limitations:
- kafka/order-service check repeated-selects UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check connections-per-request UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check safe-method-dml UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check proxy-bypass UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-data-reach UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check split-transaction-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check orm-auto-flush INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check large-persistence-context INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check. This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no trace
- kafka/order-service check lazy-sql-after-handler UNAVAILABLE eligible=0 findings=0: This application's database access is not recorded: BootUI records JDBC statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource is in use.
- kafka/order-service check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- kafka/order-service check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/order-service check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applic
- kafka/order-service check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the
- kafka/order-service not exercised (5 + 0 omitted): "GET /swagger-ui.html"; "GET /v3/api-docs"; "GET /v3/api-docs.yaml"; "GET /v3/api-docs/swagger-config"; "POST /orders/generate"
- kafka/order-service limitation: Kafka Streams is on this application's classpath, and BootUI does not record stream processing: the records a topology consumes and produces are neither executions nor messages here, so a service that only processes streams can show no obse
- kafka/payment-service check route-time-breakdown INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check errors-behind-2xx INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check safe-method-dml INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-data-reach INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check anonymous-success-on-restricted-route INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- kafka/payment-service check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.

## bookstore
### BS-1 (miss, run `bookstore and bookstore+agent`, first run: not run)
POST /orders publishes OrderCreatedEvent; two @ApplicationModuleListener handlers run after the commit in their own transactions (OrderEventInventoryHandler reads then saves the stock level), Spring Modulith records each publication in its JDBC registry, and the event is externalized to RabbitMQ (OrderService.java:33-44, OrderEventInventoryHandler.java:19-23).
Kinds: work-after-response, after-commit-writes, route-time-breakdown; subjects: POST /orders

Worksheet rows:
- `bookstore/fact/411799c2` [fact] connections-per-request OBSERVED: `POST /orders` held 3 connections of `dataSource` at the same time in 40 of 80 requests.
- `bookstore/fact/f96ae8af` [fact] split-transaction-writes OBSERVED: `POST /orders` committed its writes in up to 8 independent units in 40 of 40 requests that wrote: if these writes must succeed together, one may persist while another fails.

### BS-2 (miss, run `bookstore`, first run: not run)
The URL rules leave /buy, /cart, /update-cart, and GET /products open to anonymous visitors and protect /admin/** by role; an anonymous request never reaches an admin page (WebSecurityConfig.java:33-43).
Kinds: anonymous-data-reach, anonymous-success-on-restricted-route; subjects: POST /buy, GET /admin/catalog/products

Worksheet rows:
- `bookstore/fact/62f83aca` [fact] route-time-breakdown OBSERVED: `GET /admin/catalog/products`: warm median 28 ms over 40 requests; Response write 77 %, Handler, other work 12 %, SQL 10 %. First request 2.0 ms (cold).
Other rows in the full report (not in the worksheet):
- bookstore `route-time-breakdown:a670e3c2bd` listed=False route-time-breakdown OBSERVED: `POST /buy`: warm median 4.6 ms over 79 requests; Handler, other work 50 %, SQL 36 %, Other filters 7 %. First request 13 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- bookstore `gc-inflated-latency:7d31e7fc8a` listed=False gc-inflated-latency OBSERVED: A stop-the-world pause completed during 3 of `GET /admin/catalog/products`'s 8 slowest requests (38 %), against 1 % of its other 71 requests; those pauses total 33 ms. (unlisted: Garbage collection and heap rows are reached from the Memory panel rather than listed by default.)

### BS-C1 (counterexample, run `bookstore`)
Anonymous cart and catalog access is the intended rule, not a data-reach problem.
Kinds: -; subjects: POST /buy, POST /update-cart, GET /cart, GET /products

Worksheet rows:
- `bookstore/hidden/103d0ab2` [hidden] gc-inflated-latency OBSERVED: A stop-the-world pause completed during 3 of `GET /products`'s 16 slowest requests (19 %), against 0 % of its other 143 requests; those pauses total 42 ms.
Other rows in the full report (not in the worksheet):
- bookstore `route-time-breakdown:e770b2ab0a` listed=False route-time-breakdown OBSERVED: `GET /products`: warm median 15 ms over 159 requests; Response write 54 %, Handler, other work 23 %, SQL 18 %. First request 63 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- bookstore `route-time-breakdown:a670e3c2bd` listed=False route-time-breakdown OBSERVED: `POST /buy`: warm median 4.6 ms over 79 requests; Handler, other work 50 %, SQL 36 %, Other filters 7 %. First request 13 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- bookstore `route-time-breakdown:a86ebcf72a` listed=False route-time-breakdown OBSERVED: `POST /update-cart`: warm median 2.1 ms over 39 requests; Response write 59 %, Handler, other work 22 %, Other filters 16 %. First request 11 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- bookstore `route-time-breakdown:aa5948598d` listed=False route-time-breakdown OBSERVED: `GET /cart`: warm median 4.9 ms over 39 requests; Response write 90 %, Other filters 4 %, Handler, other work 4 %. First request 17 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
Checks, coverage, and limitations:
- bookstore check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- bookstore check transactional-listener-skipped UNAVAILABLE eligible=0 findings=0: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.
- bookstore check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.
- bookstore check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- bookstore check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- bookstore check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- bookstore check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- bookstore not exercised (1 + 0 omitted): "ANY /admin"
- bookstore limitation: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.

### BS-C2 (counterexample, run `bookstore`)
A customer gets 403 on /admin/**, and an anonymous visitor a redirect to the login form, on purpose.
Kinds: -; subjects: GET /admin/orders, GET /admin/inventory

Worksheet rows:
- `bookstore/hidden/2d06c1f8` [hidden] route-time-breakdown OBSERVED: `GET /admin/inventory`: warm median 18 ms over 40 requests; Response write 61 %, SQL 19 %, Handler, other work 17 %. First request 0.8 ms (cold).
- `bookstore/hidden/ace3d620` [hidden] route-time-breakdown OBSERVED: `GET /admin/orders`: warm median 19 ms over 40 requests; Response write 68 %, Handler, other work 19 %, SQL 11 %. First request 1.8 ms (cold).
Other rows in the full report (not in the worksheet):
- bookstore `gc-inflated-latency:3e9e4cc7c2` listed=False gc-inflated-latency OBSERVED: A stop-the-world pause completed during 2 of `GET /admin/orders`'s 8 slowest requests (25 %), against 0 % of its other 71 requests; those pauses total 20 ms. (unlisted: Garbage collection and heap rows are reached from the Memory panel rather than listed by default.)
Checks, coverage, and limitations:
- bookstore check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- bookstore check transactional-listener-skipped UNAVAILABLE eligible=0 findings=0: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.
- bookstore check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.
- bookstore check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- bookstore check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- bookstore check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- bookstore check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- bookstore not exercised (1 + 0 omitted): "ANY /admin"
- bookstore limitation: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.

### BS-C3 (counterexample, run `bookstore`)
An unknown order or product code answers 404 through OrderNotFoundException or ProductNotFoundException, on purpose.
Kinds: -; subjects: GET /orders/{orderNumber}, GET /admin/catalog/products/{code}

Worksheet rows:
- `bookstore/hidden/5c00a3ea` [hidden] exception-hotspots OBSERVED: `GET /admin/catalog/products/{code}` recorded `ProductNotFoundException` in 40 of 80 requests (40 occurrences).
Other rows in the full report (not in the worksheet):
- bookstore `route-time-breakdown:7896713806` listed=False route-time-breakdown OBSERVED: `GET /admin/catalog/products/{code}`: warm median 10 ms over 79 requests; Handler, other work 42 %, Response write 31 %, SQL 22 %. First request 20 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- bookstore `route-time-breakdown:8acc3d57a1` listed=False route-time-breakdown OBSERVED: `GET /orders/{orderNumber}`: warm median 7.7 ms over 79 requests; Handler, other work 53 %, Response write 27 %, SQL 15 %. First request 25 ms (cold). (unlisted: Its warm median is under 20 ms, and authorization takes under 20 % of its time and under 50 decisions a request, so it is not prominent.)
- bookstore `exception-hotspots:1897cf3c5e` listed=False exception-hotspots OBSERVED: `GET /orders/{orderNumber}` recorded `OrderNotFoundException` in 40 of 80 requests (40 occurrences). (unlisted: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.)
Checks, coverage, and limitations:
- bookstore check proxy-bypass INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- bookstore check transactional-listener-skipped UNAVAILABLE eligible=0 findings=0: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.
- bookstore check after-commit-writes UNAVAILABLE eligible=0 findings=0: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.
- bookstore check transaction-across-remote-call INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- bookstore check event-loop-blocking NOT_APPLICABLE eligible=0 findings=0: Spring MVC serves requests on worker threads, not on event loops.
- bookstore check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- bookstore check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the applicatio
- bookstore not exercised (1 + 0 omitted): "ANY /admin"
- bookstore limitation: This application defines its own application event multicaster, such as Spring Modulith's event publication registry, so BootUI does not record application events.

## timeless
### TL-1 (miss, run `timeless`, first run: not run)
GET /api/records without page and limit throws a NullPointerException, since RecordResource calls Optional.of on a missing query parameter, and answers 500.
Kinds: exception-hotspots, framework-warnings-by-route; subjects: GET /api/records

Worksheet rows:
- `timeless/fact/01c6181c` [fact] exception-hotspots OBSERVED: `GET /api/records` recorded `NullPointerException` in 40 of 160 requests (40 occurrences).
- `timeless/fact/637c2a77` [fact] framework-warnings-by-route OBSERVED: `GET /api/records` logged `ERROR` from `QuarkusErrorHandler` in 40 of 160 requests (40 events): "HTTP Request to /api/records failed, error id: &lt;id>".
- `timeless/hidden/39de18ae` [hidden] route-time-breakdown OBSERVED: `GET /api/records`: warm median 5.4 ms over 120 requests; SQL 43 %, Handler, other work 36 %, Other filters 19 %. Median CPU 2.5 ms and 243 KB allocated per request. First request 4.5 ms (cold).
- `timeless/hidden/5f30e1ea` [hidden] exception-hotspots OBSERVED: `GET /api/records` recorded `UnauthorizedException` in 40 of 160 requests (40 occurrences).

### TL-2 (miss, run `timeless`, first run: not run)
POST /api/messages is open to anonymous callers at the HTTP level (its comment relies on a network rule): given a phone number it reads the user, calls the AI, and writes records (MessageResource.java).
Kinds: anonymous-data-reach; subjects: POST /api/messages

Worksheet rows:
- `timeless/fact/160673f8` [fact] route-time-breakdown OBSERVED: `POST /api/messages`: warm median 316 ms over 119 requests; AI calls 96 %, Handler, other work 2 %, SQL 2 %. Median CPU 7.1 ms and 823 KB allocated per request. First request 469 ms (cold).
- `timeless/fact/54ac7ea2` [fact] ai-usage-by-route OBSERVED: `POST /api/messages` made 160 AI operations in 80 of 120 requests: 160 model calls, up to 2 in one request, a median 154 ms each, 133216 input and 3040 output tokens (reported by 160 of 160 calls). Input tokens grew across successive model calls in 80 requests
- `timeless/hidden/c365c174` [hidden] gc-inflated-latency OBSERVED: A stop-the-world pause completed during 2 of `POST /api/messages`'s 12 slowest requests (17 %), against 0 % of its other 107 requests; those pauses total 50 ms.
Other rows in the full report (not in the worksheet):
- timeless `exception-hotspots:b83824c64c` listed=False exception-hotspots OBSERVED: `POST /api/messages` recorded `NotFoundException` in 40 of 120 requests (40 occurrences). (unlisted: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.)

### TL-3 (miss, run `timeless`, first run: not run)
AI calls dominate POST /api/messages, with two model calls in one request when the getBalance tool runs, and that tool reads every record of the user (GetBalanceTool.java).
Kinds: ai-usage-by-route, route-time-breakdown; subjects: POST /api/messages

Worksheet rows:
- `timeless/fact/160673f8` [fact] route-time-breakdown OBSERVED: `POST /api/messages`: warm median 316 ms over 119 requests; AI calls 96 %, Handler, other work 2 %, SQL 2 %. Median CPU 7.1 ms and 823 KB allocated per request. First request 469 ms (cold).
- `timeless/fact/54ac7ea2` [fact] ai-usage-by-route OBSERVED: `POST /api/messages` made 160 AI operations in 80 of 120 requests: 160 model calls, up to 2 in one request, a median 154 ms each, 133216 input and 3040 output tokens (reported by 160 of 160 calls). Input tokens grew across successive model calls in 80 requests
- `timeless/hidden/c365c174` [hidden] gc-inflated-latency OBSERVED: A stop-the-world pause completed during 2 of `POST /api/messages`'s 12 slowest requests (17 %), against 0 % of its other 107 requests; those pauses total 50 ms.
Other rows in the full report (not in the worksheet):
- timeless `exception-hotspots:b83824c64c` listed=False exception-hotspots OBSERVED: `POST /api/messages` recorded `NotFoundException` in 40 of 120 requests (40 occurrences). (unlisted: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.)

### TL-4 (miss, run `timeless`, first run: not run)
GET /api/records counts the user's records, reads one page, and then reads the amount and type of every record of the user on every request (RecordResource.getRecords).
Kinds: route-time-breakdown; subjects: GET /api/records

Worksheet rows:
- `timeless/fact/01c6181c` [fact] exception-hotspots OBSERVED: `GET /api/records` recorded `NullPointerException` in 40 of 160 requests (40 occurrences).
- `timeless/fact/637c2a77` [fact] framework-warnings-by-route OBSERVED: `GET /api/records` logged `ERROR` from `QuarkusErrorHandler` in 40 of 160 requests (40 events): "HTTP Request to /api/records failed, error id: &lt;id>".
- `timeless/hidden/39de18ae` [hidden] route-time-breakdown OBSERVED: `GET /api/records`: warm median 5.4 ms over 120 requests; SQL 43 %, Handler, other work 36 %, Other filters 19 %. Median CPU 2.5 ms and 243 KB allocated per request. First request 4.5 ms (cold).
- `timeless/hidden/5f30e1ea` [hidden] exception-hotspots OBSERVED: `GET /api/records` recorded `UnauthorizedException` in 40 of 160 requests (40 occurrences).

### TL-C1 (counterexample, run `timeless`)
BCrypt makes POST /api/sign-in slow on purpose.
Kinds: -; subjects: POST /api/sign-in

Worksheet rows:
- `timeless/fact/f6bad530` [fact] route-time-breakdown OBSERVED: `POST /api/sign-in`: warm median 100 ms over 79 requests; Handler, other work 98 %, SQL 2 %, Other filters 0 %. Median CPU 98 ms and 328 KB allocated per request. First request 101 ms (cold).
Checks, coverage, and limitations:
- timeless check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- timeless check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransac
- timeless check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- timeless check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransactionM
- timeless check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableT
- timeless check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- timeless check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- timeless check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- timeless check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application
- timeless not exercised (6 + 0 omitted): "POST /api/messages/image"; "POST /chat/completions"; "POST /completions"; "POST /embeddings"; "POST /images/generations"; "POST /moderations"
- timeless limitation: Retained non-HTTP executions, which requests does not count: 16 scheduled runs and 0 consumed messages.

### TL-C2 (counterexample, run `timeless`)
The AI call runs before QuarkusTransaction.requiringNew, outside any transaction: no transaction is held across it.
Kinds: -; subjects: POST /api/messages

Worksheet rows:
- `timeless/fact/160673f8` [fact] route-time-breakdown OBSERVED: `POST /api/messages`: warm median 316 ms over 119 requests; AI calls 96 %, Handler, other work 2 %, SQL 2 %. Median CPU 7.1 ms and 823 KB allocated per request. First request 469 ms (cold).
- `timeless/fact/54ac7ea2` [fact] ai-usage-by-route OBSERVED: `POST /api/messages` made 160 AI operations in 80 of 120 requests: 160 model calls, up to 2 in one request, a median 154 ms each, 133216 input and 3040 output tokens (reported by 160 of 160 calls). Input tokens grew across successive model calls in 80 requests
- `timeless/hidden/c365c174` [hidden] gc-inflated-latency OBSERVED: A stop-the-world pause completed during 2 of `POST /api/messages`'s 12 slowest requests (17 %), against 0 % of its other 107 requests; those pauses total 50 ms.
Other rows in the full report (not in the worksheet):
- timeless `exception-hotspots:b83824c64c` listed=False exception-hotspots OBSERVED: `POST /api/messages` recorded `NotFoundException` in 40 of 120 requests (40 occurrences). (unlisted: It was recorded only behind 4xx responses, which are usually intended, and is counted in the row Behind 4xx responses.)
Checks, coverage, and limitations:
- timeless check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- timeless check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransac
- timeless check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- timeless check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransactionM
- timeless check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableT
- timeless check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- timeless check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- timeless check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- timeless check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application
- timeless not exercised (6 + 0 omitted): "POST /api/messages/image"; "POST /chat/completions"; "POST /completions"; "POST /embeddings"; "POST /images/generations"; "POST /moderations"
- timeless limitation: Retained non-HTTP executions, which requests does not count: 16 scheduled runs and 0 consumed messages.

### TL-C3 (counterexample, run `timeless`)
Panache's blocking JDBC runs on worker threads, not on the Vert.x event loop.
Kinds: -; subjects: (event-loop-blocking)

Worksheet rows: none.
Checks, coverage, and limitations:
- timeless check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- timeless check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransac
- timeless check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- timeless check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransactionM
- timeless check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableT
- timeless check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- timeless check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- timeless check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- timeless check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application
- timeless not exercised (6 + 0 omitted): "POST /api/messages/image"; "POST /chat/completions"; "POST /completions"; "POST /embeddings"; "POST /images/generations"; "POST /moderations"
- timeless limitation: Retained non-HTTP executions, which requests does not count: 16 scheduled runs and 0 consumed messages.

### TL-C4 (counterexample, run `timeless`)
Anonymous calls to @Authenticated resources answer 401, a duplicate sign-up 409, and another user's profile 403, on purpose.
Kinds: -; subjects: GET /api/records, POST /api/sign-up, GET /api/users/{id}

Worksheet rows:
- `timeless/fact/01c6181c` [fact] exception-hotspots OBSERVED: `GET /api/records` recorded `NullPointerException` in 40 of 160 requests (40 occurrences).
- `timeless/fact/637c2a77` [fact] framework-warnings-by-route OBSERVED: `GET /api/records` logged `ERROR` from `QuarkusErrorHandler` in 40 of 160 requests (40 events): "HTTP Request to /api/records failed, error id: &lt;id>".
- `timeless/hidden/25597938` [hidden] exception-hotspots OBSERVED: `POST /api/sign-up` recorded `ResteasyReactiveViolationException` in 40 of 120 requests (40 occurrences).
- `timeless/hidden/39de18ae` [hidden] route-time-breakdown OBSERVED: `GET /api/records`: warm median 5.4 ms over 120 requests; SQL 43 %, Handler, other work 36 %, Other filters 19 %. Median CPU 2.5 ms and 243 KB allocated per request. First request 4.5 ms (cold).
- `timeless/hidden/3baa0b75` [hidden] route-time-breakdown OBSERVED: `POST /api/sign-up`: warm median 3.1 ms over 119 requests; Handler, other work 93 %, SQL 5 %, Hibernate flushes 1 %. Median CPU 2.1 ms and 110 KB allocated per request. First request 26 ms (cold).
- `timeless/hidden/5f30e1ea` [hidden] exception-hotspots OBSERVED: `GET /api/records` recorded `UnauthorizedException` in 40 of 160 requests (40 occurrences).
- `timeless/hidden/7109b216` [hidden] route-time-breakdown OBSERVED: `GET /api/users/{id}`: warm median 2.0 ms over 79 requests; Other filters 49 %, SQL 29 %, Handler, other work 18 %. Median CPU 1.1 ms and 172 KB allocated per request. First request 1.6 ms (cold).
- `timeless/hidden/e4c9eef0` [hidden] exception-hotspots OBSERVED: `GET /api/users/{id}` recorded `UnauthorizedException` in 40 of 119 requests (40 occurrences).
Checks, coverage, and limitations:
- timeless check proxy-bypass NOT_APPLICABLE eligible=0 findings=0: Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.
- timeless check split-transaction-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransac
- timeless check transactional-listener-skipped NOT_APPLICABLE eligible=0 findings=0: Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
- timeless check after-commit-writes NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableTransactionM
- timeless check transaction-across-remote-call NOT_APPLICABLE eligible=0 findings=0: The transactions panel, whose evidence this reads, is not available in this application: transaction boundary capture relies on Spring Framework's TransactionExecutionListener hook, registered against ConfigurableT
- timeless check lazy-sql-after-handler NOT_APPLICABLE eligible=0 findings=0: Quarkus closes the session with the transaction, so a lazy load after the handler fails with LazyInitializationException, which Exception hotspots reports.
- timeless check heap-growth-after-gc INSUFFICIENT eligible=0 findings=0: No eligible work was recorded for this check.
- timeless check work-after-response NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's executors sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application with
- timeless check changed-code-not-executed NOT_APPLICABLE eligible=0 findings=0: This observation requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets to add agent-based evidence. Start the application
- timeless not exercised (6 + 0 omitted): "POST /api/messages/image"; "POST /chat/completions"; "POST /completions"; "POST /embeddings"; "POST /images/generations"; "POST /moderations"
- timeless limitation: Retained non-HTTP executions, which requests does not count: 16 scheduled runs and 0 consumed messages.
