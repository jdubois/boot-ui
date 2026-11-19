import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {createRequire} from 'node:module'
import {fileURLToPath, pathToFileURL} from 'node:url'

const root = new URL('../../', import.meta.url)
const frontend = new URL('bootui-ui/src/main/frontend/', root)
const frontendRequire = createRequire(new URL('package.json', frontend))
const browserRequire = createRequire(new URL('bootui-spring-sample-app/e2e/package.json', root))
const {createServer: createViteServer} = await import(pathToFileURL(frontendRequire.resolve('vite')))
const {chromium} = browserRequire('playwright')

const requests = []
const backend = createServer((request, response) => {
  request.resume()
  request.on('end', () => {
    requests.push(request.headers)
    response.setHeader('Content-Type', 'application/json')
    response.end(JSON.stringify(request.headers))
  })
})
await new Promise((resolve) => backend.listen(0, '127.0.0.1', resolve))
const backendOrigin = `http://127.0.0.1:${backend.address().port}`
process.env.BOOTUI_API_PROXY_TARGET = backendOrigin

let vite
let browser
try {
  vite = await createViteServer({
    root: fileURLToPath(frontend),
    configFile: fileURLToPath(new URL('vite.config.js', frontend)),
    server: {host: '127.0.0.1', port: 0},
    plugins: [{
      name: 'proxy-origin-proof',
      configureServer(server) {
        server.middlewares.use((request, response, next) => {
          if (request.url === '/bootui/proxy-proof.html') {
            response.setHeader('Content-Type', 'text/html')
            response.end('<!doctype html><title>Proxy admission proof</title>')
          } else {
            next()
          }
        })
      }
    }]
  })
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({headless: true})
  const page = await browser.newPage()
  await page.goto(`${origin}/bootui/proxy-proof.html`)
  const forwarded = await page.evaluate(async () => {
    const response = await fetch('/bootui/api/proxy-proof', {method: 'POST', body: 'explicit test'})
    return response.json()
  })
  assert.equal(forwarded['sec-fetch-site'], 'same-origin')
  assert.equal(forwarded.origin, origin)
  assert.equal(forwarded.host, new URL(backendOrigin).host)
  assert.notEqual(new URL(forwarded.origin).port, new URL(backendOrigin).port)
  await page.evaluate(async (target) => {
    await fetch(`${target}/bootui/api/direct-proof`, {method: 'POST', body: 'explicit test'}).catch(() => {})
  }, backendOrigin)
  assert.equal(requests.at(-1)['sec-fetch-site'], 'same-site')
  assert.equal(requests.at(-1).origin, origin)
  console.log('PASS: real Chromium + repository Vite proxy preserve same-origin metadata and Origin across an upstream port rewrite; a direct cross-port write is same-site.')
} finally {
  await browser?.close()
  await vite?.close()
  await new Promise((resolve, reject) => backend.close((error) => error ? reject(error) : resolve()))
}
