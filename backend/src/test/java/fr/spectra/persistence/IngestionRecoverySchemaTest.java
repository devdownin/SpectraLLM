package fr.spectra.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class IngestionRecoverySchemaTest {
    @Test void migrationIsIdempotentAndDeletionMarkerSurvivesReconnection() throws Exception {
        String url = "jdbc:h2:mem:recovery-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ingested_files DROP COLUMN ingestion_complete");
                statement.execute("DROP INDEX idx_ingested_files_deletion_pending");
                statement.execute("ALTER TABLE ingested_files DROP COLUMN deletion_pending");
                statement.execute("ALTER TABLE ingested_files DROP COLUMN deletion_actor");
                statement.execute("INSERT INTO ingested_files (sha256) VALUES ('old-doc')");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            try (var statement = connection.createStatement(); var rs = statement.executeQuery(
                    "SELECT ingestion_complete, deletion_pending FROM ingested_files WHERE sha256='old-doc'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getBoolean(2)).isFalse();
            }
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE ingested_files SET deletion_pending=TRUE, deletion_actor='alice' WHERE sha256='old-doc'");
            }
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT deletion_pending, deletion_actor FROM ingested_files WHERE sha256='old-doc'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).isTrue();
            assertThat(rs.getString(2)).isEqualTo("alice");
        }
    }
}
