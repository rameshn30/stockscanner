package com.ramgenix.scanner.service;

import java.math.BigDecimal;
import java.util.Comparator;

import com.ramgenix.scanner.entity.Pattern;

public class RankComparator implements Comparator<Pattern> {

	@Override
	public int compare(Pattern o1, Pattern o2) {
		int rankComparison = Double.compare(o2.getRank(), o1.getRank());

		if (rankComparison != 0) {
			return rankComparison;
		} else {
			BigDecimal adr1 = o1.getAdr() != null ? o1.getAdr() : BigDecimal.ZERO;
			BigDecimal adr2 = o2.getAdr() != null ? o2.getAdr() : BigDecimal.ZERO;
			return adr2.compareTo(adr1); // Descending order
		}
	}
}
