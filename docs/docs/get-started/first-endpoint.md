---
sidebar_position: 3
---

# Your first endpoint

Turn a SQL query on the sample shop into a URL you can call. You need the [quick start](./quick-start.md) running with the
sample data loaded, and to be signed in as **admin**.

## 1. Create a workspace

1. In the sidebar, under **Administration**, open **Workspaces** and press **New workspace**.
2. Enter a **Name** (`Shop`) and a **Slug** (`shop`). The slug is part of every endpoint URL and cannot change later.
3. Keep yourself as **First owner** and press **Create workspace**.

The workspace switcher at the top now shows **Shop**.

## 2. Connect the database

1. Open **Datasources** and press **New datasource**.
2. Fill in the **Connection** group:

   | Setting | Value |
   |---|---|
   | **Name** | `shop` |
   | **Database type** | PostgreSQL |
   | **Host** | `postgres` (the database container's name on the Compose network) |
   | **Port** | leave empty for 5432 |
   | **Database** | `opendbapi` |

3. Under **Credentials**, set **Username** `shop_reader` and **Password** `shop-reader`.
4. Under **Driver properties**, press **Add property** and set `currentSchema` to `shop`, so queries can write
   `customers` instead of `shop.customers`.
5. Keep **Read only** on, press **Test connection**, and when it says **Connected.** press **Create datasource**.

![The shop datasource with its connection settings and a Connected badge](/img/datasources/detail.png)

## 3. Write the endpoint

1. Open **Endpoints** and press **New endpoint**.
2. Name it `Customers`, keep **Method** `GET`, set **Path** to `customers`, choose the `shop` datasource and keep
   **Access** on **Private**. Press **Create and edit query**.
3. Replace the query with:

   ```sql
   select id, name, country, tier from customers
   <where><if test="tier != null">tier = #{tier}</if></where>
   order by id
   ```

   `#{tier}` is a parameter, sent to the database as a bound value, never pasted into the SQL. The `<where>` and `<if>`
   tags add the condition only when a caller passes `tier`.
4. Press **Detect from SQL** next to **Parameters**: a `tier` parameter of type `string` appears.
5. Under **Preview**, type `gold` for `tier` and press **Run**. The two gold customers appear, together with the SQL that
   ran and its bound values.

![The query editor of the Customers endpoint with the tier parameter and the Preview panel](/img/endpoints/query.png)

## 4. Publish

Press **Save**, then **Publish** and **Publish a new version**. The header changes to `Live · v1`.

## 5. Call it

A private endpoint needs a token. Create one for a service account:

1. Open **Clients**, press **New service account**, name it `tutorial` and press **Create**.
2. Under **Grants**, press **Grant access**, choose the `Customers` endpoint and press **Grant**.
3. Under **Clients**, press **New client** and **Create client**. Copy the **Client id** and **Client secret**; the
   secret is shown only once.

Exchange the client for a token, then call the endpoint:

```bash
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials \
  http://localhost:8520/oauth2/token | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8520/api/shop/customers?tier=gold"
```

```json
{
  "data": [
    {"id": 1, "name": "Ada Lovelace", "country": "GB", "tier": "gold"},
    {"id": 2, "name": "Grace Hopper", "country": "US", "tier": "gold"}
  ],
  "meta": {"version": 1, "cached": false, "requestId": "cfbf7475-…"}
}
```

`meta.version` tells you which published version answered. Every call appears under **Activity**.

## Next

- [Write and publish endpoints](../endpoints/write-and-publish.md): the editor, parameters and versions in detail.
- [Calling endpoints](../access/calling-endpoints.md): tokens, the response envelope and errors.
- [Configuration as code](../config/yaml-format.md): keep this workspace in Git.
