package dk.via.pro3;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Registration rules independent of HTTP and JSON. */
public final class AnimalRegistrationService {
    private final AnimalRepository repository;
    public AnimalRegistrationService(AnimalRepository repository) { this.repository = repository; }

    public AnimalRegistration register(AnimalRegistration input) throws SQLException {
        var animal = validate(input);
        try {
            repository.insert(animal);
        } catch (SQLException exception) {
            // A database primary key also resolves concurrent duplicate submissions atomically.
            if ("23505".equals(exception.getSQLState())) {
                throw new ApiException(409, "Animal registration number already exists");
            }
            throw exception;
        }
        return animal;
    }

    public static AnimalRegistration validate(AnimalRegistration input) {
        text(input.registrationNumber(), "registrationNumber", 100);
        date(input.arrivalDate());
        text(input.origin(), "origin", 200);
        String species = input.species() == null ? "unspecified" : input.species();
        text(species, "species", 100);
        BigDecimal weight = input.arrivalWeightKg();
        if (weight == null || weight.signum() <= 0
                || weight.compareTo(new BigDecimal("999999999.999")) > 0
                || weight.stripTrailingZeros().scale() > 3) {
            throw new ApiException(400, "arrivalWeightKg must be positive, at most 999999999.999, with at most 3 decimal places");
        }
        var animal = new AnimalRegistration(input.registrationNumber(), input.arrivalDate(),
                weight.setScale(3), input.origin(), species);
        return animal;
    }

    public AnimalRegistration find(String id) throws SQLException {
        text(id, "registrationNumber", 100);
        return repository.find(id).orElseThrow(() -> new ApiException(404, "Animal not found"));
    }

    public List<AnimalRegistration> list(String arrivalDate, String origin) throws SQLException {
        if (origin != null) text(origin, "origin", 200);
        return repository.list(arrivalDate == null ? null : date(arrivalDate), origin);
    }

    private static void text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, field + " must be nonblank, at most " + maximum
                    + " characters, without surrounding whitespace or control characters");
        }
    }

    private static LocalDate date(String value) {
        if (value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try {
                var date = LocalDate.parse(value);
                if (date.getYear() >= 1) return date;
            } catch (DateTimeParseException ignored) { }
        }
        throw new ApiException(400, "arrivalDate must be a valid YYYY-MM-DD date (year 0001–9999)");
    }

    public static final class ApiException extends RuntimeException {
        private final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
