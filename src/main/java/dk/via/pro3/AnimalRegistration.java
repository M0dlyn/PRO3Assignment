package dk.via.pro3;

import java.math.BigDecimal;

/** REST representation. arrivalDate is an ISO date; weight is a JSON decimal in kg. */
public record AnimalRegistration(String registrationNumber, String arrivalDate,
                                 BigDecimal arrivalWeightKg, String origin, String species) {}
