---
sidebar_position: 5
---

# Activity and audit log

**Activity** shows the calls to the workspace's published endpoints. The **audit log** records who changed what.

## Activity

Open **Activity** in the sidebar.

![The Activity page: requests, error rate, p95 latency, active clients and requests per day](/img/activity/activity.png)

- The tiles show **Requests**, **Error rate**, **p95 latency** and **Active clients** for the chosen period: **Last 24 hours**, **Last 7 days** or
  **Last 30 days**.
- **Top endpoints**, **Top errors**, **Slowest endpoints**, **Top clients** and **Top addresses** show where calls and
  problems come from.
- **Calls** lists each call with its status, client, duration, rows and error code. Filter by endpoint or status, or
  paste a **request id** from an error to find that call.
- **Include Try-it** adds calls made from the endpoint editor's **Try it** tab.

Calls are kept for 30 days (`opendbapi.access-log.retention-days`). Query strings and request bodies are not stored.

## Audit log

**Settings → Audit log** lists every change in the workspace: endpoints saved, published and rolled back, datasources,
service accounts, clients and grants, members, secrets, policies and configuration applies, with who did it, when and
from which address. It is for workspace admins.

System admins also have **Administration → Audit log**: sign-ins and every change to workspaces, users, members and
identity providers.
