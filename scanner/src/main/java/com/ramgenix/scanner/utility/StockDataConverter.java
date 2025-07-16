package com.ramgenix.scanner.utility;

import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.ramgenix.scanner.entity.StockData;

@Service
public class StockDataConverter {

	public Map<String, List<StockData>> convertToWeeklyData(Map<String, List<StockData>> stockDataMap) {
		Map<String, List<StockData>> stockDataWeeklyMap = new ConcurrentHashMap<>();

		if (stockDataMap == null || stockDataMap.isEmpty()) {
			return stockDataWeeklyMap;
		}

		for (Map.Entry<String, List<StockData>> entry : stockDataMap.entrySet()) {
			String symbol = entry.getKey();
			List<StockData> dailyData = entry.getValue();
			List<StockData> weeklyData = new ArrayList<>();

			if (dailyData == null || dailyData.isEmpty()) {
				stockDataWeeklyMap.put(symbol, weeklyData);
				continue;
			}

			// Group daily data by week
			Map<Integer, List<StockData>> weekGroups = new ConcurrentHashMap<>();
			WeekFields weekFields = WeekFields.ISO;

			for (StockData data : dailyData) {
				if (data.getDate() == null) {
					continue; // Skip if date is null
				}
				int weekOfYear = data.getDate().get(weekFields.weekOfYear());
				int year = data.getDate().getYear();
				int weekKey = year * 100 + weekOfYear; // Unique key for year-week
				weekGroups.computeIfAbsent(weekKey, k -> new ArrayList<>()).add(data);
			}

			// Process each week group
			List<Integer> sortedWeekKeys = new ArrayList<>(weekGroups.keySet());
			sortedWeekKeys.sort((a, b) -> b - a); // Descending order for most recent week first

			for (Integer weekKey : sortedWeekKeys) {
				List<StockData> weekDataList = weekGroups.get(weekKey);
				// Sort by date descending to get latest day first
				weekDataList.sort((a, b) -> b.getDate().compareTo(a.getDate()));

				// Aggregate OHLCV
				StockData weeklyStockData = new StockData(); // Renamed to avoid conflict
				weeklyStockData.setSymbol(symbol);
				weeklyStockData.setSeries(weekDataList.get(0).getSeries()); // Use latest day's series
				weeklyStockData.setDate(weekDataList.get(0).getDate()); // Use latest day's date
				weeklyStockData.setSwingType(weekDataList.get(0).getSwingType()); // Use latest day's swingType
				weeklyStockData.setPurpleDotType(weekDataList.get(0).getPurpleDotType()); // Use latest day's
																							// purpleDotType

				double high = Double.MIN_VALUE;
				double low = Double.MAX_VALUE;
				double volume = 0;

				// Find first trading day (earliest date) for open
				StockData firstDay = weekDataList.get(weekDataList.size() - 1); // Earliest date
				weeklyStockData.setOpen(firstDay.getOpen());

				// Aggregate high, low, volume, and set close
				for (StockData day : weekDataList) {
					high = Math.max(high, day.getHigh());
					low = Math.min(low, day.getLow());
					volume += day.getVolume();
				}
				weeklyStockData.setHigh(high);
				weeklyStockData.setLow(low);
				weeklyStockData.setVolume(volume);
				weeklyStockData.setClose(weekDataList.get(0).getClose()); // Latest day's close

				weeklyData.add(weeklyStockData); // Add to the list
			}

			stockDataWeeklyMap.put(symbol, weeklyData);
		}

		return stockDataWeeklyMap;
	}

	public void printStockData(Map<String, List<StockData>> stockDataMap) {
		if (stockDataMap == null || stockDataMap.isEmpty()) {
			System.out.println("No stock data available.");
			return;
		}

		for (Map.Entry<String, List<StockData>> entry : stockDataMap.entrySet()) {
			String symbol = entry.getKey();
			List<StockData> dataList = entry.getValue();

			if (!symbol.equals("TITAN")) {
				continue;
			}

			System.out.printf("Stock Symbol: %s%n", symbol);
			if (dataList == null || dataList.isEmpty()) {
				System.out.println("  No data available for this symbol.");
				continue;
			}

			for (int i = 0; i < dataList.size(); i++) {
				String dateLabel = (i == 0) ? "Latest Date" : (i == 1) ? "Yesterday" : "Day -" + i;
				StockData data = dataList.get(i);
				System.out.printf(
						"  %s: Date: %s, Series: %s, Open: %.2f, High: %.2f, Low: %.2f, Close: %.2f, Volume: %.0f, Swing: %s, PurpleDot: %s%n",
						dateLabel, data.getDate(), data.getSeries(), data.getOpen(), data.getHigh(), data.getLow(),
						data.getClose(), data.getVolume(), data.getSwingType(), data.getPurpleDotType());
			}
			System.out.println(); // Empty line for readability
		}
	}

	public void printWeeklyStockData(Map<String, List<StockData>> stockDataWeeklyMap) {
		if (stockDataWeeklyMap == null || stockDataWeeklyMap.isEmpty()) {
			System.out.println("No weekly stock data available.");
			return;
		}

		for (Map.Entry<String, List<StockData>> entry : stockDataWeeklyMap.entrySet()) {
			String symbol = entry.getKey();
			List<StockData> weeklyDataList = entry.getValue();

			if (!symbol.equals("TITAN")) {
				continue;
			}

			System.out.printf("Stock Symbol (Weekly): %s%n", symbol);
			if (weeklyDataList == null || weeklyDataList.isEmpty()) {
				System.out.println("  No weekly data available for this symbol.");
				continue;
			}

			for (int i = 0; i < weeklyDataList.size(); i++) {
				String weekLabel = (i == 0) ? "Latest Week" : "Week -" + i;
				StockData data = weeklyDataList.get(i);
				System.out.printf(
						"  %s: Date: %s, Series: %s, Open: %.2f, High: %.2f, Low: %.2f, Close: %.2f, Volume: %.0f, Swing: %s, PurpleDot: %s%n",
						weekLabel, data.getDate(), data.getSeries(), data.getOpen(), data.getHigh(), data.getLow(),
						data.getClose(), data.getVolume(), data.getSwingType(), data.getPurpleDotType());
			}
			System.out.println(); // Empty line for readability
		}
	}

}