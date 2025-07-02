package com.ramgenix.scanner.utility;

import java.util.List;

import com.ramgenix.scanner.entity.StockData;

public class StockCalculationUtility {
	
	public static double calculatePercentageChange(List<StockData> stockDataList, int numDays) {
        if (stockDataList == null || stockDataList.size() < numDays) {
            throw new IllegalArgumentException("Not enough data points to calculate percentage change");
        }

        // Get the latest closing price (index 0 is the most recent day's data)
        double latestClose = stockDataList.get(0).getClose();
        
        // Get the closing price numDays ago
        double oldClose = stockDataList.get(numDays - 1).getClose();

        // Calculate the percentage change
        double percentageChange = ((latestClose - oldClose) / oldClose) * 100;

        return percentageChange;
    }
	
	/**
     * Safely parse a double value from a string.
     * 
     * @param value The string value to parse.
     * @return The parsed double, or 0.0 if parsing fails.
     */
    public static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // Log the error if needed
            System.err.println("Invalid number format: " + value);
            return 0.0;
        }
    }
    
    public static double parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // Log the error if needed
            System.err.println("Invalid number format: " + value);
            return 0.0;
        }
    }

}
