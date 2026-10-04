// @ts-check
import {existsSync, readdirSync} from 'node:fs'
import {join, resolve} from 'node:path'

const MISSING_JAR =
  'run ./mvnw install -pl bootui-agent -am (the Spring sample builds it with -am), or set BOOTUI_AGENT_JAR'

/**
 * The BootUI agent jar the agent suites attach with `-javaagent`: `BOOTUI_AGENT_JAR`, or the one `./mvnw install` built
 * in `bootui-agent/target`. Throws with the remedy when there is none, rather than letting the JVM fail to start.
 *
 * @returns {string}
 */
export function agentJar() {
  if (process.env.BOOTUI_AGENT_JAR) {
    if (!existsSync(process.env.BOOTUI_AGENT_JAR)) {
      throw new Error(`BOOTUI_AGENT_JAR names no file: ${process.env.BOOTUI_AGENT_JAR}`)
    }
    return process.env.BOOTUI_AGENT_JAR
  }
  const target = resolve('../../bootui-agent/target')
  const jar = existsSync(target)
    ? readdirSync(target).find(
        (name) => /^bootui-agent-\d[\w.-]*\.jar$/.test(name) && !/-(tests|sources|javadoc)\.jar$/.test(name)
      )
    : undefined
  if (!jar) {
    throw new Error(`No bootui-agent-*.jar in ${target}: ${MISSING_JAR}`)
  }
  return join(target, jar)
}
