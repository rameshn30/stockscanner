package com.ramgenix.scanner.service;

import java.util.Comparator;

import com.ramgenix.scanner.entity.Pattern;

public class RankComparator implements Comparator<Pattern>{

	@Override
	public int compare(Pattern o1, Pattern o2) {
		 int rankComparison = Double.compare(o2.getRank(), o1.getRank());

	        if (rankComparison != 0) {	            
	            return rankComparison;
	        } else {	            
	            return Double.compare(o2.getBody0(), o1.getBody0());
	        }
	}

}
