package io.opendbapi.admin.auth;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.IdentityProvider;
import io.opendbapi.core.secret.SecretResolver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Service;

/**
 * LDAP / Active Directory sign-in (spec §8.5): bind as the service account, find the user
 * with {@code userSearchFilter}, bind as the user with their password, then read groups
 * from a group search ({@code groupSearchFilter}, {0} = user DN, {1} = username) and/or a
 * user attribute such as AD's {@code memberOf}.
 *
 * <p>Config keys: {@code url}, {@code bindDn}, {@code userSearchBase}, {@code userSearchFilter},
 * {@code groupSearchBase}, {@code groupSearchFilter}, {@code groupNameAttribute} (default cn),
 * {@code groupsAttribute}, {@code emailAttribute} (mail), {@code displayNameAttribute} (cn),
 * {@code allowInsecure} (permit plain ldap:// — development only). Bind password: the
 * provider's secret reference.
 */
@Service
public class LdapLogin {

    private final SecretResolver secrets;

    public LdapLogin(SecretResolver secrets) {
        this.secrets = secrets;
    }

    public ExternalSignIn.Identity authenticate(IdentityProvider idp, String username, String password) {
        return authenticate(idp, username, password, false);
    }

    /** {@code verbose}: include the directory's own error (admin test only, never on the sign-in page). */
    private ExternalSignIn.Identity authenticate(IdentityProvider idp, String username, String password, boolean verbose) {
        LdapContextSource context = contextSource(idp);
        BindAuthenticator authenticator = new BindAuthenticator(context);
        authenticator.setUserSearch(new FilterBasedLdapUserSearch(
                idp.configText("userSearchBase", ""), required(idp, "userSearchFilter"), context));
        DirContextOperations user;
        try {
            user = authenticator.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            throw LocalLogin.invalid();
        } catch (RuntimeException e) {
            throw unavailable(idp, e, verbose);
        }
        String dn = user.getNameInNamespace();
        return new ExternalSignIn.Identity(
                dn,
                username,
                user.getStringAttribute(idp.configText("displayNameAttribute", "cn")),
                user.getStringAttribute(idp.configText("emailAttribute", "mail")),
                groups(idp, context, user, dn, username));
    }

    /** Binds as the service account and, when given, signs a test user in. Returns what was found. */
    public List<String> test(IdentityProvider idp, String username, String password) {
        LdapContextSource context = contextSource(idp);
        List<String> report = new ArrayList<>();
        try {
            context.getReadOnlyContext().close();
            report.add("Service account bind succeeded.");
        } catch (Exception e) {
            throw unavailable(idp, e, true);
        }
        if (username != null && !username.isBlank()) {
            ExternalSignIn.Identity identity = authenticate(idp, username, password, true);
            report.add("User found: " + identity.externalId());
            report.add("Groups: " + (identity.groups().isEmpty() ? "(none)" : String.join(", ", identity.groups())));
        }
        return report;
    }

    private Set<String> groups(IdentityProvider idp, LdapContextSource context, DirContextOperations user,
                               String dn, String username) {
        Set<String> groups = new LinkedHashSet<>();
        String groupSearchFilter = idp.configText("groupSearchFilter", null);
        if (groupSearchFilter != null) {
            SpringSecurityLdapTemplate template = new SpringSecurityLdapTemplate(context);
            groups.addAll(template.searchForSingleAttributeValues(
                    idp.configText("groupSearchBase", ""), groupSearchFilter,
                    new String[] {dn, username}, idp.configText("groupNameAttribute", "cn")));
        }
        String groupsAttribute = idp.configText("groupsAttribute", null);
        if (groupsAttribute != null) {
            String[] values = user.getStringAttributes(groupsAttribute);
            if (values != null) {
                for (String value : values) {
                    groups.add(firstRdnValue(value));
                }
            }
        }
        return groups;
    }

    private LdapContextSource contextSource(IdentityProvider idp) {
        String url = required(idp, "url");
        boolean secure = url.startsWith("ldaps://") || Boolean.TRUE.equals(idp.config().path("startTls").asBoolean(false));
        if (!secure && !idp.config().path("allowInsecure").asBoolean(false)) {
            throw ApiProblem.badRequest("insecure_ldap",
                    "LDAP provider '%s' uses an unencrypted URL. Use ldaps://, or set allowInsecure for development only."
                            .formatted(idp.name()));
        }
        DefaultSpringSecurityContextSource source = new DefaultSpringSecurityContextSource(url);
        String bindDn = idp.configText("bindDn", null);
        if (bindDn != null) {
            source.setUserDn(bindDn);
            source.setPassword(secrets.resolve(idp.secretRef()));
        }
        source.setPooled(false);
        source.afterPropertiesSet();
        return source;
    }

    private static String required(IdentityProvider idp, String field) {
        String value = idp.configText(field, null);
        if (value == null) {
            throw ApiProblem.badRequest("idp_misconfigured",
                    "LDAP provider '%s' has no %s configured.".formatted(idp.name(), field));
        }
        return value;
    }

    /** {@code CN=Sales Authors,OU=Groups,DC=corp} → {@code Sales Authors}; a plain name stays as is. */
    static String firstRdnValue(String value) {
        try {
            List<Rdn> rdns = new LdapName(value).getRdns();
            return rdns.isEmpty() ? value : String.valueOf(rdns.get(rdns.size() - 1).getValue());
        } catch (InvalidNameException e) {
            return value;
        }
    }

    private static ApiProblem unavailable(IdentityProvider idp, Exception e, boolean verbose) {
        if (!verbose) {
            return new ApiProblem(503, "directory_unavailable",
                    "Sign-in through '%s' is unavailable right now. Try again later or contact an administrator."
                            .formatted(idp.name()));
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return new ApiProblem(503, "directory_unavailable",
                "Cannot reach directory '%s': %s".formatted(idp.name(), root.getMessage()));
    }
}
