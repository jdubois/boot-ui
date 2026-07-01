import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import AiSetupChecklist from './AiSetupChecklist.vue'

describe('AiSetupChecklist', () => {
  it('offers both frameworks and the embedded OTLP receiver on Spring Boot', () => {
    const wrapper = mount(AiSetupChecklist, {
      props: {springAiDetected: false, langChain4jDetected: false, enabled: false, hasData: false}
    })
    const text = wrapper.text()

    expect(text).toContain('Spring AI or LangChain4j on classpath')
    expect(text).toContain('Choose a framework to instrument')
    // Both setup cards render.
    expect(text).toContain('Set up Spring AI')
    expect(text).toContain('Set up LangChain4j')
    // Spring trace-capture copy mentions the embedded OTLP receiver.
    expect(text).toContain('/bootui/api/otlp/v1/traces')
    expect(text).toContain('BootUI starter')
  })

  it('offers only LangChain4j and in-process capture on Quarkus', () => {
    const wrapper = mount(AiSetupChecklist, {
      props: {
        platform: 'quarkus',
        springAiDetected: false,
        langChain4jDetected: false,
        enabled: false,
        hasData: false
      }
    })
    const text = wrapper.text()

    // Framework checklist drops the Spring AI mention.
    expect(text).toContain('LangChain4j on classpath')
    expect(text).not.toContain('Spring AI or LangChain4j on classpath')
    expect(text).not.toContain('Set up Spring AI')

    // Quarkus instrumentation card + in-process capture, no OTLP endpoint, no "starter".
    expect(text).toContain('Set up AI instrumentation')
    expect(text).toContain('quarkus-langchain4j-openai')
    expect(text).toContain('in-process')
    expect(text).not.toContain('/bootui/api/otlp/v1/traces')
    expect(text).not.toContain('BootUI starter')
  })

  it('keeps the telemetry-enable step framework-neutral on both platforms', () => {
    for (const platform of ['spring-boot', 'quarkus']) {
      const wrapper = mount(AiSetupChecklist, {
        props: {platform, springAiDetected: false, langChain4jDetected: false, enabled: false, hasData: false}
      })
      expect(wrapper.text()).toContain('bootui.telemetry.enabled=true')
    }
  })
})
