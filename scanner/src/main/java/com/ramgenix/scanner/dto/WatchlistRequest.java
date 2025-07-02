package com.ramgenix.scanner.dto;


import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@ToString

public class WatchlistRequest {
    private String symbol;
    private String patternName;
    private LocalDate date;

    // Getters and setters
}

