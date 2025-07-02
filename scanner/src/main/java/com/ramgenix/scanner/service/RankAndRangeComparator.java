package com.ramgenix.scanner.service;

import java.util.Comparator;

import com.ramgenix.scanner.entity.Pattern;

public class RankAndRangeComparator implements Comparator<Pattern>{

	@Override
    public int compare(Pattern o1, Pattern o2) {        
        int comparision = Double.compare(o2.getRank(), o1.getRank());
        if (comparision != 0) {
          return comparision;
        } else {          
            return Double.compare(o1.getRangePct(), o2.getRangePct());
        }
    }

}
