package com.ramgenix.scanner.entity;

public class SectorAnalysisResult {
    private String sector;
    private double averagePercentageChange;

    public SectorAnalysisResult(String sector, double averagePercentageChange) {
        this.sector = sector;
        this.averagePercentageChange = averagePercentageChange;
    }

    public String getSector() {
        return sector;
    }

    public double getAveragePercentageChange() {
        return averagePercentageChange;
    }
}
