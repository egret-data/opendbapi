package io.opendbapi.core.endpoint.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.error.ApiProblem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Test cases of endpoints in the current workspace (row-level secured). */
@Repository
public class EndpointTestStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public EndpointTestStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<EndpointTestCase> list(UUID endpointId) {
        return jdbc.sql("select * from endpoint_test_case where endpoint_id = ? order by lower(name)").param(endpointId)
                .query(this::row).list();
    }

    public Optional<EndpointTestCase> find(UUID endpointId, UUID id) {
        return jdbc.sql("select * from endpoint_test_case where endpoint_id = ? and id = ?").params(endpointId, id)
                .query(this::row).optional();
    }

    public UUID create(UUID workspaceId, UUID endpointId, EndpointTestCase t, UUID userId) {
        try {
            return jdbc.sql("""
                    insert into endpoint_test_case (workspace_id, endpoint_id, name, service_account_id, params, expect, created_by)
                    values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) returning id""")
                    .params(workspaceId, endpointId, t.name(), t.serviceAccountId(), write(t.params()), write(t.expect()), userId)
                    .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw nameTaken(t.name());
        } catch (DataIntegrityViolationException e) {
            throw ApiProblem.notFound("resource_not_found", "That service account does not exist in this workspace.");
        }
    }

    public boolean update(UUID endpointId, UUID id, EndpointTestCase t) {
        try {
            return jdbc.sql("""
                    update endpoint_test_case set name = ?, service_account_id = ?, params = ?::jsonb, expect = ?::jsonb,
                           last_result = null, updated_at = now() where endpoint_id = ? and id = ?""")
                    .params(t.name(), t.serviceAccountId(), write(t.params()), write(t.expect()), endpointId, id).update() > 0;
        } catch (DuplicateKeyException e) {
            throw nameTaken(t.name());
        } catch (DataIntegrityViolationException e) {
            throw ApiProblem.notFound("resource_not_found", "That service account does not exist in this workspace.");
        }
    }

    public boolean delete(UUID endpointId, UUID id) {
        return jdbc.sql("delete from endpoint_test_case where endpoint_id = ? and id = ?").params(endpointId, id).update() > 0;
    }

    public void recordResult(UUID id, EndpointTestCase.TestCaseResult result) {
        jdbc.sql("update endpoint_test_case set last_result = ?::jsonb where id = ?").params(write(result), id).update();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private EndpointTestCase row(ResultSet rs, int i) throws SQLException {
        try {
            String last = rs.getString("last_result");
            return new EndpointTestCase(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getObject("service_account_id", UUID.class), json.readValue(rs.getString("params"), MAP),
                    json.readValue(rs.getString("expect"), EndpointTestCase.TestExpectation.class),
                    last == null ? null : json.readValue(last, EndpointTestCase.TestCaseResult.class),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt test case", e);
        }
    }

    private static ApiProblem nameTaken(String name) {
        return ApiProblem.conflict("name_taken", "This endpoint already has a test named '%s'.".formatted(name));
    }
}
