// A deterministic stand-in for a chat model, so the AI holdout runs locally without a paid API or a downloaded
// model. It answers the OpenAI chat completions API (POST /v1/chat/completions) and Ollama's chat API (POST /api/chat)
// with the same decisions, and reports token usage, so AI usage checks have something to count.
//
// For Timeless it returns the JSON its TextAiService asks for: a balance question first calls the application's
// getBalance tool (a database read inside the AI call), then answers; any other message is read as one transaction
// whose amount is the first number in the message. Any other application gets a short fixed answer.
//
//   node validation/stubs/llm-stub.mjs --port 18199 [--delay-ms 150]

import {createServer} from 'node:http'
import {parseArgs} from 'node:util'

const {values} = parseArgs({
  options: {port: {type: 'string', default: '18199'}, 'delay-ms': {type: 'string', default: '150'}}
})
const port = Number(values.port)
if (port === 8080) throw new Error('Port 8080 is reserved for the developer’s own application')
const delayMs = Number(values['delay-ms'])
let calls = 0

function text(content) {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) return content.map((part) => part.text ?? '').join(' ')
  return ''
}

/** The decision for one conversation: a tool call or a final answer. */
export function decide(messages, tools) {
  const user = text(messages.filter((m) => m.role === 'user').at(-1)?.content)
  const toolResult = messages.find((m) => m.role === 'tool')
  const message = /---\s*([\s\S]*?)\s*---\s*$/.exec(user)?.[1] ?? user
  const userId = /The user ID is ([^\s.]+)/.exec(user)?.[1] ?? 'unknown'
  const asksBalance = /balance|saldo|how much do i have/i.test(message)
  const hasBalanceTool = (tools || []).some((t) => (t.function?.name ?? t.name) === 'getBalance')
  if (asksBalance && hasBalanceTool && !toolResult) {
    return {toolCall: {name: 'getBalance', arguments: {userId}}}
  }
  if (/AllRecognizedOperations|"operation"/.test(user)) {
    if (asksBalance) {
      return {content: JSON.stringify({all: [{operation: 'GET_BALANCE', recognizedTransaction: null}]})}
    }
    const amount = Number(/(\d+(?:[.,]\d+)?)/.exec(message)?.[1]?.replace(',', '.') ?? 0)
    const incoming = /received|recebi|salary|salário/i.test(message)
    const recognizedTransaction = {
      amount,
      description: message.slice(0, 40),
      type: incoming ? 'IN' : 'OUT',
      withError: amount === 0,
      category: amount === 0 ? 'NONE' : incoming ? 'FINANCIAL_FREEDOM' : 'FIXED_COSTS'
    }
    return {content: JSON.stringify({all: [{operation: 'ADD_TRANSACTION', recognizedTransaction}]})}
  }
  return {content: 'This is a deterministic answer from the BootUI validation stub.'}
}

function usage(messages, answer) {
  const prompt = Math.ceil(messages.map((m) => text(m.content)).join(' ').length / 4)
  const completion = Math.ceil((answer.content ?? JSON.stringify(answer.toolCall)).length / 4)
  return {prompt, completion}
}

function openAi(body) {
  const answer = decide(body.messages || [], body.tools)
  const tokens = usage(body.messages || [], answer)
  const message = answer.toolCall
    ? {
        role: 'assistant',
        content: null,
        tool_calls: [
          {
            id: `call_${calls}`,
            type: 'function',
            function: {name: answer.toolCall.name, arguments: JSON.stringify(answer.toolCall.arguments)}
          }
        ]
      }
    : {role: 'assistant', content: answer.content}
  return {
    id: `chatcmpl-stub-${calls}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: body.model || 'stub',
    choices: [{index: 0, message, finish_reason: answer.toolCall ? 'tool_calls' : 'stop'}],
    usage: {
      prompt_tokens: tokens.prompt,
      completion_tokens: tokens.completion,
      total_tokens: tokens.prompt + tokens.completion
    }
  }
}

function ollama(body) {
  const answer = decide(body.messages || [], body.tools)
  const tokens = usage(body.messages || [], answer)
  const message = answer.toolCall
    ? {role: 'assistant', content: '', tool_calls: [{function: answer.toolCall}]}
    : {role: 'assistant', content: answer.content}
  return {
    model: body.model || 'stub',
    created_at: new Date().toISOString(),
    message,
    done: true,
    done_reason: 'stop',
    prompt_eval_count: tokens.prompt,
    eval_count: tokens.completion
  }
}

const server = createServer((request, response) => {
  let raw = ''
  request.on('data', (chunk) => (raw += chunk))
  request.on('end', () => {
    calls++
    let body = {}
    try {
      body = raw ? JSON.parse(raw) : {}
    } catch {
      response.writeHead(400).end('{"error":"invalid JSON"}')
      return
    }
    let payload
    if (request.method === 'POST' && request.url.endsWith('/chat/completions')) payload = openAi(body)
    else if (request.method === 'POST' && request.url === '/api/chat') payload = ollama(body)
    else if (request.url.endsWith('/models') || request.url === '/api/tags') payload = {data: [], models: []}
    else {
      response.writeHead(404, {'Content-Type': 'application/json'}).end('{"error":"not found"}')
      return
    }
    setTimeout(() => {
      response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(payload))
    }, delayMs)
  })
})

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  server.listen(port, '127.0.0.1', () => console.log(`LLM stub listening on http://127.0.0.1:${port}`))
}
