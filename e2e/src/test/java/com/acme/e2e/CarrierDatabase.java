package com.acme.e2e;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The carrier changes its own data: a direct update of the emulator's database, the same as the manual checks of
 * {@code docs/specs/demo-seed.md} §8. The emulator has no command for it on purpose: in real life a discrepancy is
 * not something a client of the carrier can cause. No classes of {@code carrier-emulator} are used.
 */
public final class CarrierDatabase {

    static final String URL = System.getProperty("e2e.carrierDbUrl", "jdbc:postgresql://localhost:5432/carrier");
    static final String USER = System.getProperty("e2e.carrierDbUser", "carrier");
    static final String PASSWORD = System.getProperty("e2e.carrierDbPassword", "carrier");

    /** Replaces the executed operation's result with a successful one; {@code GET /operations/{id}} shows it at once. */
    public void changeResult(String operationId, String trackingNumber, BigDecimal price) {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE operation
                        SET result_status = 'SUCCEEDED', tracking_number = ?, price = ?, reason = NULL
                      WHERE operation_id = ? AND state = 'EXECUTED'""")) {
            update.setString(1, trackingNumber);
            update.setBigDecimal(2, price);
            update.setObject(3, UUID.fromString(operationId));
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Operation " + operationId + " is not executed by the carrier");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot change the carrier's result at " + URL, e);
        }
    }
}
