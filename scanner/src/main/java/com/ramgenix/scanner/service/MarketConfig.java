package com.ramgenix.scanner.service;

public class MarketConfig {
	String dateFile;
	String csvPath;
	String outputDirectory;
	String watchlistDirectory;
	String country;

	public MarketConfig(String dateFile, String csvPath, String outputDirectory, String watchlistDirectory,
			String country) {
		this.dateFile = dateFile;
		this.csvPath = csvPath;
		this.outputDirectory = outputDirectory;
		this.watchlistDirectory = watchlistDirectory;
		this.country = country;
	}
}