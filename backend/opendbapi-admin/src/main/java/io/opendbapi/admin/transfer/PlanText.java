package io.opendbapi.admin.transfer;

import io.opendbapi.core.transfer.ConfigPlanner;

/** Plans and apply results as text for the terminal, in the spirit of {@code terraform plan}. */
final class PlanText {

    private PlanText() {
    }

    static String plan(String ws, ConfigPlanner.ConfigPlan plan) {
        StringBuilder out = new StringBuilder();
        out.append("Workspace ").append(ws);
        if (plan.environment() != null) {
            out.append(", environment ").append(plan.environment());
        }
        out.append(plan.prune() ? ", prune" : "").append(plan.publish() ? ", publish" : "").append("\n\n");
        int unchanged = 0;
        for (ConfigPlanner.ConfigChange c : plan.changes()) {
            String mark = switch (c.action()) {
                case ConfigPlanner.CREATE -> "+";
                case ConfigPlanner.UPDATE -> "~";
                case ConfigPlanner.DELETE -> "-";
                case ConfigPlanner.SKIP -> "·";
                default -> null;
            };
            if (mark == null) {
                unchanged++;
                continue;
            }
            out.append("  ").append(mark).append(' ').append(c.kind()).append(' ').append(c.name());
            if (!c.details().isEmpty()) {
                out.append("  (").append(String.join(", ", c.details())).append(')');
            }
            out.append('\n');
        }
        if (!plan.hasChanges()) {
            out.append("  No changes.\n");
        }
        out.append('\n').append(count(plan, ConfigPlanner.CREATE)).append(" to create, ")
                .append(count(plan, ConfigPlanner.UPDATE)).append(" to update, ")
                .append(count(plan, ConfigPlanner.DELETE)).append(" to delete, ")
                .append(unchanged).append(" unchanged.\n")
                .append("Digest: ").append(plan.digest()).append('\n');
        return out.toString();
    }

    static String apply(String ws, ConfigPlanner.ConfigApplyResult result) {
        StringBuilder out = new StringBuilder(plan(ws, result.plan()).replace(" to create,", " created,")
                .replace(" to update,", " updated,").replace(" to delete,", " deleted,"));
        if (!result.published().isEmpty()) {
            out.append('\n');
            for (ConfigPlanner.PublishOutcome p : result.published()) {
                out.append("  ").append(p.endpoint()).append(": ").append(p.result().replace('_', ' '));
                if (p.message() != null) {
                    out.append(" — ").append(p.message());
                }
                out.append('\n');
            }
        }
        out.append(result.ok() ? "Applied.\n" : "Applied, but some endpoints were not published.\n");
        return out.toString();
    }

    private static int count(ConfigPlanner.ConfigPlan plan, String action) {
        return plan.counts().getOrDefault(action, 0);
    }
}
