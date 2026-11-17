package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

/** Browser sessions live in PostgreSQL, so every instance knows every signed-in user. */
class SharedSessionIntegrationTest extends AppTestSupport {

    private static int sessionsOf(String username) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var ps = c.prepareStatement("select count(*) from opendbapi.spring_session where principal_name = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void signingInStoresTheSessionInPostgresAndSigningOutRemovesIt() throws Exception {
        // Arrange
        int before = sessionsOf("admin");

        // Act
        ApiClient admin = signedInAdmin();
        admin.get("/admin/api/me").expect(200);
        int signedIn = sessionsOf("admin");
        String cookie = admin.cookie("OPENDBAPI_SESSION");
        admin.post("/admin/api/auth/logout", null);
        int signedOut = sessionsOf("admin");

        // Assert
        assertThat(cookie).isNotBlank();
        assertThat(signedIn).isEqualTo(before + 1);
        assertThat(signedOut).isEqualTo(before);
    }
}
