import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import AiLangChain4jSetup from './AiLangChain4jSetup.vue'

describe('AiLangChain4jSetup', () => {
  it('shows the Spring Boot setup (plain LangChain4j + embedded OTLP receiver) by default', () => {
    const wrapper = mount(AiLangChain4jSetup)
    const text = wrapper.text()

    expect(text).toContain('langchain4j-open-ai-official')
    expect(text).toContain('/bootui/api/otlp/v1/traces')
    expect(text).toContain('BootUI starter')

    expect(text).not.toContain('quarkus-langchain4j-openai')
    expect(text).not.toContain('quarkus-opentelemetry')
  })

  it('shows the Quarkus setup (in-process capture, no OTLP endpoint, no "starter") on Quarkus', () => {
    const wrapper = mount(AiLangChain4jSetup, {props: {platform: 'quarkus'}})
    const text = wrapper.text()

    expect(text).toContain('quarkus-langchain4j-openai')
    expect(text).toContain('quarkus-opentelemetry')
    expect(text).toContain('bootui.telemetry.enabled=true')
    expect(text).toContain('in-process')

    expect(text).not.toContain('/bootui/api/otlp/v1/traces')
    expect(text).not.toContain('langchain4j-open-ai-official')
    expect(text).not.toContain('BootUI starter')
  })
})
