---
sidebar_position: 4
---

# Known issues

Problems in the current release (0.2.0) and how to work around them.

## `timestamptz` values lose their offset

A PostgreSQL `timestamp with time zone` column is returned as local date-time text without the offset, for example
`"2026-10-06T00:00"`. Clients cannot tell which time zone it is in.

**Work around**: convert it in SQL, e.g. `to_char(placed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')`, or give
the column the **Epoch milliseconds** [value type](../endpoints/settings.md#output).

## **Publish** is enabled when there is nothing to publish

When the saved draft equals the live version, **Publish** stays enabled and pressing it shows a
`409 nothing_to_publish` error.

**Work around**: none needed; the live version is unchanged.

## Reloading the API tokens page shows "not found"

Opening `/account/tokens` directly, or reloading it, answers 404 in 0.2.0.

**Work around**: open it from the user menu → **API tokens**. Fixed in the next release.
