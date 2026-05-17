package com.trading.algo.ipo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IpoRepository extends JpaRepository<Ipo, Long> {

    Optional<Ipo> findByName(String name);

    List<Ipo> findByListingDate(LocalDate date);

    List<Ipo> findByOpenDate(LocalDate date);

    List<Ipo> findByListingDateBetween(LocalDate from, LocalDate to);

    List<Ipo> findByStatus(String status);

    // IPOs listing today that haven't been monitored yet
    List<Ipo> findByListingDateAndAlertListingPerfSentFalse(LocalDate date);

    // IPOs with listing date in the future (upcoming)
    List<Ipo> findByListingDateAfterOrderByListingDateAsc(LocalDate date);

    // IPOs listing in next N days
    List<Ipo> findByListingDateBetweenOrderByListingDateAsc(LocalDate from, LocalDate to);
}