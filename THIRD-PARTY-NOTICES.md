# Third-party notices

## Herdr app

`src/main/resources/herdr/programs.json` and the icons in `src/main/resources/herdr/icons/` come from
[herdr-app](https://github.com/herdrdev/herdr-app) (`defaults/`, commit `7a449a3`), Apache License 2.0.
See `src/main/resources/herdr/LICENSE-herdr-app.txt`.

The SVG files were changed: the root `width`, `height`, `style` and `class` attributes were replaced
with `width="16" height="16"` so that IntelliJ loads them at icon size.

The icons themselves come from these sources (from `herdr-app/defaults/icons/LICENSES.md`):

- Brand icons: [Simple Icons](https://simpleicons.org), CC0 1.0.
- Glyphs (terminal, bot, search, ...): [Lucide](https://lucide.dev), ISC, with MIT-licensed
  Feather-derived icons. See `src/main/resources/herdr/icons/LICENSE-Lucide.txt`.
- Agent icons: [LobeHub icons](https://github.com/lobehub/lobe-icons) (MIT) and the agents' own
  marks. See `src/main/resources/herdr/icons/LICENSE-LobeHub.txt`.

Brands are their owners' trademarks. These licenses do not grant trademark rights.
