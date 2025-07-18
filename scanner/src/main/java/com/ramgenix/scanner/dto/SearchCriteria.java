package com.ramgenix.scanner.dto;

public class SearchCriteria {
	private String pattern;
	private Double adr;
	private Double minPrice;
	private Double maxPrice;
	private Long minVolume;

	// Default constructor
	public SearchCriteria() {
	}

	// Getters and Setters
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
	}

	public Double getAdr() {
		return adr;
	}

	public void setAdr(Double adr) {
		this.adr = adr;
	}

	public Double getMinPrice() {
		return minPrice;
	}

	public void setMinPrice(Double minPrice) {
		this.minPrice = minPrice;
	}

	public Double getMaxPrice() {
		return maxPrice;
	}

	public void setMaxPrice(Double maxPrice) {
		this.maxPrice = maxPrice;
	}

	public Long getMinVolume() {
		return minVolume;
	}

	public void setMinVolume(Long minVolume) {
		this.minVolume = minVolume;
	}
}