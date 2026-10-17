package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.Clinic;
import com.booking.bookingsystem.entity.Doctor;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.exception.ClinicAccessRequestConflictException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoctorClinicAccessRequestServiceTest {
    @ParameterizedTest
    @CsvSource({"uq_doctor_clinic_request_pending,23505,true", "other_constraint,23505,false",
            "uq_doctor_clinic_request_pending,23502,false", "fk_doctor_clinic_request_clinic,23503,false"})
    void onlyPendingUniquenessFailuresBecomeBusinessConflicts(String constraint, String sqlState, boolean conflict) {
        var users = mock(AppUserRepository.class);
        var doctors = mock(DoctorRepository.class);
        var clinics = mock(ClinicRepository.class);
        var memberships = mock(ClinicMembershipRepository.class);
        var requests = mock(DoctorClinicAccessRequestRepository.class);
        UUID account = UUID.randomUUID();
        UUID clinic = UUID.randomUUID();
        var doctor = new Doctor();
        doctor.setId(UUID.randomUUID());
        when(users.lockEnabledById(account)).thenReturn(Optional.of(new AppUser()));
        when(doctors.lockForAccount(account)).thenReturn(Optional.of(doctor));
        when(clinics.lockById(clinic)).thenReturn(Optional.of(new Clinic()));
        var failure = new DataIntegrityViolationException("Database error", new ConstraintViolationException(
                "Database error", new SQLException("Database error", sqlState), constraint));
        when(requests.saveAndFlush(any())).thenThrow(failure);
        var service = new DoctorClinicAccessRequestService(users, doctors, clinics, memberships, requests, Clock.systemUTC());
        if (conflict) {
            assertSame(failure, assertThrows(ClinicAccessRequestConflictException.class,
                    () -> service.submit(account, clinic)).getCause());
        } else {
            assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.submit(account, clinic)));
        }
    }
}
