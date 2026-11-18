# Documentation style guide

How the OpenDBAPI docs (this site) are written. The model is the [Metabase docs](https://www.metabase.com/docs/latest/):
one page per feature, task-oriented, every setting named exactly as it appears in the product.

`docs/SPEC.md` is the design specification for contributors; these pages are for people who run and use OpenDBAPI.

## Principles

1. **One page per feature.** A page covers one thing a person can do or configure. Split a page that grows past ~3 000
   words or covers two unrelated tasks.
2. **The product is the source of truth.** Every label, option, default and limit comes from the running app or the source
   code. Never guess; if you cannot verify something, leave it out.
3. **Write for the person doing the task.** Start with what it is and when to use it, then how.
4. **Name UI elements exactly**, in **bold**, with the UI's capitalisation: **Publish**, **New endpoint**. Navigation paths
   use arrows: **Settings → Secrets**.
5. **Show, don't just tell.** Screenshots from the seeded demo instance; small runnable examples (SQL, YAML, curl).
6. **Be honest about limits.** State unfinished or surprising behaviour plainly in a `:::note` or `:::warning`.

## Page template

```markdown
---
sidebar_position: 3
---

# Page title (the feature, as a noun or "Verb the thing")

One or two sentences: what this is and when you would use it.

![Alt text that describes the screenshot](/img/<area>/<id>.png)

## How to <do the main task>

1. Open **Endpoints** and press **New endpoint**.
2. …

## <Settings group, one H2 per tab or panel section, in UI order>

### <Setting name exactly as in the UI>

What it does, the options (a table when there are 3+), the default, and any limit.

## Limits

Only what is true and verified.

## Further reading

- [Related page](./other-page)
```

- **Settings pages mirror the panel**: one H2 per tab or section, settings in the order the UI shows them.
- **Tables** for option lists with 3+ items (`Option | What it does | Default`).
- **Numbered steps** for procedures (one action per step), bullets for options.
- **Admonitions**: `:::tip`, `:::note`, `:::warning`; at most two per page.
- **Code blocks** always name their language (`sql`, `yaml`, `bash`, `json`, `groovy`, `text`).
- Second person, present tense, plain words. No marketing language, no "simply" or "just".
- Sentence case headings without trailing punctuation.

## Writing plainly

Write what the reader needs to do or know, and stop. Before merging a page, read it once for these habits and remove
them:

- **Contrasts that only add weight.** "It's not X, it's Y" or "X, not Y" when nobody believed X. State Y.
- **Closers and run-ups.** A last line that repeats the paragraph; an opener such as "Here's what you need to know".
- **Dashes as connectors.** No em dash (—) or en dash (–) in prose: use a period, comma, colon or parentheses. A dash
  in a table cell meaning "none" is fine.
- **Inflated words.** crucial, robust, seamless, powerful, leverage, effortlessly, simply, just. Say what happens.
- **Bold as decoration.** Bold is for UI labels. Lists of "**Label:** sentence" become plain sentences or a table.
- **Triads and stacked hedges.** Three items only when there are three; one qualifier at most.
- **Talking about the page.** "This page explains…", "The table below shows…". Start with the content.

## Screenshots

- Taken from a seeded local instance only; never capture real hosts, emails, tokens or secrets.
- 1440×900, light theme, cropped to the relevant region when the full page adds noise.
- File location `static/img/<area>/<id>.png`, referenced as `/img/<area>/<id>.png`.
- Every screenshot is produced by an entry in `scripts/shots/<area>.mjs` (`npm run shots`), so it can be re-captured.
- Alt text describes what the image shows. Check every image after capture: data loaded, dialog open, no spinners.

## Linking

- Relative links without `.md`, e.g. `[Clients](../access/clients)`. The build fails on broken links.
- Do not rename or delete a published page path; add pages next to it instead.

## Generated pages

- `docs/reference/admin-api.md` is generated from `openapi.json` by `npm run gen:api`. Do not edit it by hand.
