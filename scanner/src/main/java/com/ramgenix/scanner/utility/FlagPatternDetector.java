package com.ramgenix.scanner.utility;

import java.util.List;

import com.ramgenix.scanner.entity.StockData;

public class FlagPatternDetector {
	// Parameters for flag detection (tune these based on your data)
	private static final double FLAGPOLE_MIN_PCT = 10.0; // Min % change for flagpole
	private static final int FLAGPOLE_MIN_DAYS = 3; // Min days for flagpole
	private static final int FLAGPOLE_MAX_DAYS = 10; // Max days for flagpole
	private static final int FLAG_MIN_DAYS = 5; // Min days for flag
	private static final int FLAG_MAX_DAYS = 20; // Max days for flag
	private static final double FLAG_RANGE_PCT = 5.0; // Max % range for flag consolidation
	private static final double BREAKOUT_PCT = 2.0; // Min % for breakout

	public static void detectFlags(List<StockData> data) {
		for (int i = 0; i <= data.size() - FLAGPOLE_MIN_DAYS - FLAG_MIN_DAYS; i++) {
			// Check for bullish flag
			detectBullishFlag(data, i);
			// Check for bearish flag
			// detectBearishFlag(data, i);
		}
	}

	private static void detectBullishFlag(List<StockData> data, int breakoutIndex) {
		// Step 1: Look for flag (consolidation) in recent data (lower indices)
		int flagStart = breakoutIndex + 1;
		int flagEnd = breakoutIndex + FLAG_MIN_DAYS;
		if (flagEnd >= data.size() || flagEnd > breakoutIndex + FLAG_MAX_DAYS)
			return;

		// Calculate flag range
		double flagHigh = Double.MIN_VALUE;
		double flagLow = Double.MAX_VALUE;
		for (int j = breakoutIndex + 1; j <= flagEnd; j++) {
			flagHigh = Math.max(flagHigh, data.get(j).getHigh());
			flagLow = Math.min(flagLow, data.get(j).getLow());
		}
		double flagRangePct = ((flagHigh - flagLow) / flagLow) * 100;
		if (flagRangePct > FLAG_RANGE_PCT)
			return;

		// Step 2: Look for flagpole (sharp upward move) before the flag (higher
		// indices)
		for (int j = flagEnd + 1; j <= flagEnd + FLAGPOLE_MAX_DAYS && j < data.size(); j++) {
			for (int k = j + FLAGPOLE_MIN_DAYS; k <= j + FLAGPOLE_MAX_DAYS && k < data.size(); k++) {
				double startPrice = data.get(k).getClose(); // Earlier date
				double endPrice = data.get(j).getClose(); // Later date (closer to flag)
				double pctChange = ((endPrice - startPrice) / startPrice) * 100;

				if (pctChange >= FLAGPOLE_MIN_PCT) {
					// Step 3: Check for breakout at breakoutIndex
					double flagTop = flagHigh;
					double breakoutPrice = data.get(breakoutIndex).getClose();
					double breakoutPct = ((breakoutPrice - flagTop) / flagTop) * 100;
					if (breakoutPct >= BREAKOUT_PCT) {
						System.out.println("Bullish Flag detected " + data.get(0).getSymbol() + " at "
								+ data.get(breakoutIndex).getDate() + ", Flag: " + data.get(flagEnd).getDate() + " to "
								+ data.get(flagStart).getDate() + ", Flagpole: " + data.get(k).getDate() + " to "
								+ data.get(j).getDate());
					}
				}
			}
		}
	}

	private static void detectBearishFlag(List<StockData> data, int endIndex) {
		// Similar logic for bearish flag (sharp downward move)
		int flagpoleStart = endIndex - FLAG_MAX_DAYS - FLAGPOLE_MAX_DAYS;
		if (flagpoleStart < 0)
			return;

		for (int j = flagpoleStart; j <= endIndex - FLAG_MIN_DAYS - FLAGPOLE_MIN_DAYS; j++) {
			for (int k = j + FLAGPOLE_MIN_DAYS; k <= j + FLAGPOLE_MAX_DAYS && k < endIndex - FLAG_MIN_DAYS; k++) {
				double startPrice = data.get(j).getClose();
				double endPrice = data.get(k).getClose();
				double pctChange = ((startPrice - endPrice) / startPrice) * 100;

				if (pctChange >= FLAGPOLE_MIN_PCT) {
					double flagHigh = Double.MIN_VALUE;
					double flagLow = Double.MAX_VALUE;
					int flagEnd = k + FLAG_MIN_DAYS;
					if (flagEnd >= endIndex || flagEnd >= k + FLAG_MAX_DAYS)
						continue;

					for (int m = k + 1; m <= flagEnd; m++) {
						flagHigh = Math.max(flagHigh, data.get(m).getHigh());
						flagLow = Math.min(flagLow, data.get(m).getLow());
					}
					double flagRangePct = ((flagHigh - flagLow) / flagLow) * 100;
					if (flagRangePct <= FLAG_RANGE_PCT) {
						if (flagEnd + 1 < endIndex) {
							double breakoutPrice = data.get(flagEnd + 1).getClose();
							double flagBottom = flagLow;
							double breakoutPct = ((flagBottom - breakoutPrice) / flagBottom) * 100;
							if (breakoutPct >= BREAKOUT_PCT) {
								System.out.println("Bearish Flag detected at index " + flagEnd + ", Flagpole: " + j
										+ " to " + k + ", Flag: " + (k + 1) + " to " + flagEnd + ", Breakout at: "
										+ data.get(flagEnd + 1).getDate());
							}
						}
					}
				}
			}
		}
	}
}
