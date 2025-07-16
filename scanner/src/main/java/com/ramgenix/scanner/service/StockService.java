package com.ramgenix.scanner.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.ramgenix.scanner.entity.Stock;
import com.ramgenix.scanner.entity.StockData;

@Service
public class StockService {

	// Mock repository method (replace with actual JPA repository)
	public List<StockData> findStockDataBySymbol(String symbol) {
		// Simulate database fetch; replace with JPA repository call
		List<StockData> data = new ArrayList<>();
		LocalDate startDate = LocalDate.of(2025, 7, 1);
		for (int i = 0; i < 60; i++) {
			StockData sd = new StockData();
			sd.setId((long) i);
			sd.setSymbol(symbol);
			sd.setSeries("EQ");
			sd.setOpen(100 + i * 0.5 + Math.random() * 5); // Mock data
			sd.setHigh(sd.getOpen() + Math.random() * 2);
			sd.setLow(sd.getOpen() - Math.random() * 2);
			sd.setClose(sd.getLow() + Math.random() * (sd.getHigh() - sd.getLow()));
			sd.setVolume((long) (100000 + i * 5000 + Math.random() * 50000));
			sd.setSwingType("UP");
			sd.setPurpleDotType("STRONG");
			sd.setDate(startDate.plusDays(i));
			data.add(sd);
		}
		return data.stream().sorted(Comparator.comparing(StockData::getDate)).collect(Collectors.toList());
	}

	public List<Stock> getStocksByPattern(String pattern) {
		List<Stock> stocks = new ArrayList<>();
		stocks.add(mapToStock("BEML.IN", true));
		stocks.add(mapToStock("RELIANCE.IN", false));
		return stocks.stream().filter(stock -> stock.getPattern().equalsIgnoreCase(pattern))
				.collect(Collectors.toList());
	}

	private Stock mapToStock(String symbol, boolean isInsideBar) {
		List<StockData> stockData = findStockDataBySymbol(symbol);
		List<StockData> history = generateMockData(symbol, 60);

		List<Double> ma10 = calculateMovingAverage(stockData, 10);
		List<Double> ma20 = calculateMovingAverage(stockData, 20);
		List<Double> ma50 = calculateMovingAverage(stockData, 50);

		Stock stock = new Stock();
		stock.setSymbol(symbol);
		stock.setExchange("NSE");
		stock.setPattern("bull-flags");
		stock.setInsideBar(isInsideBar);
		stock.setAdr(isInsideBar ? 4.61 : 2.17);
		stock.setDistance10MA(isInsideBar ? 1.5 : 0.5);
		stock.setHistory(history);
		return stock;
	}

	private List<StockData> generateMockData(String symbol, int days) {
		List<StockData> data = new ArrayList<>();
		double basePrice = 100; // Starting price at oldest date (index 59)
		LocalDate startDate = LocalDate.of(2025, 7, 1); // Oldest date
		for (int i = days - 1; i >= 0; i--) { // Start from oldest (59) to latest (0)
			StockData sd = new StockData();
			sd.setSymbol(symbol);
			sd.setSeries("EQ");
			sd.setDate(startDate.plusDays(i));
			double changePercent = 0;
			int cyclePosition = (days - 1 - i) % 25; // Reverse cycle position
			if (cyclePosition < 10) {
				changePercent = 0.05; // +5% for first 10 days (from oldest)
			} else if (cyclePosition < 15) {
				changePercent = -0.005; // -0.5% for next 5 days
			} else {
				changePercent = 0.05; // +5% for last 10 days
			}
			double volatility = (Math.random() * 2 - 1) * 0.01; // ±1% random noise
			double open = basePrice * (1 + changePercent + volatility);
			double close = open * (1 + changePercent + (Math.random() * 2 - 1) * 0.01);
			double high = Math.max(open, close) * (1 + Math.random() * 0.02); // ±2%
			double low = Math.min(open, close) * (1 - Math.random() * 0.02); // ±2%
			long volume = (long) (100000 + (days - 1 - i) * 5000 + Math.random() * 50000); // Increasing trend
			sd.setOpen(parseDouble(open));
			sd.setClose(parseDouble(close));
			sd.setLow(parseDouble(low));
			sd.setHigh(parseDouble(high));
			sd.setVolume(volume);
			data.add(sd);
			basePrice = close; // Update base for next day (moving forward in time)
		}
		return data; // Latest data at index 0, oldest at index 59
	}

	private List<Double> calculateMovingAverage(List<StockData> data, int period) {
		List<Double> ma = new ArrayList<>();
		for (int i = 0; i < data.size(); i++) {
			if (i < period - 1) {
				ma.add(0.0);
			} else {
				double sum = 0;
				for (int j = i - period + 1; j <= i; j++) {
					sum += data.get(j).getClose();
				}
				ma.add(parseDouble(sum / period));
			}
		}
		return ma;
	}

	private double parseDouble(double value) {
		return Double.parseDouble(String.format("%.2f", value));
	}
}