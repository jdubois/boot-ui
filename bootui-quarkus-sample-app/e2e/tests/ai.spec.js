// @ts-check
import {expect, test} from './fixtures.js'

// The sample app wires a real LangChain4j + Ollama Dev Service (see ChatResource / AiAssistant),
// but a full model round-trip is slow and non-deterministic on CI hardware, so this spec mocks the
// three AI endpoints -- exactly as the Spring suite's ai.spec.js does -- while keeping the real
// `/bootui/api/panels` and `/bootui/api/overview` responses (the panel is genuinely available: the
// sample app really does have quarkus-langchain4j-ollama on the classpath), so the Quarkus platform
// label and the panel's real availability flow through unmocked.
const chatSpanId = '1111111111111111'

const overview = {
  enabled: true,
  springAiDetected: false,
  langChain4jDetected: true,
  totalChats: 1,
  totalInputTokens: 42,
  totalOutputTokens: 7,
  tokensByModel: {'llama3:latest': 49},
  callsByModel: {'llama3:latest': 1},
  errorCount: 0,
  averageDurationNanos: 250_000_000,
  toolCallCount: 1,
  vectorOperationCount: 1,
  embeddingCount: 0,
  recent: [
    {
      traceId: '0123456789abcdef0123456789abcdef',
      spanId: chatSpanId,
      startEpochNanos: Date.now() * 1_000_000,
      durationNanos: 250_000_000,
      provider: 'ollama',
      requestModel: 'llama3:latest',
      responseModel: 'llama3:latest',
      inputTokens: 42,
      outputTokens: 7,
      totalTokens: 49,
      finishReason: 'stop',
      statusCode: 'OK',
      operation: 'chat',
      toolCallCount: 1,
      vectorOperationCount: 1
    }
  ],
  contentBanner:
    "Prompt and completion text is not captured by default. Enable your AI framework's content-capture option " +
    '(for LangChain4j, capture GenAI message content on the OpenTelemetry instrumentation) to see message content ' +
    'in the conversation drawer.'
}

const tokenSeries = {
  minutes: 60,
  buckets: [
    {epochMinute: 100, inputTokens: 0, outputTokens: 0, callCount: 0},
    {epochMinute: 101, inputTokens: 42, outputTokens: 7, callCount: 1}
  ]
}

const detail = {
  summary: overview.recent[0],
  toolCalls: [
    {
      spanId: '2222222222222222',
      name: 'getWeather',
      startEpochNanos: overview.recent[0].startEpochNanos + 5_000_000,
      durationNanos: 20_000_000,
      statusCode: 'OK'
    }
  ],
  vectorOperations: [
    {
      spanId: '3333333333333333',
      operation: 'query',
      collectionName: 'docs',
      startEpochNanos: overview.recent[0].startEpochNanos + 10_000_000,
      durationNanos: 15_000_000,
      statusCode: 'OK'
    }
  ],
  attributes: [{key: 'gen_ai.system', type: 'string', value: 'ollama'}],
  events: [],
  contentCaptured: false,
  contentBanner: 'Message content is not on this span.'
}

test.describe('AI Usage view (Quarkus)', () => {
  test('renders AI token usage, model breakdowns, and chat detail', async ({openView, page}) => {
    await stubAi(page, overview)

    await openView('ai', 'AI Usage')
    // Quarkus reports LangChain4j (not Spring AI) as the detected framework.
    await expect(page.getByText('LangChain4j detected')).toBeVisible()
    await expect(page.locator('.kpi-card-body', {hasText: 'Total tokens'}).getByText('49', {exact: true})).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Usage by model'}).getByText('llama3:latest')).toBeVisible()
    await expect(page.getByText('Token usage (last 60 min)')).toBeVisible()

    await page.getByRole('button', {name: 'Toggle chat details'}).click()
    await expect(page.locator('.card', {hasText: `Chat ${chatSpanId}`})).toBeVisible()
    await expect(page.locator('.chat-detail-row').getByText('getWeather', {exact: true})).toBeVisible()
    await expect(page.locator('.chat-detail-row').getByText('docs', {exact: true})).toBeVisible()
    await page.locator('.chat-detail-row summary', {hasText: 'gen_ai'}).click()
    await expect(page.locator('.chat-detail-row').getByText('gen_ai.system')).toBeVisible()
  })

  test('shows disabled mode when telemetry is unavailable', async ({openView, page}) => {
    await stubAi(page, {
      ...overview,
      enabled: false,
      springAiDetected: false,
      langChain4jDetected: false,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      recent: [],
      contentBanner: null
    })

    await openView('ai', 'AI Usage')
    await expect(page.getByText('Enable BootUI telemetry capture', {exact: true})).toBeVisible()
    await expect(page.getByText('No AI chat completions recorded yet')).toHaveCount(0)
  })

  test('shows ready empty state before the first chat is recorded', async ({openView, page}) => {
    await stubAi(page, {
      ...overview,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      toolCallCount: 0,
      vectorOperationCount: 0,
      embeddingCount: 0,
      recent: []
    })

    await openView('ai', 'AI Usage')
    await expect(page.getByText('No AI chat completions recorded yet')).toBeVisible()
    await expect(page.getByText('Telemetry ready')).toBeVisible()
    await expect(page.getByText('Enable BootUI telemetry capture')).toHaveCount(0)
    // Quarkus never shows the OTLP-receiver copy that only applies to the Spring starter's
    // embedded OTLP endpoint; capture there is in-process via quarkus-opentelemetry instead.
    await expect(page.getByText('Cooperating local services can still export OTLP traces')).toHaveCount(0)
  })

  test('shows unavailable mode when no AI framework is on the classpath', async ({openView, page}) => {
    await stubAi(page, {
      ...overview,
      springAiDetected: false,
      langChain4jDetected: false,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      recent: [],
      contentBanner: null
    })

    await openView('ai', 'AI Usage')
    // The static checklist heading is Quarkus-specific: only LangChain4j applies (no Spring AI).
    await expect(page.getByText('LangChain4j on classpath', {exact: true})).toBeVisible()
    await expect(page.getByText('No AI framework is detected. Add')).toBeVisible()
    await expect(page.getByText('No AI chat completions recorded yet')).toHaveCount(0)
  })
})

/**
 * @param {import('@playwright/test').Page} page
 * @param {typeof overview} overviewResponse
 */
async function stubAi(page, overviewResponse) {
  await page.route(
    (url) => url.pathname === '/bootui/api/ai/overview',
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(overviewResponse)})
    }
  )
  await page.route(
    (url) => url.pathname === '/bootui/api/ai/tokens',
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(tokenSeries)})
    }
  )
  await page.route(
    (url) => url.pathname === `/bootui/api/ai/chats/${chatSpanId}`,
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(detail)})
    }
  )
}
