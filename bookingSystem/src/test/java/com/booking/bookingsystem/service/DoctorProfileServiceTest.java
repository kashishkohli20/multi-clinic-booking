package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.Doctor;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.service.exception.DoctorProfileAlreadyExistsException;
import com.booking.bookingsystem.service.exception.InvalidRegistrationException;
import com.booking.bookingsystem.util.NameNormalizer;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DoctorProfileServiceTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final DoctorRepository doctors = mock(DoctorRepository.class);
    private final DoctorProfileService service = new DoctorProfileService(users, doctors, new NameNormalizer());
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void account() {
        var user = new AppUser();
        user.setId(accountId);
        user.setEnabled(true);
        when(users.lockEnabledById(accountId)).thenReturn(Optional.of(user));
        when(doctors.saveAndFlush(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsBlankNameWithoutSaving(String name) {
        assertThrows(InvalidRegistrationException.class, () -> service.createProfile(accountId, name, null));
        verifyNoInteractions(doctors);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void rejectsOverlongNameOrDescriptionWithoutSaving(String character) {
        assertThrows(InvalidRegistrationException.class,
                () -> service.createProfile(accountId, character.repeat(256), null));
        assertThrows(InvalidRegistrationException.class,
                () -> service.createProfile(accountId, "Dr Smith", character.repeat(256)));
        verifyNoInteractions(doctors);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void optionalBlankDescriptionBecomesNull(String description) {
        assertNull(service.createProfile(accountId, "Dr Smith", description).getDescription());
    }

    @Test
    void missingIdentityOrIneligibleAccountCannotCreateProfile() {
        assertThrows(AccessDeniedException.class, () -> service.createProfile(null, "Dr Smith", null));
        verifyNoInteractions(users, doctors);
        when(users.lockEnabledById(accountId)).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () -> service.createProfile(accountId, "Dr Smith", null));
        verifyNoInteractions(doctors);
    }

    @Test
    void onlyDoctorAccountUniqueViolationBecomesProfileConflict() {
        var failure = databaseFailure("doctor_user_id_key", "23505");
        when(doctors.saveAndFlush(any())).thenThrow(failure);
        var conflict = assertThrows(DoctorProfileAlreadyExistsException.class,
                () -> service.createProfile(accountId, "Dr Smith", null));
        assertSame(failure, conflict.getCause());
    }

    @ParameterizedTest
    @CsvSource({"some_other_constraint,23505", "doctor_user_id_key,23502", "doctor_user_id_fkey,23503"})
    void unrelatedDatabaseFailuresRemainUnchanged(String constraint, String sqlState) {
        var failure = databaseFailure(constraint, sqlState);
        when(doctors.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.createProfile(accountId, "Dr Smith", null)));
    }

    @Test
    void databaseFailureWithoutConstraintMetadataRemainsUnchanged() {
        var failure = new DataIntegrityViolationException("Storage failure");
        when(doctors.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.createProfile(accountId, "Dr Smith", null)));
    }

    private DataIntegrityViolationException databaseFailure(String constraint, String sqlState) {
        return new DataIntegrityViolationException("Database failure", new ConstraintViolationException(
                "Database failure", new SQLException("Database failure", sqlState), constraint));
    }
}
