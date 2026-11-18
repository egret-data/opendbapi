---
slug: /
sidebar_position: 0
title: Introduction
---

# OpenDBAPI

OpenDBAPI turns SQL into REST APIs. You write a query, declare the parameters callers may pass, and publish it. Any
program with an OAuth2 token can then call it over HTTP and get JSON back. There is no service to write or deploy, and
no database credentials to hand out.

![The endpoints list of the demo workspace: published endpoints with their method, path and state](/img/get-started/endpoints-list.png)

Use it when a program needs the answer to a query that you control: a nightly job, a partner's backend, a mobile app
or a spreadsheet macro.

## What you get

- Endpoints written as SQL templates with typed parameters, validation, pagination, result shaping and caching. An
  endpoint can also be a Groovy script or a workflow of several steps.
- Publishing in versions. You edit a draft and publish numbered versions, which you can roll back. Saved tests run
  before publishing, and major versions can run side by side with deprecation headers.
- Access control. Workspaces are isolated from one another, the people who write endpoints have roles, and the
  programs that call them use OAuth2 client credentials. Request signing and response encryption are optional.
- Data policies that mask columns and filter rows by who is calling.
- Operations: an activity view of every call, alerts to Slack or webhooks, an audit log, Prometheus metrics and
  tracing.
- Configuration as code. Export a workspace as YAML, keep it in Git, and `plan` / `apply` it to other environments
  with the `opendbapi` command line.

## Where to start

- [Quick start](./get-started/quick-start.md): run OpenDBAPI and a sample database with Docker Compose.
- [Concepts](./get-started/concepts.md): workspaces, endpoints, versions, clients and grants in one page.
- [Your first endpoint](./get-started/first-endpoint.md): from a SQL query to a URL you can call.
- [Deploy with Docker](./deploy/docker.md) or [on Kubernetes](./deploy/kubernetes.md) when you are ready to run it for real.

OpenDBAPI is open source (Apache-2.0) at [github.com/egret-data/opendbapi](https://github.com/egret-data/opendbapi).
