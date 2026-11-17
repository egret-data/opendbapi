package io.opendbapi.core.endpoint;

import io.opendbapi.core.endpoint.EndpointDefinition.Rule;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.sql.expr.Expression;
import io.opendbapi.sql.template.TemplateException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parameter rules (spec §13.1): conditions over the bound parameters, written in the same
 * expression language as {@code <if test>}, checked before any SQL runs. A rule that cannot be
 * evaluated counts as failed.
 */
public final class ParamRules {

    public static final int MAX_RULES = 20;
    private static final int MAX_TEST = 500;
    private static final int MAX_MESSAGE = 300;
    private static final Map<String, Expression> COMPILED = new ConcurrentHashMap<>();

    private ParamRules() {
    }

    /** Problems for the validator; empty when the rules are fine. */
    static void check(List<Rule> rules, Set<String> declared, List<EndpointValidator.Finding> findings) {
        if (rules.size() > MAX_RULES) {
            findings.add(EndpointValidator.Finding.error("rule_invalid", "At most %d rules.".formatted(MAX_RULES)));
        }
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            String label = "Rule " + (i + 1);
            if (r.test() == null || r.test().isBlank() || r.test().length() > MAX_TEST) {
                findings.add(EndpointValidator.Finding.error("rule_invalid",
                        "%s needs a condition of at most %d characters.".formatted(label, MAX_TEST)));
                continue;
            }
            if (r.message() == null || r.message().isBlank() || r.message().length() > MAX_MESSAGE) {
                findings.add(EndpointValidator.Finding.error("rule_invalid",
                        "%s needs a message for callers, at most %d characters.".formatted(label, MAX_MESSAGE)));
            }
            Expression e;
            try {
                e = compile(r.test());
            } catch (TemplateException ex) {
                findings.add(EndpointValidator.Finding.error("rule_invalid", "%s: %s".formatted(label, ex.getMessage())));
                continue;
            }
            for (String name : e.rootVariables()) {
                if (!declared.contains(name)) {
                    findings.add(EndpointValidator.Finding.error("rule_invalid",
                            "%s reads '%s', which is not a declared parameter.".formatted(label, name)));
                }
            }
        }
    }

    /** @throws ApiProblem 400 {@code rule_failed} with the first failing rule's message */
    public static void enforce(List<Rule> rules, Map<String, Object> params) {
        for (Rule r : rules) {
            boolean ok;
            try {
                ok = compile(r.test()).test(params::get);
            } catch (TemplateException | ArithmeticException e) {
                ok = false;
            }
            if (!ok) {
                throw ApiProblem.badRequest("rule_failed", r.message());
            }
        }
    }

    private static Expression compile(String test) {
        Expression cached = COMPILED.get(test);
        if (cached != null) {
            return cached;
        }
        Expression e = Expression.parse(test);
        if (COMPILED.size() < 10_000) {
            COMPILED.put(test, e);
        }
        return e;
    }
}
