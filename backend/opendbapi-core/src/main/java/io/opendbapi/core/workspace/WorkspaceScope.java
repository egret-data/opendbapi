package io.opendbapi.core.workspace;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work inside a transaction bound to one workspace (spec §4.0).
 *
 * <p>The workspace id is published to PostgreSQL with {@code set_config(..., true)}, which
 * is transaction-local: it is discarded on commit or rollback, so a pooled connection never
 * carries one workspace's identity into the next borrower. Row-level security policies read
 * it through {@code current_workspace_id()}.
 */
@Component
public class WorkspaceScope {

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public WorkspaceScope(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public <T> T call(UUID workspaceId, Supplier<T> work) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(work, "work");
        return transactions.execute(status -> {
            jdbc.sql("select set_config('opendbapi.workspace_id', ?, true)")
                    .param(workspaceId.toString())
                    .query(String.class)
                    .single();
            return work.get();
        });
    }

    public void run(UUID workspaceId, Runnable work) {
        Objects.requireNonNull(work, "work");
        call(workspaceId, () -> {
            work.run();
            return null;
        });
    }
}
