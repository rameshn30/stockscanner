package com.ramgenix.scanner.service;

import java.util.Comparator;

import com.ramgenix.scanner.entity.Pattern;

public class HammerComparator implements Comparator<Pattern>{

	@Override
    public int compare(Pattern o1, Pattern o2) {
        // Compare based on tail in descending order
        int tailComparison = Double.compare(o2.getTail(), o1.getTail());

        if (tailComparison != 0) {
            // If tails are different, return the result of the tail comparison
            return tailComparison;
        } else {
            // If tails are equal, compare based on StockData volume in descending order
            return Double.compare(o2.getStockData().getVolume(), o1.getStockData().getVolume());
        }
    }

}
