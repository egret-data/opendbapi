---
slug: /
sidebar_position: 0
title: Introduction
---

# OpenDBAPI

OpenDBAPI turns SQL into REST APIs. You write a query, declare the parameters callers may pass, and publish it; any
program with an OAuth2 token can then call it over HTTP and get JSON back. You do not write a service, deploy anything
or hand out database credentials.

![The endpoints list of the demo workspace: published endpoints with their method, path and state](/img/get-started/endpoints-list.png)

Use it when something that is not a person — a nightly job, a partner's backend, a mobile app, a spreadsheet macro —
needs the answer to a query that you control.

## What you get

- **Endpoints from SQL**: templates with typed parameters, validation, pagination, result shaping and caching; or a
  Groovy script, or a workflow of several steps.
- **Safe publishing**: a draft you edit and numbered versions you publish and roll back; saved tests that run before
  publishing; side-by-side major versions with deprecation headers.
- **Access control**: workspaces isolated from one another, roles for the people who write endpoints, OAuth2 client
  credentials for the programs that call them, optional request signing and response encryption.
- **Data policies**: mask columns and filter rows by who is calling.
- **Operations**: an activity view of every call, alerts to Slack or webhooks, an audit log, Prometheus metrics and
  tracing.
- **Configuration as code**: export a workspace as YAML, keep it in Git, and `plan` / `apply` it to other environments
  with the `opendbapi` command line.

## Where to start

- [Quick start](./get-started/quick-start.md): run OpenDBAPI and a sample database with Docker Compose.
- [Concepts](./get-started/concepts.md): workspaces, endpoints, versions, clients and grants in one page.
- [Your first endpoint](./get-started/first-endpoint.md): from a SQL query to a URL you can call.
- [Deploy with Docker](./deploy/docker.md) or [on Kubernetes](./deploy/kubernetes.md) when you are ready to run it for real.

OpenDBAPI is open source (Apache-2.0) at [github.com/egret-data/opendbapi](https://github.com/egret-data/opendbapi).
