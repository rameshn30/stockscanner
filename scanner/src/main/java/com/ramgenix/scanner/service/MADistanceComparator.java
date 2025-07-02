package com.ramgenix.scanner.service;

import java.util.Comparator;

import com.ramgenix.scanner.entity.Pattern;

public class MADistanceComparator implements Comparator<Pattern> {

	@Override
	public int compare(Pattern o1, Pattern o2) {
		return Double.compare(o1.getMaDistance(), o2.getMaDistance());
	}

}
