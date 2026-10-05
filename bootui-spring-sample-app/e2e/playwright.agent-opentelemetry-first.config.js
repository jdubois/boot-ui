// @ts-check
import {agentConfig} from './agent-config.js'

/**
 * The BootUI agent's evidence specs on the Spring MVC sample with the OpenTelemetry Java agent attached before it (docs/PLAN-v2.md §5.13,
 * M5-12); see agent-config.js.
 */
export default agentConfig({companion: 'opentelemetry-first'})
