package com.ramgenix.scanner.service;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ramgenix.scanner.entity.Watchlist;
import com.ramgenix.scanner.repository.WatchlistRepository;

@Service
public class WatchlistService {

    @Autowired
    private WatchlistRepository watchlistRepository;

    public void addToWatchlist(String symbol, String patternName, LocalDate date) {
        Watchlist watchlist = new Watchlist();
        watchlist.setSymbol(symbol);
        watchlist.setPatternName(patternName);
        watchlist.setDate(date);
        watchlistRepository.save(watchlist);
    }
}

