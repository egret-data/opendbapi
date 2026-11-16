import type { ProviderView, SaveProvider } from "@/api/generated/model";

export type ProviderType = "ldap" | "oidc";

/** One editable setting of a provider: label, help, and whether it is required. */
export type Field = {
  key: string;
  label: string;
  details: string;
  required?: boolean;
  kind?: "text" | "boolean" | "list";
  placeholder?: string;
};

export const FIELDS: Record<ProviderType, Field[]> = {
  ldap: [
    { key: "url", label: "Server URL", details: "ldaps://host:636, or ldap:// with StartTLS", required: true, placeholder: "ldaps://ad.corp.local:636" },
    { key: "startTls", label: "StartTLS", details: "Upgrade an ldap:// connection to TLS", kind: "boolean" },
    { key: "allowInsecure", label: "Allow unencrypted", details: "Plain ldap:// without TLS. Development only: passwords cross the network in clear text.", kind: "boolean" },
    { key: "bindDn", label: "Service account DN", details: "Account used to search for users and groups", placeholder: "cn=svc-opendbapi,ou=svc,dc=corp,dc=local" },
    { key: "userSearchBase", label: "User search base", details: "Where users live", placeholder: "ou=users,dc=corp,dc=local" },
    { key: "userSearchFilter", label: "User search filter", details: "{0} is the username typed at sign-in", required: true, placeholder: "(sAMAccountName={0})" },
    { key: "groupSearchBase", label: "Group search base", details: "Where groups live; leave empty to read groups from a user attribute instead", placeholder: "ou=groups,dc=corp,dc=local" },
    { key: "groupSearchFilter", label: "Group search filter", details: "{0} is the user's DN, {1} the username", placeholder: "(member={0})" },
    { key: "groupsAttribute", label: "Groups attribute", details: "User attribute listing groups, such as memberOf in Active Directory", placeholder: "memberOf" },
    { key: "displayNameAttribute", label: "Display name attribute", details: "Defaults to cn", placeholder: "cn" },
    { key: "emailAttribute", label: "Email attribute", details: "Defaults to mail", placeholder: "mail" },
  ],
  oidc: [
    { key: "issuerUri", label: "Issuer URL", details: "Its /.well-known/openid-configuration is read automatically", required: true, placeholder: "https://sso.corp.local/realms/main" },
    { key: "clientId", label: "Client ID", details: "The client registered for OpenDBAPI at the identity provider", required: true },
    { key: "scopes", label: "Scopes", details: "Comma separated; defaults to openid, profile, email", kind: "list", placeholder: "openid, profile, email" },
    { key: "usernameClaim", label: "Username claim", details: "Defaults to preferred_username", placeholder: "preferred_username" },
    { key: "groupsClaim", label: "Groups claim", details: "Claim holding the user's groups; defaults to groups", placeholder: "groups" },
  ],
};

export const SECRET_LABEL: Record<ProviderType, string> = {
  ldap: "Service account password",
  oidc: "Client secret",
};

export type FormState = {
  type: ProviderType;
  name: string;
  enabled: boolean;
  values: Record<string, string | boolean>;
  /** Empty: keep the stored secret. */
  secret: string;
};

export function emptyForm(type: ProviderType): FormState {
  return { type, name: "", enabled: true, values: {}, secret: "" };
}

export function formFrom(p: ProviderView): FormState {
  const values: Record<string, string | boolean> = {};
  for (const field of FIELDS[p.type]) {
    const raw = p.config[field.key];
    if (field.kind === "boolean") values[field.key] = raw === true;
    else if (field.kind === "list" && Array.isArray(raw)) values[field.key] = raw.join(", ");
    else if (raw != null) values[field.key] = String(raw);
  }
  return { type: p.type, name: p.name, enabled: p.enabled, values, secret: "" };
}

export function toRequest(form: FormState): SaveProvider {
  const config: Record<string, unknown> = {};
  for (const field of FIELDS[form.type]) {
    const value = form.values[field.key];
    if (field.kind === "boolean") {
      if (value === true) config[field.key] = true;
    } else if (field.kind === "list") {
      const items = String(value ?? "").split(",").map((s) => s.trim()).filter(Boolean);
      if (items.length) config[field.key] = items;
    } else if (typeof value === "string" && value.trim()) {
      config[field.key] = value.trim();
    }
  }
  return { type: form.type, name: form.name.trim(), enabled: form.enabled, config, secret: form.secret || null };
}
