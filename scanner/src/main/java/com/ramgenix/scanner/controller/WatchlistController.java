package com.ramgenix.scanner.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ramgenix.scanner.service.WatchlistService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class WatchlistController {

    private static final Logger logger = LoggerFactory.getLogger(WatchlistController.class);

    private final WatchlistService watchlistService;

    @Autowired
    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @PostMapping(value = "/watchlist", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void addToWatchlist(@RequestBody MultiValueMap<String, String> formData) {
        String symbol = formData.getFirst("symbol");
        String patternName = formData.getFirst("patternName");
        String dateString = formData.getFirst("date");
       // LocalDate date = LocalDate.parse(dateString);
        
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("ddMMMuuuu")
                .toFormatter(Locale.ENGLISH);
      
        String lowercaseDateString = dateString.toLowerCase();
        LocalDate localDate = LocalDate.parse(lowercaseDateString, formatter);  

        watchlistService.addToWatchlist(symbol, patternName, localDate);
        logger.info("Added to watchlist: Symbol={}, PatternName={}, Date={}", symbol, patternName, localDate);
    }
}


