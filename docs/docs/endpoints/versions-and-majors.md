---
sidebar_position: 4
---

# Major versions and deprecation

Publishing a new version replaces what callers get at the same URL. When a change would break callers — a column
removed, a type changed — publish it as a **new major version** at a new path instead, keep the old one running, and
announce its end with **deprecation**.

![The Versions tab with the Major versions table: v1 deprecated with a sunset date, v2 live](/img/endpoints/versions.png)

## Breaking-change warning

When you validate or publish, OpenDBAPI compares the draft's result columns with the live version's. A dropped column
or a changed type shows a `breaking_change` warning, for example:

```text
Compared with live version 3, this drops column 'email'; callers may break. Consider a new major version instead.
```

The warning does not block publishing.

## Create a new major version

1. On the endpoint, open the actions menu next to **Publish** and choose **New major version…**.
2. Leave **Path of the new version** empty to use `v2/…` (`v1/orders` becomes `v2/orders`, `orders` becomes
   `v2/orders`), or type another path.
3. Press **Create draft**.

The new endpoint is a draft copy named `… v2`, in the same **lineage**. Change it, publish it, and both run side by side.
They are separate endpoints: grants, tests and data policies are set per endpoint. The **Versions** tab lists the majors
of the lineage once there are two.

## Deprecate the old version

1. On the old endpoint, choose **Deprecate…** in the actions menu.
2. Optionally set a **Sunset date (optional)**: when it will stop answering.
3. Press **Deprecate**.

The endpoint keeps answering, but every response now carries:

```text
Deprecation: @1794930769
Sunset: Wed, 30 Jun 2027 00:00:00 GMT
Link: </api/demo/v2/customers>; rel="successor-version"
```

- `Deprecation` (RFC 9745) is the time it was deprecated, as `@` and seconds since 1970.
- `Sunset` (RFC 8594) is there when you set a date.
- `Link` points to the newest live major of the lineage.

The endpoint is also marked `deprecated` in the workspace's OpenAPI document. **Stop deprecation** removes the headers.

:::note
OpenDBAPI does not stop serving at the sunset date. Disable the endpoint yourself when callers have moved.
:::

## In configuration files

Lineage and deprecation travel in an endpoint's YAML metadata: `majorOf` names the lineage's first major by its route,
so it works in every environment.

```yaml
metadata:
  name: Customers v2
  majorOf: GET /v1/customers
  major: 2
---
metadata:
  name: Customers
  deprecated: true
  sunset: 2027-06-30T00:00:00Z
```
