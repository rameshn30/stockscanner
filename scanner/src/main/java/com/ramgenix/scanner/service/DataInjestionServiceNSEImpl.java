package com.ramgenix.scanner.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.ramgenix.scanner.entity.BBValues;
import com.ramgenix.scanner.entity.FinancialInstrument;
import com.ramgenix.scanner.entity.Pattern;
import com.ramgenix.scanner.entity.SectorAnalysisResult;
import com.ramgenix.scanner.entity.StockData;
import com.ramgenix.scanner.entity.StockDataInfo;
import com.ramgenix.scanner.entity.StockMaster;
import com.ramgenix.scanner.repository.PatternRepository;
import com.ramgenix.scanner.repository.StockMasterRepository;
import com.ramgenix.scanner.utility.StockCalculationUtility;

import io.micrometer.common.util.StringUtils;

@Service
public class DataInjestionServiceNSEImpl {

	private static final Logger LOG = LoggerFactory.getLogger(DataInjestionServiceNSEImpl.class);
	private static final String BASE_DIR = "C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\";
	private static final double MIN_POST_BREAKOUT_MOVE_PERCENT = 7.0;
	private static final double RETRACEMENT_TOLERANCE_PERCENT = 5.0; // +/- tolerance around the target
	private static final int BREAKOUT_DAYS_WINDOW = 50;
	private static final int SH_CANDIDATE_20_DAY_LOOKBACK = 20;

	// Market enum
	public enum Market {
		NSE, US
	}

	public static class MarketConfig {
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

	/**
	 * Simple generic Pair class to return two values from a method.
	 */
	private static class Pair<K, V> {
		private final K key;
		private final V value;

		public Pair(K key, V value) {
			this.key = key;
			this.value = value;
		}

		public K getKey() {
			return key;
		}

		public V getValue() {
			return value;
		}
	}

	// You can make this a static nested class within your StockAnalyzer or a
	// separate class.
	static class SwingPoint {
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

	// Assuming SwingPoint class is already defined

	static class Trendline {
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

	private final Map<Market, MarketConfig> marketConfigs;

	Set<String> hammerStrings = new HashSet<>();
	Set<String> bullEngulfStrings = new HashSet<>();
	Set<String> greenRedStrings = new HashSet<>();
	Set<String> shootingStarStrings = new HashSet<>();
	Set<String> bearEngulfStrings = new HashSet<>();
	Set<String> redGreenStrings = new HashSet<>();
	Set<String> nfoStocks = new HashSet<>();
	Set<String> goldenStocks = new HashSet<>();
	Set<String> etfStocks = new HashSet<>();
	List<String> dates = new ArrayList<>();
	Map<String, String> symbolToWatchlistMap = new HashMap<>();

	static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

	LocalDate processingDate;
	private final PatternRepository patternRepository;
	private final StockMasterRepository stockMasterRepository;
	private final Map<String, String> patternToFileNameMap;

	@Autowired
	public DataInjestionServiceNSEImpl(PatternRepository patternRepository,
			StockMasterRepository stockMasterRepository) {
		this.patternRepository = patternRepository;
		this.stockMasterRepository = stockMasterRepository;
		this.patternToFileNameMap = new HashMap<>();
		patternToFileNameMap.put("BurstRetracement", "BurstRetracement");
		patternToFileNameMap.put("SuperPatterns", "SuperPatterns");
		patternToFileNameMap.put("Hammer", "1_Hammer");
		patternToFileNameMap.put("NearSupport", "NearSupport");
		patternToFileNameMap.put("BreakoutBars", "ResistanceTurnedSupport");
		patternToFileNameMap.put("BULL_FLAG", "BullFlag");
		patternToFileNameMap.put("AscendingTriangle", "AscendingTriangle");
		patternToFileNameMap.put("UpDay", "UpDay");

		this.marketConfigs = new HashMap<>();
		marketConfigs.put(Market.NSE,
				new MarketConfig("C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\dates.txt",
						"C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\NSE\\BhavCopy_NSE_CM_0_0_0_",
						"C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\newhtml\\",
						"C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\", "India"));
		marketConfigs.put(Market.US,
				new MarketConfig("C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\datesus.txt",
						"C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\NYSE\\",
						"C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\newhtmlus\\",
						"C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\US\\", "US"));

	}

	BBValues bbValues = null;
	Map<String, List<StockData>> stockDataMap = new ConcurrentHashMap<>();

	Map<String, List<Pattern>> patternResults = new ConcurrentHashMap<>();
	Set<String> patternResultsSet = new HashSet<>();
	String outputFilePath = "C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\newhtml\\";
	private String stockchartsurl = "p=D&yr=0&mn=6&dy=0&i=t4382950370c&r=1751944099189";
	Set<String> inputWatchList = new HashSet<>();
	String watchlist = "";

	public String processLatestData(Market market, String watchlist) {
		this.watchlist = watchlist;
		MarketConfig config = marketConfigs.get(market);
		if (config == null) {
			LOG.error("Invalid market: {}", market);
			return null;
		}

		// Read watchlist if provided
		if (!StringUtils.isEmpty(watchlist)) {
			String filePath = config.watchlistDirectory + "Watchlist-" + watchlist + ".txt";
			try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
				String line;
				inputWatchList.clear();
				while ((line = reader.readLine()) != null) {
					inputWatchList.add(line.trim());
				}
			} catch (IOException e) {
				LOG.error("Error reading watchlist file {}: {}", filePath, e.getMessage());
			}
		}

		return processLatestData(market);
	}

	private String processLatestData(Market market) {
		stockDataMap.clear();
		stockDataMap = prepareDataForProcessing(market);

		MarketConfig config = marketConfigs.get(market);
		stockDataMap.forEach((symbol, stockDataList) -> {
			if (inputWatchList.isEmpty() || inputWatchList.contains(symbol)) {

				markSwingHighsAndLows(symbol, stockDataList);
				/*
				 * List<SwingPoint> refinedHighs =
				 * getRefinedDescendingSwingHighs(stockDataList);
				 * 
				 * System.out.println("\nStep 3: Generating potential descending trendlines..."
				 * ); // You'll need to choose sensible values for these parameters: // A
				 * typical descending trendline has a negative slope. // -5.0 degrees is a
				 * gentle downward slope. -45.0 is much steeper. // 0.5% minimum price drop
				 * ensures the two points are meaningfully different. double
				 * minSlopeAngleDegrees = -5.0; // Example: at least 5 degrees downward slope
				 * double minPriceDropPercent = 0.5; // Example: at least 0.5% price drop
				 * between points
				 * 
				 * List<Trendline> potentialTrendlines =
				 * generateDescendingTrendlines(refinedHighs, minSlopeAngleDegrees,
				 * minPriceDropPercent); System.out .println("Found " +
				 * potentialTrendlines.size() + " potential trendlines (before validation):");
				 * for (Trendline tl : potentialTrendlines) { System.out.println("  " + tl); }
				 */

				bbValues = calculateBBAndMaValues(symbol, stockDataList, 0);

				if (volumeCheck(stockDataList) && priceCheck(stockDataList) && symbolCheck(market, stockDataList)) {

					findVolumeShockers(market, symbol, stockDataList);
					findPurpleDotStocks(market, symbol, stockDataList);
					findGapUpStocks(market, symbol, stockDataList);
					findPowerUpCandleStocks(market, symbol, stockDataList);

				}

				boolean goldenStocksOnly = false;
				{
					if (findBullishPatternsAdvanced(market, symbol, stockDataList, goldenStocksOnly)) {

					}
					checkBearishPatterns(symbol, stockDataList);
					upday(symbol, stockDataList);
				}
			}
		});

		if (StringUtils.isEmpty(watchlist)) {
			watchList();
		}

		getRestingStocksAfterBurst(market);

		// Process all pattern types
		patternToFileNameMap.forEach((patternName, fileName) -> saveSortedPatterns(market, patternName, fileName));

		return null;
	}

	private boolean findBullishPatternsAdvanced(Market market, String symbol, List<StockData> stockDataList,
			boolean superStrongStocksOnly) {

		// findNearSupportStocks(symbol, stockDataList);
		findBreakoutRetracementStocks(symbol, stockDataList);

		// Constants for bull flag detection parameters
		double BULL_FLAG_FLAGPOLE_BURST_THRESHOLD = 1.19; // Minimum price increase for swing high // (19%)
		double BULL_FLAG_CONSOLIDATION_LOWER_THRESHOLD = 0.80; // Lower boundary for consolidation range (90% of swing
																// // high)
		double BULL_FLAG_CONSOLIDATION_UPPER_THRESHOLD = 1.10; // Upper boundary for consolidation range (110% of swing
																// // high)
		int BULL_FLAG_FLAGPOLE_HIGH_MIN_DISTANCE_FROM_CURRENT = 4; // Minimum bars between flagpole and current data

		int minimumFlagPoleBars = 5;
		int maximumFlagPoleBars = 30;

		if (market == Market.US) {
			BULL_FLAG_FLAGPOLE_BURST_THRESHOLD = 1.10;
			// (10%)
			BULL_FLAG_CONSOLIDATION_LOWER_THRESHOLD = 0.90;
			// range (90% of swing high)
			BULL_FLAG_CONSOLIDATION_UPPER_THRESHOLD = 1.10;
			// range (110% of swing high)
			BULL_FLAG_FLAGPOLE_HIGH_MIN_DISTANCE_FROM_CURRENT = 4;
			minimumFlagPoleBars = 3;
			maximumFlagPoleBars = 10;

		}

		if (market == Market.NSE) {
			BULL_FLAG_FLAGPOLE_BURST_THRESHOLD = 1.08;
			// (10%)
			BULL_FLAG_CONSOLIDATION_LOWER_THRESHOLD = 0.90;
			// range (90% of swing high)
			BULL_FLAG_CONSOLIDATION_UPPER_THRESHOLD = 1.10;
			// range (110% of swing high)
			BULL_FLAG_FLAGPOLE_HIGH_MIN_DISTANCE_FROM_CURRENT = 2;
			minimumFlagPoleBars = 3;
			maximumFlagPoleBars = 10;

		}

		findBullFlagStocksAdvanced(market, symbol, stockDataList, BULL_FLAG_FLAGPOLE_BURST_THRESHOLD,
				BULL_FLAG_CONSOLIDATION_LOWER_THRESHOLD, BULL_FLAG_CONSOLIDATION_UPPER_THRESHOLD,
				BULL_FLAG_FLAGPOLE_HIGH_MIN_DISTANCE_FROM_CURRENT, minimumFlagPoleBars, maximumFlagPoleBars,
				"BULL_FLAG");

		if (!volumeCheck(stockDataList) || !priceCheck(stockDataList))
			return false;

		boolean result = false;

		result = findHammerStocks(symbol, stockDataList);
		if (result)
			return true;

		result = pbs(symbol, stockDataList);

		if (result)
			return true;

		if (!result)
			return false;

		return false;

	}

	private void saveSortedPatterns(Market market, String patternName, String fileName) {
		List<Pattern> patterns = patternResults.get(patternName);
		if (patterns != null && !patterns.isEmpty()) {
			// Sort and save full pattern file
			patterns.sort(new RankComparator());
			savePatternsToFile(market, dates.get(0), patterns, fileName, false, false);

			// Generate incremental patterns
			List<Pattern> incrementalPatterns = getIncrementalPatterns(market, patternName, patterns);
			if (!incrementalPatterns.isEmpty()) {
				incrementalPatterns.sort(new RankComparator());
				// savePatternsToFile(market, dates.get(0), incrementalPatterns, "incremental-"
				// + fileName, false, false);
			}
		}
	}

	private List<Pattern> getIncrementalPatterns(Market market, String patternName, List<Pattern> currentPatterns) {
		Set<String> previousSymbols = getPreviousDaySymbols(market, patternName);
		List<Pattern> incrementalPatterns = new ArrayList<>();

		for (Pattern pattern : currentPatterns) {
			String symbol = pattern.getStockData().getSymbol();
			if (!previousSymbols.contains(symbol)) {
				incrementalPatterns.add(pattern);
			}
		}

		return incrementalPatterns;
	}

	private Set<String> getPreviousDaySymbols(Market market, String patternName) {
		Set<String> symbols = new HashSet<>();
		String fileName = patternToFileNameMap.get(patternName);
		if (fileName == null) {
			LOG.warn("No file name mapping found for pattern: {}", patternName);
			return symbols;
		}

		MarketConfig config = marketConfigs.get(market);
		LocalDate[] previousDates = { processingDate.minusDays(1), processingDate.minusDays(2) };

		for (LocalDate date : previousDates) {
			String formattedDate = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
			String filePath = config.outputDirectory + fileName + "_" + formattedDate + ".html";
			File file = new File(filePath);

			if (file.exists()) {
				try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
					String line;
					while ((line = reader.readLine()) != null) {
						if (line.contains("Symbol: ")) {
							int startIndex = line.indexOf("Symbol: ") + 8;
							int endIndex = line.indexOf(" ", startIndex);
							if (endIndex == -1) {
								endIndex = line.indexOf("<", startIndex);
							}
							if (endIndex == -1) {
								endIndex = line.length();
							}
							if (startIndex < endIndex) {
								String symbol = line.substring(startIndex, endIndex).trim();
								if (!symbol.isEmpty()) {
									symbols.add(symbol);
								}
							}
						}
					}
					LOG.info("Read {} symbols from previous file: {}", symbols.size(), filePath);
					return symbols;
				} catch (IOException e) {
					LOG.error("Error reading previous day's file {}: {}", filePath, e.getMessage());
				}
			}
		}

		LOG.info("No previous file found for pattern {} in market {}, treating all patterns as incremental",
				patternName, market);
		return symbols;
	}

	private boolean symbolCheck(Market market, List<StockData> stockDataList) {
		if (market == Market.NSE) {
			for (StockData sd : stockDataList) {
				if (!"EQ".equals(sd.getSeries()) && !"BE".equals(sd.getSeries())) {
					return false;
				}
			}
		}
		// For US, skip series check unless specific series values are required
		return !isETFStock(stockDataList.get(0).getSymbol());
	}

	private Map<String, List<StockData>> prepareDataForProcessing(Market market) {
		hammerStrings.clear();
		bullEngulfStrings.clear();
		greenRedStrings.clear();
		shootingStarStrings.clear();
		bearEngulfStrings.clear();
		redGreenStrings.clear();
		patternResults.clear();
		dates.clear();
		bbValues = null;

		Map<String, List<StockData>> stockDataMap = new HashMap<>();
		MarketConfig config = marketConfigs.get(market);
		if (config == null) {
			LOG.error("No configuration found for market: {}", market);
			return stockDataMap;
		}

		// Define formatters
		DateTimeFormatter nseFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH);
		DateTimeFormatter usFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH);
		DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

		// Read dates from date file
		List<String> rawDates = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(config.dateFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains("#") || line.trim().isEmpty())
					continue;
				rawDates.add(line.trim());
			}
		} catch (IOException e) {
			LOG.error("Error reading date file {}: {}", config.dateFile, e.getMessage());
			return stockDataMap;
		}

		// Parse dates
		for (String rawDate : rawDates) {
			try {
				LocalDate date = market == Market.NSE ? LocalDate.parse(rawDate.toLowerCase(), nseFormatter)
						: LocalDate.parse(rawDate, usFormatter);
				dates.add(date.format(outputFormatter));
			} catch (DateTimeParseException e) {
				LOG.error("Failed to parse date '{}' for market {}: {}", rawDate, market, e.getMessage());
			}
		}

		if (dates.isEmpty()) {
			LOG.error("No valid dates found in {}", config.dateFile);
			return stockDataMap;
		}

		// Set processingDate for the first valid date
		try {
			processingDate = LocalDate.parse(dates.get(0), outputFormatter);
			patternRepository.deleteByDate(processingDate);
		} catch (DateTimeParseException e) {
			LOG.error("Failed to parse processing date '{}': {}", dates.get(0), e.getMessage());
			return stockDataMap;
		}

		// Load stock data
		for (String date : dates) {
			GetStockDataFromCSV(date, stockDataMap, market);
		}

		return stockDataMap;
	}

	private void GetStockDataFromCSV(String date, Map<String, List<StockData>> stockDataMap, Market market) {
		MarketConfig config = marketConfigs.get(market);
		List<String> csvFiles = market == Market.NSE ? List.of(config.csvPath + date + "_F_0000.csv")
				: List.of(config.csvPath + "NYSE_" + date + ".csv", config.csvPath + "NASDAQ_" + date + ".csv");

		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
		LocalDate parsedDate;
		try {
			parsedDate = LocalDate.parse(date, formatter);
		} catch (DateTimeParseException e) {
			LOG.error("Invalid date format for CSV processing: {}", date);
			return;
		}

		for (String csvFile : csvFiles) {

			try (BufferedReader file = new BufferedReader(new FileReader(csvFile))) {

				if (market == Market.US) {

					String line;
					file.readLine();

					while ((line = file.readLine()) != null) {
						String[] data = line.split(",");
						if (data.length >= 6) {
							String symbol = data[0];
							String series = data[1];
							double open = Double.parseDouble(data[2]);
							double high = Double.parseDouble(data[3]);
							double low = Double.parseDouble(data[4]);
							double close = Double.parseDouble(data[5]);
							long totalTradedQty = Long.parseLong(data[6]);

							StockData stockData = new StockData();
							stockData.setSymbol(symbol);
							stockData.setSeries(series);
							stockData.setClose(close);
							stockData.setHigh(high);
							stockData.setLow(low);
							stockData.setOpen(open);
							stockData.setVolume(totalTradedQty);
							stockData.setSwingType("");

							DateTimeFormatter formatterDate = new DateTimeFormatterBuilder().parseCaseInsensitive()
									.appendPattern("uuuuMMdd").toFormatter(Locale.ENGLISH);

							String lowercaseDateString = date.toLowerCase();
							LocalDate localDate = LocalDate.parse(lowercaseDateString, formatterDate);
							stockData.setDate(localDate);

							stockDataMap.computeIfAbsent(symbol, k -> new ArrayList<>()).add(stockData);

						} else {
							System.out.println("Invalid number of fields in the line.");
						}

					}

				}

				else {
					// Create a CsvToBeanBuilder instance
					CsvToBean<FinancialInstrument> csvToBean = new CsvToBeanBuilder<FinancialInstrument>(file)
							.withType(FinancialInstrument.class).withIgnoreLeadingWhiteSpace(true).build();

					// Convert the CSV data into a list of FinancialInstrument objects
					List<FinancialInstrument> financialInstruments = csvToBean.parse();
					if (financialInstruments != null) {
						// financialInstruments.forEach(System.out::println);

						for (FinancialInstrument fi : financialInstruments) {

							String symbol = fi.getTckrSymb();

							StockData stockData = new StockData();
							stockData.setSymbol(fi.getTckrSymb());
							stockData.setSeries(fi.getSctySrs());
							stockData.setClose(StockCalculationUtility.parseDouble(fi.getClsPric()));
							stockData.setHigh(StockCalculationUtility.parseDouble(fi.getHghPric()));
							stockData.setLow(StockCalculationUtility.parseDouble(fi.getLwPric()));
							stockData.setOpen(StockCalculationUtility.parseDouble(fi.getOpnPric()));
							stockData.setVolume(StockCalculationUtility.parseDouble(fi.getTtlTradgVol()));
							stockData.setSwingType("");

							String lowercaseDateString = date.toLowerCase();
							// LocalDate localDate = LocalDate.parse(lowercaseDateString, formatter);
							stockData.setDate(DateFormatChecker.processDate(lowercaseDateString));

							if (!fi.getSctySrs().equals("EQ") && !fi.getSctySrs().equals("BE")) {
								continue;
							}

							if (symbol.contains("ETF") || symbol.contains("BEES") || isETFStock(symbol)
									|| symbol.contains("LIQUID") || symbol.endsWith("LIQ") && symbol.endsWith("GOLD")
									|| symbol.endsWith("ADD") || symbol.contains("NIFTY")
									|| symbol.endsWith("SENSEX")) {
								continue;
							}

							stockDataMap.computeIfAbsent(symbol, k -> new ArrayList<>()).add(stockData);

						}
					}

				}
			} catch (IOException e) {
				// e.printStackTrace();
				return;
			}

		}

	}

	private void checkBearishPatterns(String symbol, List<StockData> stockDataList) {
		bbValues = calculateBBAndMaValues(symbol, stockDataList, 0);

		if (!volumeCheck(stockDataList) || !priceCheckBearish(stockDataList))
			return;

		if (!isNFOStock(symbol)) {
			return;
		}

		boolean result = false;

		downDay(symbol, stockDataList);

		result = shootingStar(symbol, stockDataList);
		if (result)
			return;

		if (result)
			return;
		result = darkCloud(symbol, stockDataList);
		if (result)
			return;
		result = bearCOG(symbol, stockDataList);
		if (result)
			return;
		result = pss(symbol, stockDataList);
		if (result)
			return;
		result = redGreen(symbol, stockDataList);
		if (result)
			return;
		result = bbred(symbol, stockDataList);
		if (result)
			return;

	}

	private boolean findPowerUpCandleStocks(Market market, String symbol, List<StockData> stockDataList) {
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(market); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		double changePct = ((sd.close_0 - sd.close_1) / sd.close_1) * 100;

		if (changePct > 7) {
			String type = "PowerUpCandle";
			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).country(config.country).build();

			pattern.setRank((int) Math.round(changePct));
			pattern.setChangePct(new BigDecimal(changePct));
			String rankStr = "changePct:" + changePct + " Volume 0:" + sd.volume_0 + " Volume 1: " + sd.volume_1;
			pattern.setRankStr(rankStr);
			pattern.setDate(processingDate);

			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			savePattern(pattern, type, sd);
			return true;
		}
		return false;
	}

	private boolean findGapUpStocks(Market market, String symbol, List<StockData> stockDataList) {
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(market); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		double gapPercentage = ((sd.open_0 - sd.close_1) / sd.close_1) * 100;

		if (gapPercentage > 3.5 && sd.volume_0 > sd.volume_1 && sd.close_0 > sd.open_0 && sd.close_0 > sd.close_1) {
			String type = "Gapup";
			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).country(config.country).build();

			pattern.setRank((int) Math.round(gapPercentage));
			pattern.setGapupPct(new BigDecimal(gapPercentage));
			String rankStr = "Gapup:" + gapPercentage + " Volume 0:" + sd.volume_0 + " Volume 1: " + sd.volume_1;
			pattern.setRankStr(rankStr);
			pattern.setDate(processingDate);

			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			savePattern(pattern, type, sd);
			return true;
		}
		return false;
	}

	private boolean findHammerStocks(String symbol, List<StockData> stockDataList) {
		String type = "Hammer";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		if (sd.tail_0 >= (2 * sd.body_0) && sd.low_0 <= sd.low_1 && (sd.tail_0 / sd.head_0) > 2) {
			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).country(config.country).build();

			pattern.setRank(calculateADR(stockDataList, 20));
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			hammerStrings.add(symbol);
			savePattern(pattern, type, sd);
			return true;
		}
		return false;
	}

	private boolean findNearSupportStocks(String symbol, List<StockData> stockDataList) {
		if (stockDataList == null || stockDataList.isEmpty() || !volumeCheck(stockDataList)
				|| !priceCheck(stockDataList)) {
			return false;
		}
		String type = "NearSupport";
		double proximityThreshold = 2.0;
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		if (stockDataList.size() < 3) {
			return false;
		}

		double latestClose = stockDataList.get(0).getClose();
		if (latestClose == 0.0) {
			return false;
		}

		double supportLevel = 0.0;
		double percentageDiff = Double.MAX_VALUE;
		LocalDate supportDate = null;

		for (int i = 1; i < stockDataList.size(); i++) {
			StockData stockData = stockDataList.get(i);
			if ("L".equals(stockData.getSwingType())) {
				double lowPrice = stockData.getLow();
				if (lowPrice < latestClose) {
					percentageDiff = Math.abs((latestClose - lowPrice) / lowPrice) * 100;
					if (percentageDiff > proximityThreshold) {
						return false;
					}
					supportLevel = lowPrice;
					supportDate = stockData.getDate();
					break;
				}
			}
		}

		if (supportLevel == 0.0) {
			return false;
		}

		Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
		String rankStr = " DistanceToSupport:" + String.format("%.2f%%", percentageDiff) + " SupportLevel:"
				+ String.format("%.2f", supportLevel) + " SupportDate:"
				+ (supportDate != null ? supportDate.toString() : "N/A") + " LatestClose:"
				+ String.format("%.2f", latestClose);
		pattern.setRankStr(rankStr);
		patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		return true;
	}

	/**
	 * Finds the MOST RECENT "breakout retracement stock" where the price retraces
	 * back to its original breakout level (shCandidate.getHigh()), with an
	 * additional check for "fresh" retracement path. The original swing high
	 * candidate must also be the highest high in the 20 days preceding it.
	 *
	 * The input list of StockData is expected to be in reverse chronological order
	 * (index 0 is the latest data, last index is the oldest data).
	 * 
	 * @param symbol
	 *
	 * @param stockDataList A list of StockData objects for a specific stock,
	 *                      ordered from latest to oldest.
	 * @return A list containing a single StockData object representing the most
	 *         recent original swing high that meets all criteria, or an empty list
	 *         if none is found.
	 */
	public boolean findBreakoutRetracementStocks(String symbol, List<StockData> stockDataList) {

		final String patternType = "BreakoutBars";

		if (!symbol.equals("RPOWER")) {
			// return false;
		}

		// Basic validation: Ensure enough data for all checks.
		if (stockDataList == null
				|| stockDataList.size() < (BREAKOUT_DAYS_WINDOW - 1) + SH_CANDIDATE_20_DAY_LOOKBACK + 1) {
			// System.out.println("Not enough data to perform analysis. Need at least "
			// + ((BREAKOUT_DAYS_WINDOW - 1) + SH_CANDIDATE_20_DAY_LOOKBACK + 1) + "
			// days.");
			return false;
		}

		double currentClose = stockDataList.get(0).getClose();

		// Outer loop: Iterate through potential original swing high candidates
		// (`shCandidate`).
		// 'i' goes from index 5 (6th most recent day) up to BREAKOUT_DAYS_WINDOW - 1
		// (49th index, 50th day ago).
		for (int i = 5; i < BREAKOUT_DAYS_WINDOW; i++) {
			StockData shCandidate = stockDataList.get(i);

			if ("H".equals(shCandidate.getSwingType())) {
				double originalSwingHighPrice = shCandidate.getHigh();

				// Check: shCandidate must be the highest high in the 20 days prior to its
				// index.
				boolean isHighestInPrevious20Days = true;
				for (int k = i + 1; k < Math.min(stockDataList.size(), i + SH_CANDIDATE_20_DAY_LOOKBACK + 1); k++) {
					if (stockDataList.get(k).getHigh() >= shCandidate.getHigh()) {
						isHighestInPrevious20Days = false;
						break;
					}
				}

				if (isHighestInPrevious20Days) {
					int breakoutCandleIndex = -1;

					for (int j = i - 1; j >= 0; j--) {
						StockData currentCandle = stockDataList.get(j);
						if (currentCandle.getClose() > originalSwingHighPrice) {
							breakoutCandleIndex = j;
							break;
						}
					}

					if (breakoutCandleIndex != -1) {
						boolean moved10PercentUp = false;
						for (int k = breakoutCandleIndex; k >= 0; k--) {
							if (stockDataList.get(k).getClose() >= originalSwingHighPrice
									* (1 + MIN_POST_BREAKOUT_MOVE_PERCENT / 100)) {
								moved10PercentUp = true;
								break;
							}
						}

						if (moved10PercentUp) {
							Optional<Pair<StockData, Integer>> highestPostBreakoutSwingHighOptWithIndex = findHighestSwingHighAfterIndex(
									symbol, stockDataList, breakoutCandleIndex);

							if (highestPostBreakoutSwingHighOptWithIndex.isPresent()) {
								Pair<StockData, Integer> pair = highestPostBreakoutSwingHighOptWithIndex.get();
								int highestPostBreakoutSwingHighIndex = pair.getValue();

								// NEW CHECK: After the breakout candle, if any candle from its index
								// till the highestPostBreakoutHighIndex has closed below the breakout candle's
								// close,
								// then this breakout is invalid.
								boolean isValidBreakoutPath = true;
								// Get the close price of the breakout candle for comparison.
								double breakoutClosePrice = stockDataList.get(breakoutCandleIndex).getClose();

								// Iterate from the breakout candle's index down to the
								// highestPostBreakoutHighIndex.
								// This covers all candles in the path from breakout to the highest point
								// achieved.
								for (int pathCheckIndex = breakoutCandleIndex; pathCheckIndex >= highestPostBreakoutSwingHighIndex; pathCheckIndex--) {
									// If any candle in this path closed below the breakout candle's close, it's
									// invalid.
									if (stockDataList.get(pathCheckIndex).getClose() < breakoutClosePrice) {
										isValidBreakoutPath = false;
										break; // Found an invalid close, no need to check further.
									}
								}

								if (isValidBreakoutPath) {

									// Check: Price should not have retraced back to original breakout level
									// except for the last 3 bars (indices 0, 1, 2).
									boolean freshRetracementPath = true;
									for (int retracementCheckIndex = highestPostBreakoutSwingHighIndex; retracementCheckIndex >= 3; retracementCheckIndex--) {
										if (stockDataList.get(retracementCheckIndex)
												.getClose() <= originalSwingHighPrice) {
											freshRetracementPath = false;
											break;
										}
									}

									if (freshRetracementPath) {
										// CRITICAL CHANGE HERE: targetRetracementPrice is now directly
										// shCandidate.getHigh()
										// as the target is to retrace *back to* the breakout level itself.
										double targetRetracementPrice = shCandidate.getHigh();

										// The tolerance is applied around this target price.
										double lowerBound = targetRetracementPrice
												* (1 - RETRACEMENT_TOLERANCE_PERCENT / 100);
										double upperBound = targetRetracementPrice
												* (1 + RETRACEMENT_TOLERANCE_PERCENT / 100);

										if (currentClose >= lowerBound && currentClose <= upperBound) {
											StockDataInfo sd = new StockDataInfo(stockDataList);
											Pattern pattern = Pattern.builder().patternName(patternType)
													.stockData(stockDataList.get(0)).head(sd.head_0).tail(sd.tail_0)
													.body0(sd.body_0).build();

											double actualRetracementPercent = ((originalSwingHighPrice - currentClose)
													/ originalSwingHighPrice) * 100;

											String breakoutDateStr = stockDataList.get(breakoutCandleIndex).getDate()
													.toString();

											// Construct the rankStr directly using String.format.
											// Displaying dates for shCandidate, breakout, and highestPostBreakoutHigh.
											String rankStr = String.format(
													"BO_Level: %.2f (Date: %s), Breakout_Date: %s, HPH: %.2f (Date: %s), Retracement_Pct: %.2f%%",
													originalSwingHighPrice, shCandidate.getDate().toString(), // Date of
																												// the
																												// shCandidate
																												// (original
																												// breakout
																												// level)
													breakoutDateStr, // Date of the actual breakout
													pair.getKey().getHigh(), // High of the highestPostBreakoutSwingHigh
													pair.getKey().getDate().toString(), // Date of the
																						// highestPostBreakoutSwingHigh
													actualRetracementPercent);

											pattern.setRankStr(rankStr);
											pattern.setRank(calculateADR(stockDataList, 20));
											patternResults.computeIfAbsent(patternType, k -> new ArrayList<>())
													.add(pattern);
											return true;
										}
									}

								}
							}
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * Helper method to find the highest swing high from a specified starting index
	 * (chronologically older) up to the latest data (index 0).
	 */
	private Optional<Pair<StockData, Integer>> findHighestSwingHighAfterIndex(String symbol,
			List<StockData> stockDataList, int startIndex) {
		StockData highestSwingHigh = null;
		int highestSwingHighIndex = -1;

		for (int k = startIndex; k >= 0; k--) {
			StockData candle = stockDataList.get(k);
			if ("H".equals(candle.getSwingType())) {
				if (highestSwingHigh == null || candle.getHigh() > highestSwingHigh.getHigh()) {
					highestSwingHigh = candle;
					highestSwingHighIndex = k;
				}
			}
		}
		if (highestSwingHigh != null) {
			return Optional.of(new Pair<>(highestSwingHigh, highestSwingHighIndex));
		} else {
			return Optional.empty();
		}
	}

	// Removes all flagpole entries with the specified swing low index from the
	// flagpole list
	private void removeSwingLowFromFlagPoleList(List<int[]> flagpoleList, int lowIndex) {
		flagpoleList.removeIf(pair -> pair[0] == lowIndex);
	}

	// Detects bull flag patterns for a given stock symbol and stock data list
	private boolean findBullFlagStocksAdvanced(Market market, String symbol, List<StockData> stockDataList,
			double flagpoleThreshold, double consolidationLowerThreshold, double consolidationUpperThreshold,
			int minimumSwingHighIndex, int minimumFlagPoleBars, int maximumFlagPoleBars, String patternType) {
		// Validate input data: check for null, empty list, price, and volume conditions
		if (stockDataList == null || stockDataList.isEmpty() || !volumeCheck(stockDataList)) {
			return false;
		}

		// Optional filter for testing specific stock, currently disabled
		if (!symbol.equals("GEPIL")) {
			// return false;
		}

		// Create StockDataInfo to extract static metrics for the entire dataset
		StockDataInfo sd = new StockDataInfo(stockDataList);

		BBValues bb = calculateBBAndMaValues(symbol, stockDataList, 0);
		if (!(bb != null && bb.getMa_20() > bb.getMa_50())) {
			return false;
		}

		double adr = calculateADR(stockDataList, 20);
		if (adr < 3.5) {
			return false;
		}

		if (market == Market.US && (sd.close_0 < 20 || sd.close_0 > 1000)) {
			return false;
		}

		List<Pattern> patternsFromDB = getPatternsFromDB(market);
		if (patternsFromDB == null || patternsFromDB.isEmpty()) {
			return false;
		}

		// Initialize variables for flagpole detection and pattern storage
		List<int[]> flagpoleList = new ArrayList<>();

		// Step 1: Identify all swing lows in the dataset
		List<Integer> swingLowIndices = new ArrayList<>();
		for (int i = 0; i < stockDataList.size(); i++) {
			if ("L".equals(stockDataList.get(i).getSwingType())) {
				swingLowIndices.add(i);
			}
		}

		// Step 2: For each swing low, find swing highs with sufficient price increase
		// and valid duration
		for (int lowIndex : swingLowIndices) {
			int prevLowIndex = -1; // Track previous swing low to ensure one flagpole per low
			double swingLowPrice = stockDataList.get(lowIndex).getLow();
			// Search backward for swing highs before the swing low (newer indices)
			for (int j = lowIndex - 1; j >= 0; j--) {
				// Invalidate swing low if any prior bar's close is below the swing low's close
				if (stockDataList.get(j).getClose() < stockDataList.get(lowIndex).getLow()) {
					removeSwingLowFromFlagPoleList(flagpoleList, lowIndex);
					break; // Stop processing this swing low
				}
				// Check for swing high with at least the specified price increase from swing
				// low
				if ("H".equals(stockDataList.get(j).getSwingType())
						&& stockDataList.get(j).getHigh() >= swingLowPrice * flagpoleThreshold) {
					// Ensure flagpole duration is within minimum and maximum bounds
					int flagpoleDuration = lowIndex - j;
					if (flagpoleDuration < minimumFlagPoleBars || flagpoleDuration > maximumFlagPoleBars) {
						continue; // Skip if flagpole duration is invalid
					}
					// Add new flagpole for this swing low if none exists
					if (flagpoleList.isEmpty() || prevLowIndex == -1 || prevLowIndex != lowIndex) {
						flagpoleList.add(new int[] { lowIndex, j });
						prevLowIndex = lowIndex;
					}
					// Update flagpole if a higher swing high is found for the same swing low
					else if (prevLowIndex == lowIndex && stockDataList.get(j).getHigh() > stockDataList
							.get(flagpoleList.get(flagpoleList.size() - 1)[1]).getHigh()) {
						flagpoleList.get(flagpoleList.size() - 1)[1] = j;
					}
				}
			}
		}

		// Step 3: Validate bull flags for each flagpole
		double currentPrice = stockDataList.get(0).getClose(); // Use latest bar's close price
		for (int i = 0; i < flagpoleList.size(); i++) {
			int swingHighIndex = flagpoleList.get(i)[1];
			int swingLowIndex = flagpoleList.get(i)[0];

			if (swingHighIndex < minimumSwingHighIndex) {
				continue;
			}

			double flagPoleHighPrice = stockDataList.get(swingHighIndex).getHigh();

			// Skip if current price exceeds swing high, as consolidation should be at or
			// below
			if (currentPrice > flagPoleHighPrice) {
				continue;
			}

			boolean validClosing = true;
			// Validate closing prices from swing high to latest bar against lower
			// consolidation threshold
			for (int j = swingHighIndex - 1; j >= 0; j--) {
				if (stockDataList.get(j).getClose() < flagPoleHighPrice * consolidationLowerThreshold) {
					validClosing = false;
					break;
				}
			}
			if (!validClosing)
				continue;

			// Validate closing prices before swing high against 50% level of flagpole range
			double flagPoleLowPrice = stockDataList.get(swingLowIndex).getLow();
			double fiftyPercentLevel = (flagPoleHighPrice + flagPoleLowPrice) / 2;
			for (int j = swingHighIndex - 1; j >= 0; j--) {
				if (stockDataList.get(j).getClose() < fiftyPercentLevel) {
					validClosing = false;
					break;
				}
			}
			if (!validClosing)
				continue;

			// Check if current price is within consolidation range
			if (currentPrice >= flagPoleHighPrice * consolidationLowerThreshold
					&& currentPrice <= flagPoleHighPrice * consolidationUpperThreshold) {
				// Create Pattern object with static metrics from StockDataInfo
				Pattern pattern = Pattern.builder().patternName(patternType).stockData(stockDataList.get(0))
						.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

				// Calculate metrics for rank string
				double flagpoleSizePercent = ((flagPoleHighPrice - flagPoleLowPrice) / flagPoleLowPrice) * 100;
				int flagpoleDays = swingLowIndex - swingHighIndex;
				double flagSize = flagPoleHighPrice - currentPrice;
				double flagSizePercent = (flagSize / flagPoleHighPrice) * 100;
				double proximityPercent = ((currentPrice - flagPoleHighPrice) / flagPoleHighPrice) * 100;

				// Format rank string with pattern details
				String rankStr = String.format(
						"Flagpole Low Date:%s Low price:%.2f Flagpole High Date:%s High price:%.2f "
								+ "Flagpole Size:%.2f%% Flagpole Days:%d Flag Size:%.2f Flag Size:%.2f%% Proximity to Close:%.2f%% ADR:%.2f",
						stockDataList.get(swingLowIndex).getDate().toString(), flagPoleLowPrice,
						stockDataList.get(swingHighIndex).getDate().toString(), flagPoleHighPrice, flagpoleSizePercent,
						flagpoleDays, flagSize, flagSizePercent, proximityPercent, adr);
				pattern.setRank(swingHighIndex * -1); // Prioritize newer highs for ascending sort
				pattern.setRankStr(rankStr);

				// Store pattern in results map and return success
				patternResults.computeIfAbsent(patternType, k -> new ArrayList<>()).add(pattern);
				return true;
			}
		}

		return false;
	}

	private boolean findAscendingTriangleStocks(String symbol, List<StockData> stockDataList, int minimumSwingPoints,
			double resistanceRange, double minimumLowIncrease, int minimumTriangleBars, int maximumTriangleBars,
			String patternType) {
		// Validate input data: check for null, empty list, price, and volume conditions
		if (stockDataList == null || stockDataList.isEmpty() || !priceCheck(stockDataList)
				|| !volumeCheck(stockDataList)) {
			return false;
		}

		// Optional filter for testing specific stock (TSLA), currently disabled
		if (!symbol.equals("TSLA")) {
			// return false;
		}

		// Initialize variables for triangle detection and pattern storage
		List<int[]> trianglePoints = new ArrayList<>();

		// Create StockDataInfo to extract static metrics for the entire dataset
		StockDataInfo sd = new StockDataInfo(stockDataList);

		// Step 1: Identify all swing lows and highs in the dataset
		List<Integer> swingLowIndices = new ArrayList<>();
		List<Integer> swingHighIndices = new ArrayList<>();
		for (int i = 0; i < stockDataList.size(); i++) {
			if ("L".equals(stockDataList.get(i).getSwingType())) {
				swingLowIndices.add(i);
			} else if ("H".equals(stockDataList.get(i).getSwingType())) {
				swingHighIndices.add(i);
			}
		}

		// Step 2: Validate ascending triangle by finding pairs of swing lows where the
		// newer low
		// (lower index, later in time) has a higher price than the older low (higher
		// index, earlier in time)
		for (int i = 0; i < swingLowIndices.size() - 1; i++) {
			int lowIndex1 = swingLowIndices.get(i); // Newer swing low (later in time)
			double lowPrice1 = stockDataList.get(lowIndex1).getLow();
			for (int j = i + 1; j < swingLowIndices.size(); j++) {
				int lowIndex2 = swingLowIndices.get(j); // Older swing low (earlier in time)
				double lowPrice2 = stockDataList.get(lowIndex2).getLow();

				// Ensure the newer swing low (lower index) has a higher price than the older
				// swing low
				// by at least the specified increase to form an ascending support line
				if (lowPrice1 <= lowPrice2 * (1 + minimumLowIncrease)) {
					continue; // Skip if the newer low is not sufficiently higher
				}

				// Ensure triangle duration is within minimum and maximum bounds
				int triangleDuration = Math.abs(lowIndex1 - lowIndex2);
				if (triangleDuration < minimumTriangleBars || triangleDuration > maximumTriangleBars) {
					continue; // Skip if triangle duration is invalid
				}

				// Search for swing highs to form the resistance line, allowing a margin
				int margin = 5; // Allow highs slightly before/after the triangle range
				for (int k = 0; k < swingHighIndices.size(); k++) {
					int highIndex1 = swingHighIndices.get(k);
					if (highIndex1 < lowIndex2 - margin || highIndex1 > lowIndex1 + margin) {
						continue; // High must be within the triangle range (older to newer)
					}
					double highPrice1 = stockDataList.get(highIndex1).getHigh();

					// Find another swing high for resistance
					for (int m = k + 1; m < swingHighIndices.size(); m++) {
						int highIndex2 = swingHighIndices.get(m);
						if (highIndex2 < lowIndex2 - margin || highIndex2 > lowIndex1 + margin) {
							continue; // High must be within the triangle range
						}
						double highPrice2 = stockDataList.get(highIndex2).getHigh();

						// Check if highs are within the specified resistance range
						double avgHighPrice = (highPrice1 + highPrice2) / 2;
						if (Math.abs(highPrice1 - highPrice2) / avgHighPrice > resistanceRange) {
							continue; // Skip if highs are not flat enough
						}

						// Invalidate triangle if any bar after the newer low (lower index) has a close
						// below the newer low's close
						for (int n = lowIndex1 - 1; n >= 0; n--) {
							if (stockDataList.get(n).getClose() < stockDataList.get(lowIndex1).getClose()) {
								removeSwingLowFromTrianglePoints(trianglePoints, lowIndex1);
								break;
							}
						}

						// Store valid triangle points: [lowIndex1, lowIndex2, highIndex1, highIndex2]
						trianglePoints.add(new int[] { lowIndex1, lowIndex2, highIndex1, highIndex2 });
					}
				}
			}
		}

		// Step 3: Validate consolidation within the triangle for each set of points
		double currentPrice = stockDataList.get(0).getClose(); // Use latest bar's close price
		for (int i = 0; i < trianglePoints.size(); i++) {
			int lowIndex1 = trianglePoints.get(i)[0]; // Newer low (later in time)
			int lowIndex2 = trianglePoints.get(i)[1]; // Older low (earlier in time)
			int highIndex1 = trianglePoints.get(i)[2];
			int highIndex2 = trianglePoints.get(i)[3];

			double lowPrice1 = stockDataList.get(lowIndex1).getLow();
			double lowPrice2 = stockDataList.get(lowIndex2).getLow();
			double highPrice1 = stockDataList.get(highIndex1).getHigh();
			double highPrice2 = stockDataList.get(highIndex2).getHigh();

			// Calculate resistance level as average of swing highs
			double resistanceLevel = (highPrice1 + highPrice2) / 2;

			// Calculate support line slope (price increase per bar, positive for ascending)
			double supportSlope = (lowPrice1 - lowPrice2) / (lowIndex1 - lowIndex2);

			boolean validClosing = true;
			// Validate closes stay within triangle: above support line, below resistance
			for (int j = lowIndex2; j >= lowIndex1; j--) { // Iterate from older to newer
				// Calculate support price at index j using linear interpolation
				double barsFromLow2 = j - lowIndex2;
				double supportPrice = lowPrice2 + (supportSlope * barsFromLow2);
				if (stockDataList.get(j).getClose() < supportPrice
						|| stockDataList.get(j).getClose() > resistanceLevel) {
					validClosing = false;
					break;
				}
			}
			if (!validClosing)
				continue;

			// Check if current price is within the triangle's consolidation range
			double latestSupportPrice = lowPrice2 + (supportSlope * (lowIndex1 - lowIndex2));
			if (currentPrice >= latestSupportPrice && currentPrice <= resistanceLevel) {
				// Create Pattern object with static metrics from StockDataInfo
				Pattern pattern = Pattern.builder().patternName(patternType).stockData(stockDataList.get(0))
						.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

				// Calculate metrics for rank string
				int triangleDays = Math.abs(lowIndex1 - lowIndex2);
				double supportSlopePercent = (supportSlope / lowPrice2) * 100;
				double triangleHeight = resistanceLevel - lowPrice2;
				double triangleHeightPercent = (triangleHeight / resistanceLevel) * 100;
				double proximityToResistance = ((currentPrice - resistanceLevel) / resistanceLevel) * 100;

				// Format rank string with triangle details
				String rankStr = String.format(
						"Older Low Date:%s Low Price:%.2f Newer Low Date:%s Low Price:%.2f "
								+ "Resistance Level:%.2f Support Slope:%.2f%% Triangle Days:%d "
								+ "Triangle Height:%.2f Height:%.2f%% Proximity to Resistance:%.2f%%",
						stockDataList.get(lowIndex2).getDate().toString(), lowPrice2,
						stockDataList.get(lowIndex1).getDate().toString(), lowPrice1, resistanceLevel,
						supportSlopePercent, triangleDays, triangleHeight, triangleHeightPercent,
						proximityToResistance);
				pattern.setRank(highIndex1 * -1); // Prioritize newer highs for ascending sort
				pattern.setRankStr(rankStr);

				// Store pattern in results map and return success
				patternResults.computeIfAbsent(patternType, k -> new ArrayList<>()).add(pattern);
				return true;
			}
		}

		// No valid ascending triangle found
		return false;
	}

	// Removes all entries with the specified swing low index from the triangle
	// points list
	private void removeSwingLowFromTrianglePoints(List<int[]> trianglePoints, int lowIndex) {
		trianglePoints.removeIf(pair -> pair[0] == lowIndex || pair[1] == lowIndex);
	}

	private boolean shootingStar(String symbol, List<StockData> stockDataList) {
		String type = "ShootingStar";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;
		int rank = 0;

		if (sd.head_0 >= (1.5 * sd.body_0) && sd.high_0 >= sd.high_1 && (sd.head_0 > sd.tail_0)) {
			Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).tail(sd.tail_0)
					.head(sd.head_0).country(config.country).build();
			if (sd.high_0 > bbValues.getUpper())
				rank++;
			if (sd.high_0 > sd.high_1)
				rank = rank + 2;
			if (sd.close_0 < sd.open_0)
				rank++;
			pattern.setRank(rank);
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			shootingStarStrings.add(symbol);
			return true;
		}
		return false;
	}

	private boolean darkCloud(String symbol, List<StockData> stockDataList) {
		String type = "DarkCloud";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;
		double half = (sd.open_1 + sd.close_1) / 2;

		if (sd.close_0 < sd.open_0 && sd.close_1 > sd.open_1 && sd.close_0 < half && sd.open_0 >= sd.close_1) {
			Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
					.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			if (bearEngulfStrings.contains(symbol)) {
				return true;
			}
			bearEngulfStrings.add(symbol);
			return true;
		}
		return false;
	}

	private boolean bearCOG(String symbol, List<StockData> stockDataList) {
		String type = "BearCOG";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		if (sd.close_0 < sd.open_0 && sd.close_1 > sd.open_1 && sd.close_2 > sd.open_2) {
			Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
					.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			if (bearEngulfStrings.contains(symbol)) {
				return true;
			}
			bearEngulfStrings.add(symbol);
			return true;
		}
		return false;
	}

	private boolean bbred(String symbol, List<StockData> stockDataList) {
		String type = "BBRed";

		StockDataInfo sd = new StockDataInfo(stockDataList);

		if (sd.close_0 < sd.open_0 && sd.body_0 > 5 && sd.body_0 > sd.body_1 && sd.high_0 > bbValues.getUpper()
				&& sd.low_0 < bbValues.getUpper()) {
			Pattern pattern = new Pattern();
			pattern.setPatternName(type);
			pattern.setStockData(stockDataList.get(0));
			pattern.setHead(sd.head_0);
			pattern.setTail(sd.tail_0);
			pattern.setBody0(sd.body_0);

			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);

			return true;
		}

		if (sd.close_0 < sd.open_0 && sd.high_0 > bbValues.getUpper() && sd.low_0 >= bbValues.getUpper()) {
			Pattern pattern = new Pattern();
			pattern.setPatternName(type);
			pattern.setStockData(stockDataList.get(0));
			pattern.setHead(sd.head_0);
			pattern.setTail(sd.tail_0);
			pattern.setBody0(sd.body_0);

			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			return true;
		}

		return false;

	}

	private boolean pbs(String symbol, List<StockData> stockDataList) {
		if (symbol.equals("CMSINFO")) {
			System.out.println("break");
		}
		String type = "PBS";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		boolean pbs = false;

		if (sd.close_0 < bbValues.getMa_50() || bbValues.getMa_20() < bbValues.getMa_50())
			return false;

		if (sd.high_0 <= sd.high_1 && sd.high_1 <= sd.high_2 && sd.low_0 <= sd.low_1 && sd.low_1 <= sd.low_2) {
			pbs = true;
		}

		if ((sd.close_0 <= sd.open_0 && sd.close_1 <= sd.open_1 && sd.close_2 <= sd.open_2) && sd.close_3 > sd.open_3
				&& (sd.close_3 >= bbValues.getMa_20())) {
			pbs = true;
		}

		if (!pbs && (sd.close_0 <= sd.open_0 && sd.close_1 <= sd.open_1 && sd.close_2 <= sd.open_2
				&& sd.close_3 <= sd.open_3) && sd.close_4 > sd.open_4 && (sd.close_4 >= bbValues.getMa_20())) {
			pbs = true;
		}

		if (!pbs && (sd.close_0 <= sd.open_0 && sd.close_1 <= sd.open_1 && sd.close_2 <= sd.open_2
				&& sd.close_3 <= sd.open_3 && sd.close_4 <= sd.open_4) && sd.close_5 > sd.open_5
				&& (sd.close_5 >= bbValues.getMa_20())) {
			pbs = true;
		}

		if (!pbs)
			return false;

		int rank = 0;

		Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
				.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).country(config.country).build();

		pattern.setRank(rank);
		pattern.setRankStr("Rank:" + rank);

		patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		return true;
	}

	private boolean pss(String symbol, List<StockData> stockDataList) {
		if (symbol.equals("CMSINFO")) {
			System.out.println("break");
		}
		String type = "PSS";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		boolean pss = false;

		if ((sd.close_0 >= sd.open_0 && sd.close_1 >= sd.open_1 && sd.close_2 >= sd.open_2) && sd.close_3 < sd.open_3
				&& (sd.close_3 <= bbValues.getMa_20())) {
			pss = true;
		}

		if (!pss && (sd.close_0 >= sd.open_0 && sd.close_1 >= sd.open_1 && sd.close_2 >= sd.open_2
				&& sd.close_3 >= sd.open_3) && sd.close_4 < sd.open_4 && (sd.close_4 <= bbValues.getMa_20())) {
			pss = true;
		}

		if (!pss && (sd.close_0 >= sd.open_0 && sd.close_1 >= sd.open_1 && sd.close_2 >= sd.open_2
				&& sd.close_3 >= sd.open_3 && sd.close_4 >= sd.open_4) && sd.close_5 < sd.open_5
				&& (sd.close_5 <= bbValues.getMa_20())) {
			pss = true;
		}

		if (!pss && (sd.high_0 > sd.high_1 && sd.high_1 > sd.high_2) && sd.close_3 <= bbValues.getMa_20()) {
			pss = true;
		}

		if (!pss)
			return false;

		Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
				.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).country(config.country).build();

		pattern.setRank(1);

		patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		return true;
	}

	private boolean greenRed(String symbol, List<StockData> stockDataList) {
		boolean ib = false;
		String type = "GreenRed";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;
		if (sd.high_0 <= sd.high_1 && sd.low_0 >= sd.low_1 && sd.close_1 >= sd.open_1)
			ib = true;
		int rank = 0;

		if (ib == true || (sd.close_0 < sd.open_0 && (sd.close_1 > sd.open_1) && sd.close_0 > sd.open_1
				&& sd.body_1 > sd.body_0 * 1.5)) {
			Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
					.tail(sd.tail_0).body0(sd.body_1).bbValues(bbValues).country(config.country).build();
			String rankStr = "";
			pattern.setRank(rank);
			pattern.setRankStr(rankStr);
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			greenRedStrings.add(symbol);
			return true;
		}
		return false;
	}

	private boolean redGreen(String symbol, List<StockData> stockDataList) {
		boolean ib = false;
		String type = "RedGreen";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;
		if (sd.high_0 <= sd.high_1 && sd.low_0 >= sd.low_1 && sd.close_1 <= sd.open_1)
			ib = true;
		int rank = 0;

		if (ib == true || (sd.close_0 > sd.open_0 && (sd.close_1 < sd.open_1 && sd.close_2 < sd.open_2)
				&& (sd.body_1 / sd.body_0) >= 2) || (sd.close_1 <= sd.open_1 && sd.body_1 / sd.body_0 >= 2)) {
			if (sd.body_1 / sd.body_0 >= 2) {
				String rankStr = "";
				Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
						.tail(sd.tail_0).body0(sd.body_1).bbValues(bbValues).country(config.country).build();
				if (sd.high_0 > bbValues.getLower() && sd.low_0 < bbValues.getLower()) {
					rankStr += "LBB;";
					rank++;
				} else if (sd.low_0 < bbValues.getLower()) {
					rankStr += "LBB;";
					rank++;
				} else if (sd.high_1 > bbValues.getLower() && sd.low_1 < bbValues.getLower()) {
					rankStr += "Yesterday LBB;";
					rank++;
				}

				if (sd.high_0 > bbValues.getMa_20() && sd.low_0 < bbValues.getMa_20()) {
					rankStr += "MBB;";
					rank++;
				} else if (sd.high_1 > bbValues.getMa_20() && sd.low_1 < bbValues.getMa_20()) {
					rankStr += "Yesterday MBB;";
					rank++;
				}

				if (sd.close_0 > 200) {
					rankStr += "close > 200;";
					rank++;
				}

				if (sd.close_1 < sd.open_1 && sd.close_2 > sd.open_2 && sd.body_1 > 5 && sd.body_1 > sd.body_2) {
					rankStr += "Yesterday Bearish engulf;";
					rank++;
				}

				if (sd.close_0 > sd.open_0 && sd.body_0 < sd.body_1) {
					rankStr += "Today green;";
					rank++;
					rank++;
				}

				pattern.setRank(rank);
				pattern.setRankStr(rankStr);
				patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
				redGreenStrings.add(symbol);
				return true;
			}
		}
		return false;
	}

	private boolean upday(String symbol, List<StockData> stockDataList) {
		String type = "UpDay";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		if (!volumeCheck(stockDataList) || !priceCheck(stockDataList))
			return false;

		if (sd.close_0 > sd.open_0) {
			Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
					.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			return true;
		}
		return false;
	}

	private boolean downDay(String symbol, List<StockData> stockDataList) {
		String type = "DownDay";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		if (sd.close_0 < sd.open_0) {
			Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
					.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			return true;
		}
		return false;
	}

	private boolean findPurpleDotStocks(Market market, String symbol, List<StockData> stockDataList) {
		String type = "PurpleDot";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(market); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		if (isPurpleCandle(stockDataList.get(0))) {
			Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
					.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			savePattern(pattern, type, sd);
			return true;
		}
		return false;
	}

	public void getRestingStocksAfterBurst(Market market) {
		List<Pattern> results = getPatternsFromDB(market);

		patternResultsSet.clear();
		for (Pattern pattern : results) {
			if (patternResultsSet.contains(pattern.getSymbol()))
				continue;

			if (pattern.getDate().equals(processingDate))
				continue;

			List<StockData> stockDataList = stockDataMap.get(pattern.getSymbol());
			if (stockDataList == null || stockDataList.isEmpty())
				continue;

			if (!volumeCheck(stockDataList) || !priceCheck(stockDataList))
				continue;

			StockDataInfo sd = new StockDataInfo(stockDataList);

			double adr = calculateADR(stockDataList, 20);
			if (market == Market.US && (adr < 3.5 || sd.close_0 < 20 || sd.close_0 > 1000))
				continue;

			int indexFound = -1;

			for (int i = 0; i < stockDataList.size(); i++) {
				if (stockDataList.get(i).getDate().equals(pattern.getDate())) {
					indexFound = i;
				}
			}

			if (indexFound == -1)
				continue;

			boolean validPattern = true;

			for (int j = indexFound; j > 0; j--) {

				double percentage = (stockDataList.get(j).getClose() - pattern.getClose().doubleValue())
						/ pattern.getClose().doubleValue() * 100;
				if (percentage > 10) {
					validPattern = false;
					break;
				}
			}

			if (!validPattern)
				continue;

			BBValues bb = calculateBBAndMaValues(pattern.getSymbol(), stockDataList, 0);

			if (bb == null || bb != null && sd.close_0 > bb.getMa_50() && sd.close_0 > bb.getMa_50()
					&& bb.getMa_20() > bb.getMa_50()) {
				// validPattern = false;
			}

			if (!validPattern)
				continue;

			String type = "BurstRetracement";

			StockData sd1 = new StockData();
			sd1.setSymbol(pattern.getSymbol());
			pattern.setStockData(sd1);
			pattern.setMaDistance(sd.getClose_0() - bb.getMa_10());
			String rankStr = "Pattern:" + pattern.getPatternName() + " On:" + pattern.getDate() + " MA10 distance "
					+ Double.toString(pattern.getMaDistance()) + " ADR:" + Double.toString(adr);
			pattern.setRankStr(rankStr);
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			patternResultsSet.add(pattern.getSymbol());

		}

	}

	private List<Pattern> getPatternsFromDB(Market market) {
		MarketConfig config = marketConfigs.get(market);
		if (config == null)
			return null;

		List<String> patternNames = Arrays.asList("PowerUpCandle", "PurpleDot");
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate;
		int businessDaysToSubtract = 21;

		if (market == Market.US)
			businessDaysToSubtract = 15;

		while (businessDaysToSubtract > 0) {
			startDate = startDate.minusDays(1);
			if (startDate.getDayOfWeek() != DayOfWeek.SATURDAY && startDate.getDayOfWeek() != DayOfWeek.SUNDAY) {
				businessDaysToSubtract--;
			}
		}

		List<Pattern> results = patternRepository.findDistinctByPatternNameInAndCountryEqualsAndDateRange(patternNames,
				config.country, startDate, endDate);
		return results;
	}

	private boolean findVolumeShockers(Market market, String symbol, List<StockData> stockDataList) {
		StockDataInfo sd = new StockDataInfo(stockDataList);
		MarketConfig config = marketConfigs.get(market); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		/*
		 * if (sd.close_0 < bbValues.getMa_200()) return false;
		 */

		if (sd.close_0 > sd.open_0 && ((sd.close_0 - sd.open_0) / sd.open_0) * 100 > 1
				&& ((bbValues.getTwentyMaAverage() > 0 && (sd.volume_0 > (bbValues.getTwentyMaAverage() * 1.5)))
						|| sd.volume_0 > (sd.volume_1 * 3))) {
			String type = "VolumeShockers";

			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).country(config.country).build();

			pattern.setRank((int) (sd.volume_1 / sd.volume_0));
			String rankStr = "Date:" + stockDataList.get(0).getDate().toString() + " Volume 0:" + sd.volume_0
					+ " Volume 1: " + sd.volume_1;
			pattern.setRankStr(rankStr);
			pattern.setDate(processingDate);

			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);

			savePattern(pattern, type, sd);

			return true;

		}
		return false;

	}

	private boolean isPurpleCandle(StockData stockData) {
		// Define your criteria for a big candle (e.g., 5% movement and 500,000 share
		// volume)
		double movementThreshold = 0.05; // 5%
		double volumeThreshold = 500000;

		double close = stockData.getClose();
		double open = stockData.getOpen();
		double volume = stockData.getVolume();

		// Calculate the percentage movement
		double percentageMovement = ((close - open) / open);

		// Check if the candle has 5% movement and volume of at least 500,000
		return percentageMovement >= movementThreshold && volume >= volumeThreshold;
	}

	private void markSwingHighsAndLows(String symbol, List<StockData> stockDataList) {
		if (stockDataList == null || stockDataList.size() < 5) {
			return; // Need at least 5 bars for ±2-bar window
		}

		int window = 2; // Check 2 bars before and after

		for (int i = 0; i < stockDataList.size(); i++) {
			StockData current = stockDataList.get(i);
			if (current.getHigh() <= 0 || current.getLow() <= 0) {
				current.setSwingType("");
				continue;
			}

			boolean isSwingHigh = true;
			boolean isSwingLow = true;

			// Compare with bars in ±2 window
			for (int j = Math.max(0, i - window); j <= Math.min(stockDataList.size() - 1, i + window); j++) {
				if (j == i)
					continue;
				StockData other = stockDataList.get(j);
				if (other.getHigh() >= current.getHigh())
					isSwingHigh = false;
				if (other.getLow() <= current.getLow())
					isSwingLow = false;
			}

			current.setSwingType(isSwingHigh ? "H" : isSwingLow ? "L" : "");
		}
	}

	/**
	 * Extracts and refines a list of significant Swing Highs from the stock data
	 * that are suitable for forming descending trendlines. Assumes
	 * `markSwingHighsAndLows` has already been called on the `stockDataList`. The
	 * returned list will be in reverse chronological order (latest swing high
	 * first).
	 *
	 * @param stockDataList The list of StockData with swing types marked ('H' or
	 *                      'L').
	 * @return A list of `SwingPoint` objects representing the refined Swing Highs.
	 */
	private List<SwingPoint> getRefinedDescendingSwingHighs(List<StockData> stockDataList) {
		List<SwingPoint> rawSwingHighs = new ArrayList<>();
		int totalBars = stockDataList.size();

		// 1. Extract all raw 'H' (Swing High) points
		// Iterate from the latest data (index 0) to the oldest.
		for (int i = 0; i < stockDataList.size(); i++) {
			StockData data = stockDataList.get(i);
			if ("H".equals(data.getSwingType())) {
				rawSwingHighs.add(new SwingPoint(data.getDate(), data.getHigh(), "H", i, totalBars));
			}
		}

		// 2. Refine the raw swing highs to get a cleaner, descending sequence.
		// This step aims to pick the most dominant high in a cluster and ensure
		// a logical flow for potential trendlines.
		List<SwingPoint> refinedSwingHighs = new ArrayList<>();

		if (rawSwingHighs.isEmpty()) {
			return refinedSwingHighs; // No swing highs found.
		}

		// Always add the most recent swing high as the starting point for refinement.
		// This is H1 for a potential descending trendline.
		refinedSwingHighs.add(rawSwingHighs.get(0));

		for (int i = 1; i < rawSwingHighs.size(); i++) {
			SwingPoint currentRawH = rawSwingHighs.get(i); // This is an older 'H'
			SwingPoint lastRefinedH = refinedSwingHighs.get(refinedSwingHighs.size() - 1); // The most recently added
																							// 'H' to our refined list

			// Rule for adding:
			// We are looking for a 'currentRawH' that is chronologically older (higher
			// index),
			// and ideally lower in price than 'lastRefinedH' to form a descending sequence.
			// We also want to avoid picking 'H's that are too close in time or are part of
			// the same price cluster.

			// Scenario A: Current raw high is significantly lower than the last refined
			// high
			// This is a clear candidate for the next point in a descending trendline.
			// We add a minimum bar separation (e.g., at least 3 bars) to ensure
			// distinctness.
			if (currentRawH.getPrice() < lastRefinedH.getPrice()
					&& (currentRawH.getOriginalIndex() - lastRefinedH.getOriginalIndex()) >= 3) { // Ensure sufficient
																									// time separation
				refinedSwingHighs.add(currentRawH);
			}
			// Scenario B: Current raw high is higher than the last refined high, but
			// chronologically older.
			// This means 'lastRefinedH' might not have been the true peak in that earlier
			// period.
			// If the current raw high is higher and also sufficiently separated, we could
			// replace 'lastRefinedH'.
			// However, for *descending* trendlines, we are interested in progressively
			// *lower* highs.
			// So, if we encounter a chronologically older but higher 'H', it indicates the
			// 'lastRefinedH' was a local
			// high in an *uptrend* or sideways move, and not suitable for continuing a
			// *descending* line from 'lastRefinedH'.
			// For simplicity for descending trendlines, we mainly care about Scenario A.
			// The previous iteration of the outer loop would have picked the actual higher
			// swing if it was a peak.
			// So if currentRawH.getPrice() > lastRefinedH.getPrice(), we generally ignore
			// it for descending sequence building.

			// If currentRawH is equal to lastRefinedH, we ignore it as well to prevent
			// duplicates.
		}

		// At this point, 'refinedSwingHighs' contains swing highs in reverse
		// chronological order,
		// where each successive high (chronologically older) is lower than the previous
		// one,
		// and they are sufficiently separated.

		return refinedSwingHighs;
	}

	private void watchList() {
		File folder = new File("C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\");
		File[] files = folder.listFiles((dir, name) -> name.matches(".*Watchlist-US-*.+\\.txt"));

		if (files == null) {
			System.out.println("No Watchlist file found.");
			return;
		}

		for (File file : files) {
			String fileName = file.getName();
			String patternName = fileName.substring(0, fileName.indexOf("."));

			try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
				String symbol;
				while ((symbol = reader.readLine()) != null) {

					symbolToWatchlistMap.put(symbol.trim(), fileName);

					Pattern pattern = new Pattern();
					pattern.setPatternName(patternName);
					StockData stockData = new StockData();
					stockData.setSymbol(symbol);
					pattern.setStockData(stockData);
					patternResults.computeIfAbsent(patternName, k -> new ArrayList<>()).add(pattern);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		List<String> watchlistPatternNames = new ArrayList<>();

		if (files != null) {
			for (File file : files) {
				String fileName = file.getName();
				String patternName = fileName.substring(0, fileName.indexOf("."));
				watchlistPatternNames.add(patternName);
			}
		}

		// Process each watchlist pattern
		for (String patternName : watchlistPatternNames) {
			List<Pattern> watchListResults = patternResults.get(patternName);
			// Sort the list in descending order based on (stockData.close - stockData.open)
			watchListResults.sort((p1, p2) -> {
				double diff1 = p1.getStockData().getClose() - p1.getStockData().getOpen();
				double diff2 = p2.getStockData().getClose() - p2.getStockData().getOpen();
				return Double.compare(diff2, diff1); // Descending order
			});
			;
			if (watchListResults != null) {
				// savePatternsToFile(dates.get(0), watchListResults, patternName, false,
				// false);
			}
		}

	}

	public String getWatchlistForSymbol(String symbol) {
		return symbolToWatchlistMap.get(symbol.trim());
	}

	private void savePatternsToFile(Market market, String date, List<Pattern> results, String patternName,
			boolean saveToTable, boolean isSector) {
		MarketConfig config = marketConfigs.get(market);
		if (config == null) {
			LOG.error("No configuration found for market: {}", market);
			return;
		}

		String outputDirectory = config.outputDirectory;
		String fileName = (watchlist != null && !watchlist.isEmpty() ? watchlist + "_" : "")
				+ (isSector ? "sector\\" : "") + patternName + (saveToTable ? "_incremental" : "_" + date) + ".html";
		String filePath = outputDirectory + fileName;
		File file = new File(filePath);
		file.getParentFile().mkdirs(); // Ensure directory exists

		try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
			// Writing HTML header
			writer.println("<!DOCTYPE html>");
			writer.println("<html lang=\"en\">");
			writer.println("<head>");
			writer.println("<meta charset=\"UTF-8\">");
			writer.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
			writer.println("<title>" + patternName + " Patterns - " + config.country + "</title>");
			writer.println(
					"<link href=\"https://stackpath.bootstrapcdn.com/bootstrap/4.5.2/css/bootstrap.min.css\" rel=\"stylesheet\">");
			writer.println("</head>");
			writer.println("<body>");

			// Writing patterns in a 3-column layout
			writer.println("<div class=\"container-fluid\">");
			writer.println("<h2>" + patternName + " Patterns for " + (saveToTable ? "Incremental" : date) + " ("
					+ config.country + ")</h2>" + " Count : " + results.size());
			writer.println("<div class=\"row\">");

			int count = 0;
			for (Pattern pattern : results) {
				if (saveToTable) {
					// Patterns are already saved in detection methods; skip redundant save
					// savePattern(pattern, patternName, null);
				}

				String rankStr = pattern.getRankStr() != null ? pattern.getRankStr() : "";
				String symbol = pattern.getStockData() != null ? pattern.getStockData().getSymbol() : "N/A";
				String country = pattern.getCountry() != null ? pattern.getCountry() : config.country;
				String patternDate = pattern.getDate() != null ? pattern.getDate().toString() : "N/A";
				String rank = Double.toString(pattern.getRank());

				boolean purpleDot = false;

				writer.println("<div class=\"col-md-6\">");
				writer.println("<div class=\"card\">");
				writer.println("<div class=\"card-body\">");
				writer.println("<h5 class=\"card-title\">Symbol: " + symbol + "</h5>");
				// writer.println("<p class=\"card-text\">Country: " + country + "</p>");
				writer.println("<p class=\"card-text\">Date: " + patternDate + "</p>");

				String backgroundClass = "white";
				if (rankStr.contains("Two day BB") || rankStr.contains("Three days BB")
						|| rankStr.contains("Four days BB")) {
					backgroundClass = "style=\"background-color: green;\"";
				} else {
					backgroundClass = purpleDot ? "style=\"background-color: #dda0dd;\"" : "";
				}
				writer.println("<p class=\"card-text\" " + backgroundClass + ">Rank:" + rank + "</p>");
				writer.println("<p class=\"card-text\" " + backgroundClass + ">Rank Str:" + rankStr + "</p>");

				// Add stock chart image
				writer.println("<img src=\"https://stockcharts.com/c-sc/sc?s=" + symbol
						+ (market == Market.NSE ? ".IN" : "") + "&" + stockchartsurl + "\" class=\"img-fluid\">");
				writer.println("</div>");
				writer.println("</div>");
				writer.println("</div>");

				count++;
				if (count % 2 == 0) {
					writer.println("</div><div class=\"row\">");
				}
			}

			writer.println("</div></div>");

			// Writing HTML footer
			writer.println("<script src=\"https://code.jquery.com/jquery-3.5.1.slim.min.js\"></script>");
			writer.println(
					"<script src=\"https://cdn.jsdelivr.net/npm/@popperjs/core@2.4.1/dist/umd/popper.min.js\"></script>");
			writer.println(
					"<script src=\"https://stackpath.bootstrapcdn.com/bootstrap/4.5.2/js/bootstrap.min.js\"></script>");
			writer.println("</body>");
			writer.println("</html>");

			LOG.info("HTML file saved: {}", filePath);
		} catch (IOException e) {
			LOG.error("Error writing patterns to file {}: {}", filePath, e.getMessage());
		}
	}

	private void displayStockMarketCap(PrintWriter writer, Pattern pattern) {
		String symbol = pattern.getStockData().getSymbol();
		Optional<StockMaster> stockMasterOpt = stockMasterRepository.findByTicker(symbol);

		if (stockMasterOpt.isPresent()) {
			StockMaster stockMaster = stockMasterOpt.get();
			double marketCap = stockMaster.getMarketCap();

			String capCategory;
			String color;

			if (marketCap < 29547.26) {
				capCategory = "SmallCap";
				color = "red";
			} else if (marketCap <= 100000.00) { // Replace 100000.00 with the actual upper limit for MidCap
				capCategory = "MidCap";
				color = "Gray";
			} else {
				capCategory = "LargeCap";
				color = "DodgerBlue";
			}
			writer.println("<h5 style=\"color:" + color + ";\">" + capCategory + "</h5>");
		}
	}

	/*
	 * private void savePattern(Pattern pattern, String patternName) {
	 * pattern.setSymbol(pattern.getStockData().getSymbol());
	 * pattern.setDate(processingDate); pattern.setPatternName(patternName);
	 * patternRepository.save(pattern); }
	 */

	private void savePattern(Pattern pattern, String patternName, StockDataInfo sd) {
		String symbol = pattern.getStockData().getSymbol();
		LocalDate date = pattern.getStockData().getDate();

		if (pattern.getDate() != null)
			date = pattern.getDate();

		{
			// Save a new record
			if (sd != null)
				pattern.setClose(new BigDecimal(sd.close_0));
			pattern.setSymbol(symbol);
			pattern.setDate(date);
			pattern.setPatternName(patternName);
			patternRepository.save(pattern);
		}
	}

	private boolean priceCheck(List<StockData> stockDataList) {
		if (stockDataList.size() >= 2) {
			if (stockDataList.get(0).getClose() >= 5 && stockDataList.get(0).getClose() <= 10000)
				return true;
		}
		return false;
	}

	private boolean volumeCheck(List<StockData> stockDataList) {

		StockDataInfo sd = new StockDataInfo(stockDataList);

		if (sd.volume_0 < 100000)
			return false;

		if (sd.volume_0 > (sd.volume_1 * 2) && sd.close_0 > sd.open_0 && sd.close_0 > sd.close_1
				&& sd.volume_0 > 100000) {
			return true;
		}
		if (stockDataList.size() >= 10) {
			double totalVolume = 0;
			for (int i = 0; i < 10; i++) {
				if (stockDataList.get(i).getVolume() >= 200000)
					return true;
				totalVolume += stockDataList.get(i).getVolume();
			}
			double averageVolume = totalVolume / 10;
			return averageVolume > 300000;
		}
		return false;
	}

	private boolean priceCheckBearish(List<StockData> stockDataList) {
		if (stockDataList.size() >= 2) {
			if (stockDataList.get(0).getClose() >= 100 && stockDataList.get(0).getClose() <= 4000)
				return true;
		}
		return false;
	}

	public boolean isNFOStock(String symbol) {
		return true;
		// return nfoStocks.contains(symbol);
	}

	public boolean isETFStock(String symbol) {
		return etfStocks.contains(symbol);
	}

	public boolean isGoldenStock(String symbol) {
		return goldenStocks.contains(symbol);
	}

	public Set<String> initializeGoldenStocks() {
		goldenStocks.clear();
		Iterable<Pattern> allPatterns = patternRepository.findAll();
		for (Pattern pattern : allPatterns) {
			goldenStocks.add(pattern.getSymbol());
		}
		return goldenStocks;
	}

	private BBValues calculateBBAndMaValues(String symbol, List<StockData> stockDataList, int startIndex) {
		BBValues bbValues = new BBValues();

		int n = 20 + startIndex;

		if (stockDataList == null || stockDataList.size() < n) {
			// Insufficient data to calculate the 20-day MA
			return bbValues;
		}

		double ma_10 = 0;
		double ma_20 = 0;
		double ma_50 = 0;
		double ma_200 = 0;
		double bodyAvg10 = 0;
		double bodyAvg20 = 0;

		// Calculate 10-day MA
		for (int i = startIndex; i < 10; i++) {

			ma_10 += stockDataList.get(i).getClose();
			bodyAvg10 += Math.abs(stockDataList.get(i).getClose() - stockDataList.get(i).getOpen());
		}
		bbValues.setMa_10(ma_10 / 10);

		// Calculate 20-day MA
		for (int i = startIndex; i < n; i++) {

			ma_20 += stockDataList.get(i).getClose();
			bodyAvg20 += Math.abs(stockDataList.get(i).getClose() - stockDataList.get(i).getOpen());
		}
		bbValues.setMa_20(ma_20 / 20);
		bbValues.setBodyAvg20(bodyAvg20 / 20);

		if (stockDataList.size() > 50 + startIndex) {
			// Calculate 50-day MA
			for (int i = startIndex; i < 50 + startIndex; i++) {
				ma_50 += stockDataList.get(i).getClose();
			}
			bbValues.setMa_50(ma_50 / 50);
			double fiftyMaAverage = stockDataList.stream().skip(startIndex).limit(50).mapToDouble(s -> s.getVolume())
					.average().orElse(0);
			bbValues.setFiftyMaAverage(fiftyMaAverage);
		}

		if (stockDataList.size() > 20 + startIndex) {
			// Calculate 20-day MA
			double twentyMaAverage = stockDataList.stream().skip(startIndex).limit(20).mapToDouble(s -> s.getVolume())
					.average().orElse(0);
			bbValues.setTwentyMaAverage(twentyMaAverage);
		}

		if (stockDataList.size() > 200 + startIndex) {
			// Calculate 50-day MA
			for (int i = startIndex; i < 200 + startIndex; i++) {
				ma_200 += stockDataList.get(i).getClose();
			}
			bbValues.setMa_200(ma_200 / 200);
		}

		double sumSquaredDev = 0;

		// Calculate sum of squared deviations
		for (int i = startIndex; i < n; i++) {
			double deviation = stockDataList.get(i).getClose() - bbValues.getMa_20();
			sumSquaredDev += deviation * deviation;
		}

		// Calculate standard deviation
		double variance = sumSquaredDev / n;
		double sd = Math.sqrt(variance);

		// Calculate Bollinger Bands
		bbValues.setUpper(bbValues.getMa_20() + (sd * 1.8));
		bbValues.setLower(bbValues.getMa_20() - (sd * 1.8));

		return bbValues;
	}

	public void processLatestDataForPast(Market market, String watchlist) {
		this.watchlist = watchlist;
		MarketConfig config = marketConfigs.get(market);
		if (config == null) {
			LOG.error("Invalid market: {}", market);
			return;
		}

		Map<String, List<StockData>> stockDataMap = prepareDataForProcessing(market);
		stockDataMap.forEach((symbol, stockDataList) -> {
			processForSymbol(market, stockDataMap, symbol, 51);
		});
	}

	private void processForSymbol(Market market, Map<String, List<StockData>> stockDataMap, String symbol, int times) {
		List<StockData> stockDataList = stockDataMap.get(symbol);

		if (!volumeCheck(stockDataList) || !priceCheck(stockDataList))
			return;

		boolean volumeShockersFound = false;
		if (stockDataList != null && !stockDataList.isEmpty()) {
			for (int i = 0; i <= times; i++) {
				List<StockData> newDataList = createNewDataList(stockDataList, i);
				if (newDataList.isEmpty()) {
					break; // No more data to process for this symbol
				}

				bbValues = calculateBBAndMaValues(symbol, newDataList, 0);

				// Update processing date if newDataList is not empty
				if (!newDataList.isEmpty()) {
					processingDate = newDataList.get(0).getDate();
				}

				if (!volumeShockersFound)
					volumeShockersFound = findVolumeShockers(market, symbol, newDataList);
				findPurpleDotStocks(market, symbol, newDataList);
				findGapUpStocks(market, symbol, newDataList);
				findPowerUpCandleStocks(market, symbol, newDataList);
			}
		}
	}

	private List<StockData> createNewDataList(List<StockData> originalList, int startIndex) {
		List<StockData> newDataList = new ArrayList<>();
		for (int i = startIndex + 1; i < originalList.size(); i++) {
			newDataList.add(originalList.get(i));
		}
		return newDataList;
	}

	private double calculateADR(List<StockData> stockDataList, int period) {

		if (stockDataList.size() < period)
			return 0.0;

		// Calculate ADR for the most recent 'period' days
		double sum = 0.0;
		for (int i = 0; i < period; i++) {
			StockData stockData = stockDataList.get(i);
			// Calculate daily range as a percentage: ((High - Low) / Close) * 100
			double dailyRange = ((stockData.getHigh() - stockData.getLow()) / stockData.getClose()) * 100;
			sum += dailyRange;
		}
		double adr = sum / period;
		return adr;
	}

	public void createSectorWatchlist(Map<String, List<StockData>> stockDataMap) {

		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return;

		// Fetch all entries from the stock_master table
		List<StockMaster> stockMasterList = stockMasterRepository.findAll();

		// Group the entries by sub_sector
		Map<String, List<StockMaster>> groupedBySubSector = stockMasterList.stream()
				.collect(Collectors.groupingBy(StockMaster::getSubSector));

		// Create a file for each sub-sector
		groupedBySubSector.forEach((subSector, stocks) -> {
			String fileName = "Sector-" + subSector.replaceAll("\\s+", "_");
			List<Pattern> patterns = new ArrayList<>();

			for (StockMaster stock : stocks) {
				List<StockData> stockDataList = stockDataMap.get(stock.getTicker());
				if (stockDataList == null || stockDataList.isEmpty())
					continue;
				Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(subSector).symbol(stock.getName())
						.stockData(stockDataList.get(0)).country(config.country).build();
				patterns.add(pattern);
			}
			// savePatternsToFile(dates.get(0), patterns, fileName, false, true);

		});
	}

	public void performSectorAnalysis(Map<String, List<StockData>> stockDataMap) {
		// Load all stocks from the stock_master table
		List<StockMaster> stockMasters = stockMasterRepository.findAll();

		// Group stocks by sector
		Map<String, List<StockMaster>> stocksBySector = stockMasters.stream()
				.collect(Collectors.groupingBy(StockMaster::getSubSector));

		List<SectorAnalysisResult> results = new ArrayList<>();

		// Iterate through each sector
		for (Map.Entry<String, List<StockMaster>> entry : stocksBySector.entrySet()) {
			String sector = entry.getKey();
			List<StockMaster> stocksInSector = entry.getValue();

			double totalPercentageChange = 0.0;
			int stockCount = 0;

			// Iterate through each stock in the sector
			for (StockMaster stock : stocksInSector) {
				List<StockData> stockDataList = stockDataMap.get(stock.getTicker());
				if (stockDataList != null && stockDataList.size() > 1) {

					if (stockDataList.get(0).getVolume() < 200000)
						continue;

					double todayClose = stockDataList.get(0).getClose();
					double yesterdayClose = stockDataList.get(1).getClose();

					// Calculate percentage change
					double percentageChange = ((todayClose - yesterdayClose) / yesterdayClose) * 100;

					if (percentageChange < 5)
						continue;

					totalPercentageChange += percentageChange;
					stockCount++;
				}
			}

			// Calculate average percentage change for the sector
			if (stockCount > 0) {
				double averagePercentageChange = totalPercentageChange / stockCount;
				results.add(new SectorAnalysisResult(sector, averagePercentageChange));
			}
		}

		// Sort results in descending order of average percentage change
		results.sort((r1, r2) -> Double.compare(r2.getAveragePercentageChange(), r1.getAveragePercentageChange()));

		// Print the sorted results
		for (SectorAnalysisResult result : results) {
			System.out.println("Sector: " + result.getSector() + ", Average Percentage Change: "
					+ result.getAveragePercentageChange());
		}
	}

	/**
	 * Generates potential descending trendlines from a list of refined swing highs.
	 * A descending trendline connects two swing highs where the chronologically
	 * older high is higher in price than the chronologically newer high.
	 *
	 * @param refinedSwingHighs    A list of `SwingPoint` objects (type "H"), in
	 *                             reverse chronological order (latest H first), as
	 *                             returned by `getRefinedDescendingSwingHighs`.
	 * @param minSlopeAngleDegrees (Optional) Minimum downward angle of the
	 *                             trendline in degrees. For example, if -5 degrees,
	 *                             the slope must be steeper (more negative) than
	 *                             tan(-5 deg).
	 * @param minPriceDropPercent  (Optional) Minimum percentage drop required
	 *                             between the two swing highs to consider forming a
	 *                             valid descending trendline. E.g., 0.5 for 0.5%.
	 * @return A list of potential `Trendline` objects.
	 */
	private List<Trendline> generateDescendingTrendlines(List<SwingPoint> refinedSwingHighs,
			double minSlopeAngleDegrees, double minPriceDropPercent) {

		List<Trendline> descendingTrendlines = new ArrayList<>();

		// We need at least two swing highs to form a line.
		if (refinedSwingHighs.size() < 2) {
			return descendingTrendlines;
		}

		// Create a chronological copy of the swing highs for easier iteration (oldest
		// first).
		List<SwingPoint> chronologicalSwingHighs = new ArrayList<>(refinedSwingHighs);
		Collections.reverse(chronologicalSwingHighs); // Now, oldest H is at index 0, newest is at size-1.

		// Calculate the minimum allowed slope value based on the angle.
		// For a descending line, slope should be negative.
		// E.g., if minSlopeAngleDegrees is -5, slope must be <= tan(-5 deg) (e.g.,
		// -0.087).
		// A slope of -0.1 is steeper (more negative) than -0.087, so it satisfies.
		double minSlopeValue = Math.tan(Math.toRadians(minSlopeAngleDegrees));

		// Iterate through all possible pairs of swing highs to form lines
		// Point1 is always chronologically older than Point2 (i < j).
		for (int i = 0; i < chronologicalSwingHighs.size(); i++) {
			SwingPoint point1 = chronologicalSwingHighs.get(i); // Potential start point (older, left)

			// Iterate through all subsequent points for the end point
			for (int j = i + 1; j < chronologicalSwingHighs.size(); j++) {
				SwingPoint point2 = chronologicalSwingHighs.get(j); // Potential end point (newer, right)

				// Conditions for a valid DESCENDING trendline between point1 and point2:
				// 1. Point2 must be lower in price than Point1.
				// 2. There must be a minimum percentage drop between Point1 and Point2 (for
				// significance).
				double priceDropPercentage = ((point1.getPrice() - point2.getPrice()) / point1.getPrice()) * 100;

				if (point2.getPrice() < point1.getPrice() && priceDropPercentage >= minPriceDropPercent) {
					Trendline potentialTrendline = new Trendline(point1, point2, "Descending");

					// 3. The slope of the line must meet the minimum downward angle requirement.
					// (Slope should be negative and less than or equal to minSlopeValue, which is
					// also negative).
					if (potentialTrendline.getSlope() <= minSlopeValue) {
						descendingTrendlines.add(potentialTrendline);
					}
				}
			}
		}
		return descendingTrendlines;
	}

	/**
	 * Validates potential descending trendlines by checking for additional
	 * "touches" from candles. For a descending (resistance) trendline, a touch is
	 * considered when a candle's high is at or very near the line, and its closing
	 * price remains below or very near the line, suggesting rejection.
	 *
	 * @param potentialTrendlines   The list of trendlines generated in Step 2.
	 * @param stockDataList         The full list of StockData for accessing all
	 *                              candle highs, lows, and closes. (Assumed to be
	 *                              in reverse chronological order: index 0 is
	 *                              newest).
	 * @param touchTolerancePercent The percentage deviation allowed for a candle's
	 *                              high to be considered a "touch". E.g., 0.2 for
	 *                              0.2%.
	 * @param minValidationTouches  The minimum number of *additional* touches
	 *                              (beyond the initial two defining points)
	 *                              required to consider a trendline validated.
	 * @return A filtered list of validated `Trendline` objects, with their touch
	 *         counts updated.
	 */
	private List<Trendline> validateDescendingTrendlines(List<Trendline> potentialTrendlines,
			List<StockData> stockDataList, double touchTolerancePercent, int minValidationTouches) {

		List<Trendline> validatedTrendlines = new ArrayList<>();
		int totalBars = stockDataList.size(); // Total number of bars for chronologicalX calculation

		for (Trendline trendline : potentialTrendlines) {
			int currentAdditionalTouches = 0;

			// Iterate through all candles from the point chronologically just *after* the
			// trendline's
			// startPoint (i.e., between startPoint.originalIndex and 0) up to the current
			// bar (index 0).
			// This covers all bars that could potentially validate the line after its
			// definition.
			// Note: stockDataList is in reverse chronological order (index 0 is newest).
			// startPoint.getOriginalIndex() is a higher index (older bar).
			// We want to check candles from (startPoint.getOriginalIndex() - 1) down to 0.

			for (int i = trendline.getStartPoint().getOriginalIndex() - 1; i >= 0; i--) {
				StockData currentCandle = stockDataList.get(i);
				int currentCandleChronologicalX = totalBars - 1 - i; // Calculate chronological X for this candle

				// IMPORTANT: Exclude the two defining points of the trendline from being
				// counted as *additional* touches.
				// This is crucial, as they already define the line.
				// Using equals on SwingPoint might not work well if they are different
				// instances.
				// Comparing dates or original indices is more reliable.
				if (i == trendline.getStartPoint().getOriginalIndex()
						|| i == trendline.getEndPoint().getOriginalIndex()) {
					continue;
				}

				// Calculate the expected price of the trendline at this candle's chronological
				// X
				double linePriceAtX = trendline.getPriceAtChronologicalX(currentCandleChronologicalX);
				double maxDeviation = linePriceAtX * (touchTolerancePercent / 100.0);

				// --- Touch criteria for a DESCENDING (RESISTANCE) Trendline ---
				// A "touch" suggests that price approached the line and was rejected from
				// above.
				// 1. The candle's high must be close to the trendline price (within tolerance).
				// 2. The candle's close should be below the trendline price (or very close),
				// indicating it couldn't break and sustain above the resistance.
				boolean highIsCloseToLine = Math.abs(currentCandle.getHigh() - linePriceAtX) <= maxDeviation;
				boolean closeIsBelowOrNearLine = currentCandle.getClose() <= linePriceAtX + (maxDeviation / 2.0); // Allow
																													// slight
																													// tolerance
																													// for
																													// close
																													// too

				if (highIsCloseToLine && closeIsBelowOrNearLine) {
					currentAdditionalTouches++;
				}
			}

			// If the trendline meets the minimum number of *additional* touches, add it to
			// the validated list.
			if (currentAdditionalTouches >= minValidationTouches) {
				trendline.setValidationTouchCount(currentAdditionalTouches);
				validatedTrendlines.add(trendline);
			}
		}
		return validatedTrendlines;
	}

}
