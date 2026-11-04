export function registerBrowserIconTests(test, expect) {
  for (const path of ['/', '/bootui/']) {
    test(`serves browser icons for ${path}`, async ({page, request}) => {
      await page.goto(path)
      for (const [file, rel, size, contentType] of [
        ['favicon.ico', 'icon', 32, /image\/(?:x-icon|vnd\.microsoft\.icon)/],
        ['favicon.svg', 'icon', 32, /image\/svg\+xml/],
        ['apple-touch-icon.png', 'apple-touch-icon', 180, /image\/png/],
        ['apple-touch-icon-precomposed.png', 'apple-touch-icon-precomposed', 180, /image\/png/]
      ]) {
        const url = `${path}${file}`
        const link = page.locator(`link[rel="${rel}"][href$="${file}"]`)
        await expect(link).toHaveCount(1)
        expect(await link.evaluate((element) => new URL(element.href).pathname)).toBe(url)
        const response = await request.get(url)
        expect(response.status(), url).toBe(200)
        expect(response.headers()['content-type'], url).toMatch(contentType)
        const dimensions = await page.evaluate(async (src) => {
          const image = new Image()
          image.src = src
          await image.decode()
          return [image.naturalWidth, image.naturalHeight]
        }, url)
        expect(dimensions, url).toEqual([size, size])
      }
    })
  }
}
