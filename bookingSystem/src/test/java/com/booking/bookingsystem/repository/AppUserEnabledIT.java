package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
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
class AppUserEnabledIT {
    @Autowired AppUserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private AppUser createAccount() {
        AppUser user = new AppUser();
        user.setEmail(UUID.randomUUID() + "@example.com");
        return users.saveAndFlush(user);
    }

    @Test
    void newJpaAccountIsDisabled() {
        UUID id = createAccount().getId();
        entityManager.clear();
        assertFalse(users.findById(id).orElseThrow().isEnabled());
    }

    @Test
    void accountCanBeExplicitlyEnabledAndDisabled() {
        AppUser user = createAccount();
        UUID id = user.getId();
        user.setEnabled(true);
        users.saveAndFlush(user);
        entityManager.clear();
        user = users.findById(id).orElseThrow();
        assertTrue(user.isEnabled());
        user.setEnabled(false);
        users.saveAndFlush(user);
        entityManager.clear();
        assertFalse(users.findById(id).orElseThrow().isEnabled());
    }

    @Test
    void sqlInsertDefaultsToDisabled() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user (id, email) values (?, ?)", id, id + "@example.com");
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select enabled from app_user where id = ?", Boolean.class, id));
    }

    @Test
    void databaseRejectsNullEnabled() {
        UUID id = createAccount().getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update app_user set enabled = null where id = ?", id));
        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals("23502", cause.getSQLState());
    }
}
