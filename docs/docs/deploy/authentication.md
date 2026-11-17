---
sidebar_position: 3
---

# Sign-in: local, LDAP and SSO

People sign in to the management UI with a **local account**, through an **LDAP / Active Directory** directory, or
through **SSO (OpenID Connect)** such as Keycloak, Entra ID, Okta or Google. Programs that call endpoints use
[clients](../access/clients.md), not these accounts.

## Local accounts

On first start OpenDBAPI creates the system admin `admin` with the password `OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD` (or a
random one printed once in the log). System admins create more local users under **Administration → Users → New local
user**, and can reset passwords and disable users there.

After 5 wrong passwords in a row an account is locked for 15 minutes (`opendbapi.auth.lockout-attempts`,
`opendbapi.auth.lockout-duration`); resetting the password clears the lock. Hide local sign-in with
`opendbapi.auth.local-enabled=false` once SSO works; system admins can still sign in locally, as a way in when the
identity provider is down.

Users change their own password from the user menu → **Change password**.

## Add an identity provider

1. Under **Administration → Identity providers**, press **New provider**.
2. Choose the **Provider type**, give it a **Name** (shown on the sign-in page), fill in the settings below and press
   **Create provider**.
3. Use **Test the connection** (LDAP can also try a **Test username (optional)**).
4. Map groups to roles on the **Group mappings** tab.

### LDAP / Active Directory

| Setting | Example |
|---|---|
| **Server URL** | `ldaps://ldap.example.com:636` |
| **StartTLS** / **Allow unencrypted** | Use StartTLS on `ldap://`; allow unencrypted only for tests |
| **Service account DN** and **Password** | `cn=opendbapi,ou=services,dc=example,dc=com` |
| **User search base** / **User search filter** | `ou=users,dc=example,dc=com` / `(uid={0})` (Active Directory: `(sAMAccountName={0})`) |
| **Group search base** / **Group search filter** | `ou=groups,dc=example,dc=com` / `(member={0})` |
| **Email attribute**, **Display name attribute** | `mail`, `cn` |

### SSO (OpenID Connect)

| Setting | Example |
|---|---|
| **Issuer URL** | `https://keycloak.example.com/realms/acme` |
| **Client ID** and secret | From the identity provider |
| **Scopes** | `openid profile email` |
| **Username claim** | `preferred_username` |
| **Groups claim** | `groups` |

Register the redirect URI the page shows at the identity provider:

```text
https://opendbapi.example.com/login/oauth2/code/{provider id}
```

Secrets can be typed as `env:NAME` to read them from the server's environment, or `vault:PATH#KEY` under the Vault
system paths; otherwise they are stored encrypted.

## Map groups to roles

On **Group mappings**, press **Add mapping** and map a **Group** to a workspace **Role**, or to **System admin**. At every
sign-in OpenDBAPI recomputes the user's roles from their groups:

- a workspace their groups map to gets the highest mapped role;
- a membership that came from a group they left is removed;
- roles granted by hand are kept, unless a mapping for that workspace is **Managed**: then the mappings alone decide;
- the system-admin flag follows the mappings only when the provider has a **System admin** mapping.

Group names compare case-insensitively. Changes apply at each user's next sign-in.
