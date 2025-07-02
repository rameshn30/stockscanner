package com.ramgenix.scanner.service;

import java.util.Comparator;

import com.ramgenix.scanner.entity.Pattern;

public class RankHeadComparator implements Comparator<Pattern>{

	@Override
	public int compare(Pattern o1, Pattern o2) {
		 int bodyComparison = Double.compare(o2.getRank(), o1.getRank());

	        if (bodyComparison != 0) {
	            // If tails are different, return the result of the tail comparison
	            return bodyComparison;
	        } else {
	            // If tails are equal, compare based on StockData tail in descending order
	            return Double.compare(o2.getHead(), o1.getHead());
	        }
	}

}
