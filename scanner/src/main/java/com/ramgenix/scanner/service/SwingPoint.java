package com.ramgenix.scanner.service;

import java.time.LocalDate;

// You can make this a static nested class within your StockAnalyzer or a
// separate class.
class SwingPoint {
	LocalDate date;
	double price; // For swing high, this will be the high of the candle
	String type; // "H" for Swing High
	int originalIndex; // Its index in the original stockDataList (reverse chronological)
	int chronologicalX; // Calculated X-coordinate: 0 for oldest bar, N-1 for newest bar

	public SwingPoint(LocalDate date, double price, String type, int originalIndex, int totalBars) {
		this.date = date;
		this.price = price;
		this.type = type;
		this.originalIndex = originalIndex;
		// This is the calculation for chronologicalX:
		// If originalIndex 0 is the latest bar (N-1), then chronologicalX is (N-1).
		// If originalIndex N-1 is the oldest bar (0), then chronologicalX is (0).
		this.chronologicalX = totalBars - 1 - originalIndex;
	}

	// Getters for all fields
	public LocalDate getDate() {
		return date;
	}

	public double getPrice() {
		return price;
	}

	public String getType() {
		return type;
	}

	public int getOriginalIndex() {
		return originalIndex;
	}

	// This is the method that was likely missing or not correctly included in your
	// SwingPoint class:
	public int getChronologicalX() {
		return chronologicalX;
	}

	@Override
	public String toString() {
		return "SwingPoint{" + "date=" + date + ", price=" + String.format("%.2f", price) + ", type='" + type + '\''
				+ ", originalIndex=" + originalIndex + ", chronologicalX=" + chronologicalX + '}';
	}
}