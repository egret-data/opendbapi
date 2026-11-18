---
sidebar_position: 4
---

# Known issues

Problems in the current release (0.3.0) and how to work around them.

## `timestamptz` values lose their offset

A PostgreSQL `timestamp with time zone` column is returned as local date-time text without the offset, for example
`"2026-10-06T00:00"`. Clients cannot tell which time zone it is in.

To work around it, convert the value in SQL, for example `to_char(placed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')`, or give
the column the **Epoch milliseconds** [value type](../endpoints/settings.md#output).

## **Publish** is enabled when there is nothing to publish

When the saved draft equals the live version, **Publish** stays enabled and pressing it shows a
`409 nothing_to_publish` error.

No workaround is needed. The live version stays unchanged.
