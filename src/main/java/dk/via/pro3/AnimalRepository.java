package dk.via.pro3;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Prepared JDBC operations on the same animal table used by gRPC traceability. */
public final class AnimalRepository {
    private static final String SELECT = """
            SELECT registration_number, arrival_date, arrival_weight_kg, origin, species FROM animal
            """;
    private final DataSource source;

    public AnimalRepository(DataSource source) { this.source = source; }

    public void insert(AnimalRegistration animal) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO animal (registration_number, arrival_date, arrival_weight_kg, origin, species)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, animal.registrationNumber());
            statement.setDate(2, Date.valueOf(animal.arrivalDate()));
            statement.setBigDecimal(3, animal.arrivalWeightKg());
            statement.setString(4, animal.origin());
            statement.setString(5, animal.species());
            statement.executeUpdate();
        }
    }

    public Optional<AnimalRegistration> find(String registrationNumber) throws SQLException {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement(SELECT + " WHERE registration_number = ?")) {
            statement.setString(1, registrationNumber);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    public List<AnimalRegistration> list(LocalDate arrivalDate, String origin) throws SQLException {
        String sql = SELECT + " WHERE 1=1" + (arrivalDate == null ? "" : " AND arrival_date = ?")
                + (origin == null ? "" : " AND origin = ?") + " ORDER BY registration_number";
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (arrivalDate != null) statement.setDate(parameter++, Date.valueOf(arrivalDate));
            if (origin != null) statement.setString(parameter, origin);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<AnimalRegistration>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        }
    }

    private static AnimalRegistration read(ResultSet row) throws SQLException {
        return new AnimalRegistration(row.getString(1), row.getDate(2).toLocalDate().toString(),
                row.getBigDecimal(3), row.getString(4), row.getString(5));
    }
}
