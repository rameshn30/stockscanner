package com.ramgenix.scanner.entity;

import java.util.List;

import lombok.Data;

@Data
public class Stock {
	private String symbol;
	private String exchange;
	private String pattern;
	private boolean isInsideBar;
	private double adr;
	private double distance10MA;
	private List<StockData> history;
}