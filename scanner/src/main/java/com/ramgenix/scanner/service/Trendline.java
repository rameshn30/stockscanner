package com.ramgenix.scanner.service;

class Trendline {
	SwingPoint startPoint; // The chronologically older (leftmost) swing high
	SwingPoint endPoint; // The chronologically newer (rightmost) swing high
	double slope;
	double intercept;
	String type; // "Descending"
	int validationTouchCount = 0; // NEW FIELD: To store the count of additional touches

	public Trendline(SwingPoint point1, SwingPoint point2, String type) {
		if (point1.getChronologicalX() > point2.getChronologicalX()) {
			this.startPoint = point2;
			this.endPoint = point1;
		} else {
			this.startPoint = point1;
			this.endPoint = point2;
		}
		this.type = type;
		calculateLineEquation();
	}

	private void calculateLineEquation() {
		if (endPoint.getChronologicalX() == startPoint.getChronologicalX()) {
			this.slope = 0;
			this.intercept = startPoint.getPrice();
			return;
		}
		this.slope = (endPoint.getPrice() - startPoint.getPrice())
				/ (double) (endPoint.getChronologicalX() - startPoint.getChronologicalX());
		this.intercept = startPoint.getPrice() - this.slope * startPoint.getChronologicalX();
	}

	public double getPriceAtChronologicalX(int chronologicalX) {
		return this.slope * chronologicalX + this.intercept;
	}

	// Getters for properties
	public SwingPoint getStartPoint() {
		return startPoint;
	}

	public SwingPoint getEndPoint() {
		return endPoint;
	}

	public double getSlope() {
		return slope;
	}

	public double getIntercept() {
		return intercept;
	}

	public String getType() {
		return type;
	}

	// NEW Getters and Setters for validationTouchCount
	public int getValidationTouchCount() {
		return validationTouchCount;
	}

	public void setValidationTouchCount(int validationTouchCount) {
		this.validationTouchCount = validationTouchCount;
	}

	@Override
	public String toString() {
		return "Trendline [" + type + "]" + " Start: " + startPoint.getDate() + " (Price: "
				+ String.format("%.2f", startPoint.getPrice()) + ")" + ", End: " + endPoint.getDate() + " (Price: "
				+ String.format("%.2f", endPoint.getPrice()) + ")" + ", Slope: " + String.format("%.4f", slope)
				+ ", Touches: " + validationTouchCount; // Display the touch count
	}
}