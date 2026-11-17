---
sidebar_position: 4
---

# Data policies

Data policies change what a caller sees without changing the query: **masks** hide part of a value, **row filters** keep
only the rows a caller may see.

![The Masking tab of the shop datasource: customers.email masked as an email for everyone](/img/access/data-policies.png)

## Masks

A mask replaces a column's values in every answer:

| Mask | Result |
|---|---|
| **Email** | `ada@example.com` → `a***@example.com` |
| **Phone** | `0901234123` → `090****123` |
| **Keep the ends** | Keeps **Keep at start** characters and **Keep at end** characters and masks the rest |
| **Hash** | Salted SHA-256: the same input always gives the same output, so callers can still join and group |
| **Always null** | `null` |

Set a mask in one of two places:

- **On a datasource** (**Datasources →** the datasource **→ Masking → Add mask**): name a **Table column** such as
  `customers.email` or `shop.customers.email`. Every endpoint on the datasource masks it, wherever it appears in a result
  and under any alias, because OpenDBAPI traces each result column back to its table column.
- **On an endpoint** (the endpoint's **Access** tab): name a **Result column** as the endpoint returns it.

**Not applied to** lists service accounts the mask skips, for example a support tool that needs real emails.

:::note
When the SQL cannot be parsed to trace columns (vendor-specific syntax), datasource masks apply by result column name
only, and validation warns about it. Alias masked columns with their own name in such queries.
:::

## Row filters

A row filter, set on an endpoint's **Access** tab, keeps only rows whose column equals an
[attribute](./clients.md#attributes) of the calling service account. For example, a row filter on `country` with the
attribute `country` gives the `reporting` account, whose attribute is `country = VN`, only Vietnamese customers. A list
attribute (`regions = north,south`) allows several values.

- A caller without the attribute is refused with `403 missing_attribute`.
- A row filter needs to know the caller, so the endpoint must be **Private**.
- The column must be one the query returns, and the filtered query must be accepted by the database; validation checks
  both.

OpenDBAPI applies the filter by wrapping the endpoint's query, before pagination, so pages and counts only see allowed
rows.

## Where policies apply

Data policies apply to every way an endpoint answers: normal calls, pages, streams, cached answers (which are kept per
caller when policies differ by caller), every query of a [full-mode script](../endpoints/groovy-scripts.md#full-mode) or
[workflow](../endpoints/workflows.md), and tests that run as a service account. Preview runs without a caller, so it shows
masks configured for everyone but not row filters.

Changing a policy clears the workspace's cached answers on every server instance.
