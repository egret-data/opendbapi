---
sidebar_position: 2
---

# Concepts

The handful of things OpenDBAPI is made of, and how they fit together.

## Workspace

A workspace is a team's space: its own datasources, endpoints, service accounts, secrets, policies and activity.
Nothing is shared between workspaces, and PostgreSQL row-level security keeps one workspace's data out of another's
queries. The workspace's **slug** is part of every endpoint URL:

```text
https://opendbapi.example.com/api/{workspace}/{path}
```

People join a workspace as **members** with a role; see [Members and roles](../workspace/members-and-roles.md).

## Datasource

The database an endpoint queries: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle and other JDBC databases. A datasource
holds the connection settings and a password (encrypted, or a reference to a [secret](../workspace/secrets.md)). Mark it
**read only** when its endpoints should never change data. See [Datasources](../datasources/datasources.md).

## Endpoint

A query plus its URL, parameters, response shape, cache and limits. An endpoint has a **draft** you edit and numbered,
frozen **versions** you publish:

| State | Meaning | Answers calls |
|---|---|---|
| **Draft** | Created, never published | No |
| **Live** | A version is published. The header shows `Live · v2`, and **Unpublished changes** when the draft is ahead | Yes |
| **Disabled** | Taken out of service; keeps its live version and grants, so **Enable** brings it back as it was | No |
| **In the trash** | Soft-deleted; restore it to edit it again | No |

Callers only ever see a published version; saving the draft changes nothing for them until you press **Publish**.
See [Endpoints](../endpoints/write-and-publish.md).

## Service account, client and grant

Programs call **private** endpoints with a token. Three things decide who may call what:

- A **service account** is a machine identity, such as `reporting` or `partner-acme`.
- A **client** is a `client_id` and `client_secret` that belongs to a service account. A program exchanges them for a
  short-lived token at `POST /oauth2/token`.
- A **grant** lets a service account call one endpoint, or every endpoint of a group. Without one, its calls get
  `403 not_granted`.

**Public** endpoints need no token and answer anyone who can reach the server. See
[Clients and tokens](../access/clients.md).

## Data policies

Rules that change what callers see, per column or per row: **masks** (show `a***@example.com` instead of an email) and
**row filters** (a service account only sees the rows of its own country). See [Data policies](../access/data-policies.md).

## Configuration as code

A workspace exports as YAML documents that refer to each other by name. Keep the file in Git and apply it to another
workspace or installation; an **environment** maps the file's datasource names to that workspace's datasources. See
[Configuration as code](../config/yaml-format.md).
