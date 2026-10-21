package dk.via.pro3;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL and database records stay independent of the gRPC transport. */
public final class TraceabilityRepository {
    public record AnimalRecord(String registrationNumber, String species,
                               BigDecimal arrivalWeightKg, LocalDate arrivalDate) {}
    public record ProductRecord(String productId, String productType, LocalDate packedDate) {}

    private final DataSource source;

    public TraceabilityRepository(DataSource source) {
        this.source = source;
    }

    public Optional<List<AnimalRecord>> animalsForProduct(String productId) throws SQLException {
        return query("SELECT 1 FROM product WHERE product_id = ?", """
                SELECT DISTINCT a.registration_number, a.species, a.arrival_weight_kg, a.arrival_date
                FROM product_tray_batch link
                JOIN part p ON p.batch_id = link.batch_id
                JOIN animal a ON a.registration_number = p.animal_registration_number
                WHERE link.product_id = ?
                ORDER BY a.registration_number
                """, productId, rows -> new AnimalRecord(rows.getString(1), rows.getString(2),
                rows.getBigDecimal(3), rows.getDate(4).toLocalDate()));
    }

    public Optional<List<ProductRecord>> productsForAnimal(String registrationNumber) throws SQLException {
        return query("SELECT 1 FROM animal WHERE registration_number = ?", """
                SELECT DISTINCT product.product_id, product.product_type, product.packed_date
                FROM part p
                JOIN product_tray_batch link ON link.batch_id = p.batch_id
                JOIN product ON product.product_id = link.product_id
                WHERE p.animal_registration_number = ?
                ORDER BY product.product_id
                """, registrationNumber, rows -> new ProductRecord(rows.getString(1), rows.getString(2),
                rows.getDate(3).toLocalDate()));
    }

    // Existence and results share a snapshot: missing entity differs from zero matches.
    private <T> Optional<List<T>> query(String existenceSql, String querySql, String id,
                                       RowMapper<T> mapper) throws SQLException {
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(existenceSql)) {
                    statement.setString(1, id);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            connection.commit();
                            return Optional.empty();
                        }
                    }
                }
                var records = new ArrayList<T>();
                try (var statement = connection.prepareStatement(querySql)) {
                    statement.setString(1, id);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) records.add(mapper.map(rows));
                    }
                }
                connection.commit();
                return Optional.of(List.copyOf(records));
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }
}
