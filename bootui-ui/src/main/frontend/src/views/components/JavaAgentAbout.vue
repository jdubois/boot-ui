<script setup>
// What the BootUI Java agent is, what needs it, and what it costs: shown while no agent is attached, below the setup
// card, for a developer deciding whether to attach it. Keep it in line with docs/features/java-agent.md.
const FEATURES = [
  ['Code Inventory', 'Which of your methods ran in this run, and which changed since the previous one.'],
  [
    'Code Paths',
    'The methods each route spends its time in, and method probes that record the next calls of a method you pick.'
  ],
  ['Side Effects', 'The processes, network connections, and files your code reaches outside the JVM, by route.'],
  [
    'Caught exceptions',
    'What became of the exceptions your code catches, in the Exceptions panel, with its opt-in sensor.'
  ],
  ['Thread pools', 'Tasks a request hands to an executor stay linked to that request in Live Activity.'],
  [
    'Runtime Insights',
    'Findings such as work still running after the response or changed code that has not run yet, and a run comparison that lists your code changes and new calls outside the JVM.'
  ]
]
</script>

<template>
  <section class="card java-agent-about" aria-labelledby="java-agent-about-title">
    <div class="card-body p-4">
      <h3 id="java-agent-about-title" class="h6 fw-bold mb-3">
        <i class="bi bi-question-circle me-2" aria-hidden="true"></i>What the Java agent adds
      </h3>
      <div class="java-agent-about__prose small">
        <p>
          A Java agent is a JAR the JVM loads at start-up, named by its <code>-javaagent</code> option. It starts before
          your application and can add code to classes as they load, so a tool sees which of your methods run, and what
          they do, without a change to your source code.
        </p>
        <p class="mb-0">
          The BootUI agent does nothing on its own. When BootUI, inside this application, claims it, the agent adds
          small hooks, called sensors, to the classes they watch: your own beans, the JDK's thread pools, and the calls
          that leave the JVM. Like the rest of BootUI it stays on this machine and sends nothing anywhere. It records
          metadata, such as method names, timings, and the hosts and files your code reaches, not the data your code
          handles.
        </p>
      </div>

      <div class="row g-4 mt-1">
        <div class="col-lg-7">
          <h4 id="java-agent-about-needs" class="java-agent-about__subhead">What needs it</h4>
          <dl class="java-agent-about__features small mb-0" aria-labelledby="java-agent-about-needs">
            <template v-for="[name, description] in FEATURES" :key="name">
              <dt>{{ name }}</dt>
              <dd>{{ description }}</dd>
            </template>
          </dl>
        </div>
        <div class="col-lg-5">
          <h4 class="java-agent-about__subhead">Without it</h4>
          <p class="java-agent-about__prose small">
            Every other panel works the same. Beans, configuration, HTTP exchanges, SQL, logs, and most Runtime Insights
            findings come from the framework, not from the agent.
          </p>
          <h4 class="java-agent-about__subhead">Cost and safety</h4>
          <ul class="java-agent-about__list small mb-0">
            <li>
              The default sensors are held to a measured overhead budget of 10% on a benchmark route. Sensors that would
              go over it, such as file access, stay off until you switch them on.
            </li>
            <li>It is a development tool: keep it off production, AOT, and native-image runs.</li>
            <li>HotSpot prints a class data sharing warning at start-up when it is attached; that is expected.</li>
            <li>Remove <code>-javaagent</code> and the application runs exactly as before.</li>
          </ul>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.java-agent-about__prose {
  max-width: 72ch;
}

.java-agent-about__subhead {
  font-size: 0.875rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
}

.java-agent-about__features {
  display: grid;
  gap: 0.5rem 1rem;
  grid-template-columns: minmax(8.5rem, max-content) minmax(0, 1fr);
}

.java-agent-about__features dt {
  font-weight: 600;
}

.java-agent-about__features dd {
  color: var(--bootui-text-muted);
  margin-bottom: 0;
}

.java-agent-about__list {
  padding-inline-start: 1.1rem;
}

.java-agent-about__list li + li {
  margin-top: 0.35rem;
}

@media (max-width: 575.98px) {
  .java-agent-about__features {
    grid-template-columns: 1fr;
    gap: 0.15rem;
  }

  .java-agent-about__features dd + dt {
    margin-top: 0.5rem;
  }
}
</style>
