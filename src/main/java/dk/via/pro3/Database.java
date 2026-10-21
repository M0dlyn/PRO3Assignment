package dk.via.pro3;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;

import javax.sql.DataSource;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Initializes the central database; no station/network dependency. */
public final class Database {
    private Database() {}

    public static DataSource dataSource(String url) {
        var source = new JdbcDataSource();
        source.setURL(url);
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    public static void initialize(DataSource source) throws SQLException {
        try (var connection = source.getConnection()) {
            script(connection, "/schema.sql");
            script(connection, "/migration-v2.sql");
        }
    }

    /** Demo loading is explicit, atomic, and restricted to an empty animal table. */
    public static void seedDemo(DataSource source) throws SQLException {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM animal")) {
                    rows.next();
                    if (rows.getLong(1) != 0) {
                        connection.rollback();
                        return;
                    }
                }
                script(connection, "/demo.sql");
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void script(Connection connection, String resource) throws SQLException {
        var stream = Objects.requireNonNull(Database.class.getResourceAsStream(resource), resource);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        } catch (java.io.IOException exception) {
            throw new SQLException("Cannot read database resource", exception);
        }
    }
}
