package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoginSessionRepository extends JpaRepository<LoginSession, UUID> {
}
