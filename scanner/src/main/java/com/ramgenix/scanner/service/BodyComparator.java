package com.ramgenix.scanner.service;

import java.util.Comparator;

import com.ramgenix.scanner.entity.Pattern;

public class BodyComparator implements Comparator<Pattern>{

	@Override
    public int compare(Pattern o1, Pattern o2) {
        // Compare based on tail in descending order
        int bodyComparison = Double.compare(o2.getBody0(), o1.getBody0());

        if (bodyComparison != 0) {
            // If tails are different, return the result of the tail comparison
            return bodyComparison;
        } else {
            // If tails are equal, compare based on StockData volume in descending order
            return Double.compare(o2.getStockData().getVolume(), o1.getStockData().getVolume());
        }
    }

}