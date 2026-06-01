# BootUI website

The documentation and marketing website for BootUI, built with
[VitePress](https://vitepress.dev/) and published to GitHub Pages at
**<https://jdubois.github.io/boot-ui/>**.

## Why VitePress?

BootUI's own console is a Vue 3 + Vite application, so VitePress keeps the whole
project on a single frontend stack. The site is themed to echo the console's
identity (Spring green, glassmorphism, rounded surfaces) and reuses the existing
Markdown content and screenshots from the repository's `docs/` folder.

## Content

The site reuses a single source of truth:

- Screenshots are copied from `../docs/images` into `public/images` at build
  time by `scripts/copy-assets.mjs` (run automatically before `dev` and
  `build`). They are not committed under `website/`.
- Page content mirrors the root `README.md`, `docs/FEATURES.md`, and
  `docs/PROPERTIES.md`.

## Local development

```bash
cd website
npm install
npm run dev      # start the dev server (copies screenshots first)
npm run build    # build the static site into .vitepress/dist
npm run preview  # preview the production build
```

## Deployment

A GitHub Actions workflow (`.github/workflows/website.yml`) builds the site and
deploys it to GitHub Pages on pushes to `main` that touch `website/**` or
`docs/images/**`. The `base` is set to `/boot-ui/` so assets resolve correctly
under the project-site URL.

To enable publishing, set **Settings → Pages → Build and deployment → Source**
to **GitHub Actions** for the repository.
