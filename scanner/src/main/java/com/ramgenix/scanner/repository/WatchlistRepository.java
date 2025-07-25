package com.ramgenix.scanner.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ramgenix.scanner.entity.Watchlist;

@Repository
public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {
}

