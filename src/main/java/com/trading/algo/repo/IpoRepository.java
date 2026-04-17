package com.trading.algo.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.trading.algo.entity.Ipo;

public interface IpoRepository extends JpaRepository<Ipo, Long> {

    List<Ipo> findByListingDateBetween(LocalDate start, LocalDate end);

    List<Ipo> findByOpenDate(LocalDate date);

    List<Ipo> findByListingDate(LocalDate date);
    
    
    Optional<Ipo> findByName(String name);
    

}