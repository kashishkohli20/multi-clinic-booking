package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class AppUserEmailConstraintsIT {
    @Autowired
    private JdbcTemplate jdbc;

    private UUID insertAccount(String email, boolean deleted) {
        UUID id = UUID.randomUUID();
        // Bypass Bean Validation to exercise the constraints installed by Flyway.
        jdbc.update("insert into app_user (id, name, email, created_at, updated_at, deleted) "
                        + "values (?, ?, ?, now(), now(), ?)",
                id, "Test account", email, deleted);
        return id;
    }

    private void assertSqlState(String expected, DataIntegrityViolationException failure) {
        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", " \t\r\n "})
    void rejectsNullAndBlankEmails(String email) {
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insertAccount(email, false));
        assertSqlState(email == null ? "23502" : "23514", failure);
    }

    @ParameterizedTest
    @CsvSource({
            "false, alice@example.com",
            "false, ALICE@EXAMPLE.COM",
            "true, alice@example.com",
            "true, ALICE@EXAMPLE.COM"
    })
    void rejectsDuplicateEmailEvenWhenOriginalAccountIsSoftDeleted(boolean deleted, String duplicate) {
        insertAccount("alice@example.com", deleted);
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insertAccount(duplicate, false));
        assertSqlState("23505", failure);
    }

    @Test
    void distinctEmailsCanShareANameAndKeepDotsAndTags() {
        String[] emails = {"alice@gmail.com", "a.lice@gmail.com", "alice+booking@gmail.com", "bob@example.com"};
        for (String email : emails) {
            UUID id = insertAccount(email, false);
            assertEquals(email, jdbc.queryForObject("select email from app_user where id = ?", String.class, id));
        }
    }

    @Test
    void updatingEmailCannotCollideWithAnotherAccount() {
        insertAccount("alice@example.com", false);
        UUID other = insertAccount("bob@example.com", false);
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update app_user set email = ? where id = ?", "ALICE@EXAMPLE.COM", other));
        assertSqlState("23505", failure);
    }
}
