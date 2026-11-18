---
sidebar_position: 4
---

# Import from DBAPI

Move APIs from DBAPI into an OpenDBAPI workspace using the files DBAPI exports.

1. In DBAPI, export what you want to move:

   | File | Where in DBAPI |
   |---|---|
   | `api_config.json` | API list → **Export API** |
   | `group.json` | **Export API groups** |
   | `datasource.json` | **Export datasources** |

2. In OpenDBAPI, open **Settings → Import & export** and, under **Import from DBAPI**, choose the files.
3. Press **Check**. The **Import report** lists what would be created, skipped, and anything that does not carry over.
   Nothing is written yet.
4. Press **Import**.

What happens to each kind:

- APIs arrive as endpoint drafts: review, test and publish each one.
- Groups are matched by name and created when missing.
- Datasources are recreated from their JDBC URL, read only and without passwords. Set each password (or a
  [secret](../workspace/secrets.md)) before testing.

DBAPI features OpenDBAPI does not carry over (plugins, parameter rules, non-SQL executors, `${}` text substitution) are
listed in the report, so nothing is dropped silently. Rewrite `${}` as `#{}` bound parameters or an `<orderBy>` tag (see
[Write and publish endpoints](../endpoints/write-and-publish.md#conditional-sql)).
