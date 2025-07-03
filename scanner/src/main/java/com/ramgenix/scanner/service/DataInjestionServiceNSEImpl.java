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
		patternToFileNameMap.put("BullFlag", "BullFlag");
		patternToFileNameMap.put("AscendingTriangle", "AscendingTriangle");

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
	private String stockchartsurl = "p=D&yr=0&mn=6&dy=0&i=t5269193626c&r=1751541526226";
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
				markSwingHighsAndLows(stockDataList);
				bbValues = calculateBBAndMaValues(symbol, stockDataList, 0);

				if (volumeCheck(stockDataList) && priceCheck(stockDataList) && symbolCheck(market, stockDataList)) {
					findVolumeShockers(market, symbol, stockDataList);
					findPurpleDotStocks(market, symbol, stockDataList);
					findGapUpStocks(market, symbol, stockDataList);
					findPowerUpCandleStocks(market, symbol, stockDataList);
				}

				boolean goldenStocksOnly = false;
				if (!patternResultsSet.contains(symbol)) {
					if (findBullishPatternsAdvanced(symbol, stockDataList, goldenStocksOnly)) {
						patternResultsSet.add(symbol);
					}
					checkBearishPatterns(symbol, stockDataList);
					upday(symbol, stockDataList);
				}
			}
		});

		if (StringUtils.isEmpty(watchlist)) {
			// watchList();
		}

		getRestingStocksAfterBurst();

		// Process all pattern types
		patternToFileNameMap.forEach((patternName, fileName) -> saveSortedPatterns(market, patternName, fileName));

		return null;
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
				savePatternsToFile(market, dates.get(0), incrementalPatterns, "incremental-" + fileName, false, false);
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
				if (!"EQ".equals(sd.getSeries())) {
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

							if (!fi.getSctySrs().equals("EQ")) {
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

	private boolean findBullishPatternsAdvanced(String symbol, List<StockData> stockDataList,
			boolean superStrongStocksOnly) {

		if (!"FINEORG".equals(symbol)) {
			// return;
		}

		StockDataInfo sd = new StockDataInfo(stockDataList);

		findNearSupportStocks(symbol, stockDataList);
		findBreakoutBars(symbol, stockDataList);
		findBullFlagStocks(symbol, stockDataList);
		findAscendingTriangleStocks(symbol, stockDataList);

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

	private boolean findBreakoutBars(String symbol, List<StockData> stockDataList) {
		if (stockDataList == null || stockDataList.isEmpty() || !volumeCheck(stockDataList)
				|| !priceCheck(stockDataList)) {
			return false;
		}
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		final String patternType = "BreakoutBars";
		final int maxSwingHighLookback = 50;
		final int breakoutLevelWindow = 20;
		final double postBreakoutThreshold = 6.0;
		final double proximityThreshold = 5.0;
		final int recentBarsToExclude = 3;

		if (stockDataList.size() < maxSwingHighLookback + breakoutLevelWindow + 1) {
			return false;
		}

		double currentClose = stockDataList.get(0).getClose();
		if (currentClose == 0.0) {
			return false;
		}

		double breakoutLevelPrice = 0.0;
		LocalDate breakoutLevelDate = null;
		int breakoutLevelIndex = -1;
		double breakoutBarClose = 0.0;
		LocalDate breakoutBarDate = null;
		int breakoutBarIndex = -1;
		double breakoutBarLow = 0.0;
		double postBreakoutHigh = 0.0;
		LocalDate postBreakoutHighDate = null;
		int postBreakoutHighIndex = -1;
		boolean validPullback = true;

		for (int i = 1; i <= Math.min(maxSwingHighLookback, stockDataList.size() - 1); i++) {
			StockData data = stockDataList.get(i);
			if ("H".equals(data.getSwingType())) {
				double high = data.getHigh();
				boolean isHighest = true;
				for (int j = i + 1; j <= Math.min(stockDataList.size() - 1, i + breakoutLevelWindow); j++) {
					if (stockDataList.get(j).getHigh() > high) {
						isHighest = false;
						break;
					}
				}
				if (isHighest) {
					double proximityPercent = Math.abs((currentClose - high) / high) * 100;
					if (proximityPercent <= proximityThreshold) {
						for (int j = i - 1; j >= 0; j--) {
							double close = stockDataList.get(j).getClose();
							if (close > high) {
								breakoutBarClose = close;
								breakoutBarIndex = j;
								breakoutBarDate = stockDataList.get(j).getDate();
								breakoutBarLow = stockDataList.get(j).getLow();
								postBreakoutHigh = 0.0;
								postBreakoutHighIndex = -1;
								postBreakoutHighDate = null;
								for (int k = j - 1; k >= 0; k--) {
									double currentHigh = stockDataList.get(k).getHigh();
									if (currentHigh >= high * (1 + postBreakoutThreshold / 100)
											&& currentHigh > postBreakoutHigh) {
										postBreakoutHigh = currentHigh;
										postBreakoutHighIndex = k;
										postBreakoutHighDate = stockDataList.get(k).getDate();
									}
								}
								if (postBreakoutHighIndex != -1) {
									validPullback = true;
									for (int k = breakoutBarIndex; k >= recentBarsToExclude; k--) {
										double low = stockDataList.get(k).getLow();
										if (low < breakoutBarClose) {
											validPullback = false;
											break;
										}
									}
									if (validPullback) {
										breakoutLevelPrice = high;
										breakoutLevelDate = data.getDate();
										breakoutLevelIndex = i;
										break;
									}
								}
								break;
							}
						}
						if (breakoutLevelIndex != -1 && postBreakoutHighIndex != -1 && validPullback) {
							break;
						}
					}
				}
			}
		}

		if (breakoutLevelPrice == 0.0 || breakoutBarIndex == -1 || postBreakoutHighIndex == -1 || !validPullback) {
			return false;
		}

		StockDataInfo sd = new StockDataInfo(stockDataList);
		Pattern pattern = Pattern.builder().patternName(patternType).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
		String rankStr = String.format(
				" BreakoutLevelPrice:%.2f BreakoutLevelDate:%s BreakoutBarClose:%.2f BreakoutBarDate:%s PostBreakoutHigh:%.2f PostBreakoutHighDate:%s BreakoutPercent:%.2f%% PostBreakoutPercent:%.2f%% ProximityPercent:%.2f%% CurrentPrice:%.2f",
				breakoutLevelPrice, breakoutLevelDate != null ? breakoutLevelDate.toString() : "N/A", breakoutBarClose,
				breakoutBarDate != null ? breakoutBarDate.toString() : "N/A", postBreakoutHigh,
				postBreakoutHighDate != null ? postBreakoutHighDate.toString() : "N/A",
				((breakoutBarClose - breakoutLevelPrice) / breakoutLevelPrice) * 100,
				((postBreakoutHigh - breakoutLevelPrice) / breakoutLevelPrice) * 100,
				Math.abs((currentClose - breakoutLevelPrice) / breakoutLevelPrice) * 100, currentClose);
		pattern.setRankStr(rankStr);
		pattern.setRank(calculateADR(stockDataList, 20));
		patternResults.computeIfAbsent(patternType, k -> new ArrayList<>()).add(pattern);
		return true;
	}

	private boolean findBullFlagStocks(String symbol, List<StockData> stockDataList) {
		if (stockDataList == null || stockDataList.isEmpty() || !priceCheck(stockDataList)) {
			return false;
		}
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		String type = "BullFlag";
		double flagRangeThreshold = 10.0;
		double priceIncreaseThreshold = 20.0;
		double volumeIncreaseThreshold = 1.5;
		int minFlagpoleBars = 4;
		int maxFlagpoleBars = 15;
		int minFlagBars = 5;
		int maxFlagBars = 50;
		int historicalVolumePeriod = 20;
		StockDataInfo sd = new StockDataInfo(stockDataList);

		if (stockDataList.size() < minFlagpoleBars + minFlagBars) {
			return false;
		}

		double latestClose = stockDataList.get(0).getClose();
		if (latestClose == 0.0) {
			return false;
		}

		double flagpoleHigh = 0.0;
		int highIndex = -1;
		double highestSwingHigh = 0.0;
		int highestHighIndex = -1;

		for (int i = 1; i <= maxFlagBars && i < stockDataList.size(); i++) {
			if ("H".equals(stockDataList.get(i).getSwingType())) {
				if (stockDataList.get(i).getHigh() <= highestSwingHigh) {
					continue;
				}

				double currentHigh = stockDataList.get(i).getHigh();
				double highMovement = Math.abs(currentHigh - latestClose) / latestClose * 100;
				if (highMovement > flagRangeThreshold) {
					return false;
				}

				for (int j = i - 1; j >= 0; j--) {
					double highPrice = stockDataList.get(j).getHigh();
					double lowPrice = stockDataList.get(j).getLow();
					double highPriceMovement = Math.abs(highPrice - currentHigh) / currentHigh * 100;
					double lowPriceMovement = Math.abs(lowPrice - currentHigh) / currentHigh * 100;
					if (highPriceMovement > flagRangeThreshold || lowPriceMovement > flagRangeThreshold) {
						return false;
					}
				}

				highestSwingHigh = currentHigh;
				highestHighIndex = i;
			}
		}

		if (highestHighIndex == -1 || highestHighIndex < minFlagBars || highestHighIndex > maxFlagBars) {
			return false;
		}

		highIndex = highestHighIndex;
		flagpoleHigh = highestSwingHigh;
		int flagBarCount = highIndex;

		double flagMaxHigh = flagpoleHigh;
		double flagMinLow = Double.MAX_VALUE;
		for (int i = highIndex - 1; i >= 0; i--) {
			flagMaxHigh = Math.max(flagMaxHigh, stockDataList.get(i).getHigh());
			flagMinLow = Math.min(flagMinLow, stockDataList.get(i).getLow());
		}
		double flagRange = (flagMaxHigh - flagMinLow) / flagMinLow * 100;
		LocalDate flagStart = highIndex > 1 ? stockDataList.get(highIndex - 1).getDate() : null;
		LocalDate flagEnd = stockDataList.get(0).getDate();

		double flagpoleLow = Double.MAX_VALUE;
		int lowIndex = -1;
		LocalDate startDate = null;
		LocalDate endDate = stockDataList.get(highIndex).getDate();
		double flagpoleSize = 0.0;

		for (int i = highIndex + 1; i <= highIndex + maxFlagpoleBars && i < stockDataList.size(); i++) {
			if ("L".equals(stockDataList.get(i).getSwingType())) {
				double currentLow = stockDataList.get(i).getLow();
				int barCount = i - highIndex;
				double currentFlagpoleSize = (flagpoleHigh - currentLow) / currentLow * 100;

				if (barCount >= minFlagpoleBars && barCount <= maxFlagpoleBars
						&& currentFlagpoleSize > priceIncreaseThreshold && currentLow < flagpoleLow) {
					lowIndex = i;
					flagpoleLow = currentLow;
					startDate = stockDataList.get(i).getDate();
					flagpoleSize = currentFlagpoleSize;
				}
			}
		}

		if (lowIndex == -1) {
			return false;
		}

		if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
			return false;
		}

		int barCount = lowIndex - highIndex;
		double flagpoleVolumeSum = 0.0;
		for (int i = highIndex; i <= lowIndex; i++) {
			flagpoleVolumeSum += stockDataList.get(i).getVolume();
		}
		double avgFlagpoleVolume = flagpoleVolumeSum / barCount;

		double historicalVolumeSum = 0.0;
		int historicalCount = 0;
		for (int i = lowIndex + 1; i < lowIndex + 1 + historicalVolumePeriod && i < stockDataList.size(); i++) {
			historicalVolumeSum += stockDataList.get(i).getVolume();
			historicalCount++;
		}
		double volumeIncrease = (historicalCount > 0) ? avgFlagpoleVolume / (historicalVolumeSum / historicalCount)
				: 0.0;

		Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).country(config.country).build();
		String rankStr = " FlagpoleLow:" + String.format("%.2f", flagpoleLow) + " FlagpoleHigh:"
				+ String.format("%.2f", flagpoleHigh) + " PercentageIncrease:" + String.format("%.2f%%", flagpoleSize)
				+ " StartDate:" + (startDate != null ? startDate.toString() : "N/A") + " EndDate:"
				+ (endDate != null ? endDate.toString() : "N/A") + " VolumeIncrease:"
				+ String.format("%.2f", volumeIncrease) + " LatestClose:" + String.format("%.2f", latestClose)
				+ " FlagRange:" + String.format("%.2f%%", flagRange) + " FlagDuration:" + flagBarCount + " FlagStart:"
				+ (flagStart != null ? flagStart.toString() : "N/A") + " FlagEnd:"
				+ (flagEnd != null ? flagEnd.toString() : "N/A");
		pattern.setRankStr(rankStr);
		pattern.setRank(calculateADR(stockDataList, 20));
		patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		return true;
	}

	private boolean findAscendingTriangleStocks(String symbol, List<StockData> stockDataList) {
		if (stockDataList == null || stockDataList.isEmpty() || !priceCheck(stockDataList)
				|| !volumeCheck(stockDataList)) {
			return false;
		}
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return false;

		if (!symbol.equals("DALBHARAT")) {
			// return false;
		}

		String type = "AscendingTriangle";
		double resistanceTolerance = 1.5;
		int maxTriangleBars = 50;
		int minTouches = 2;
		int minBarsBetweenHighs = 5;
		double maxHighsPercentage = 0.5;
		double maxTriangleRange = 15.0;
		double maxLastTwoLowsPercentage = 5.0;
		int minBarsBetweenLows = 5;
		double idealSlope = 1.0;
		double maxSlopeDeviation = 5.0;
		double minRankScore = 50.0;
		StockDataInfo sd = new StockDataInfo(stockDataList);

		double latestClose = stockDataList.get(0).getClose();
		if (latestClose == 0.0) {
			return false;
		}

		double resistanceLevel = 0.0;
		int highIndex = -1;
		List<Integer> swingHighIndices = new ArrayList<>();
		double highestHigh = 0.0;

		for (int i = 1; i <= maxTriangleBars && i < stockDataList.size(); i++) {
			if ("H".equals(stockDataList.get(i).getSwingType())) {
				double currentHigh = stockDataList.get(i).getHigh();
				if (currentHigh > highestHigh) {
					highestHigh = currentHigh;
					swingHighIndices.clear();
					swingHighIndices.add(i);
				} else if (Math.abs(currentHigh - highestHigh) / highestHigh * 100 <= resistanceTolerance) {
					if (swingHighIndices.isEmpty()
							|| (i - swingHighIndices.get(swingHighIndices.size() - 1) >= minBarsBetweenHighs)) {
						swingHighIndices.add(i);
					}
				}
			}
		}

		if (swingHighIndices.size() < minTouches || swingHighIndices.get(0) > maxTriangleBars) {
			return false;
		}

		highIndex = swingHighIndices.get(0);
		resistanceLevel = highestHigh;

		double highsPercentage = 0.0;
		if (swingHighIndices.size() >= 2) {
			double minHigh = Double.MAX_VALUE;
			double maxHigh = 0.0;
			for (int i : swingHighIndices) {
				double high = stockDataList.get(i).getHigh();
				minHigh = Math.min(minHigh, high);
				maxHigh = Math.max(maxHigh, high);
			}
			if (minHigh > 0.0) {
				highsPercentage = (maxHigh - minHigh) / minHigh * 100;
				if (highsPercentage >= maxHighsPercentage) {
					// return false;
				}
			}
		}

		List<Integer> swingLowIndices = new ArrayList<>();
		for (int i = 1; i <= maxTriangleBars && i < stockDataList.size(); i++) {
			if ("L".equals(stockDataList.get(i).getSwingType())) {
				double currentLow = stockDataList.get(i).getLow();
				if (swingLowIndices.isEmpty()
						|| currentLow < stockDataList.get(swingLowIndices.get(swingLowIndices.size() - 1)).getLow()) {
					swingLowIndices.add(i);
				}
			}
		}

		if (swingLowIndices.size() < minTouches || swingLowIndices.get(0) > maxTriangleBars) {
			return false;
		}

		double supportSlope = 0.0;
		if (swingLowIndices.size() >= 2) {
			double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumXX = 0.0;
			int n = swingLowIndices.size();
			for (int index : swingLowIndices) {
				double x = -index;
				double y = stockDataList.get(index).getLow();
				sumX += x;
				sumY += y;
				sumXY += x * y;
				sumXX += x * x;
			}
			double xMean = sumX / n;
			double yMean = sumY / n;
			supportSlope = (sumXY - n * xMean * yMean) / (sumXX - n * xMean * xMean);
		}

		double triangleMinLow = Double.MAX_VALUE;
		double triangleMaxHigh = resistanceLevel;
		int earliestIndex = Math.max(highIndex, swingLowIndices.get(0));
		int latestIndex = Math.min(highIndex, swingLowIndices.get(0));
		for (int i = earliestIndex - 1; i >= 0; i--) {
			triangleMaxHigh = Math.max(triangleMaxHigh, stockDataList.get(i).getHigh());
			triangleMinLow = Math.min(triangleMinLow, stockDataList.get(i).getLow());
		}
		double triangleRange = (triangleMaxHigh - triangleMinLow) / triangleMinLow * 100;
		if (triangleRange > maxTriangleRange) {
			return false;
		}

		LocalDate startDate = stockDataList.get(earliestIndex).getDate();
		LocalDate endDate = stockDataList.get(0).getDate();
		int triangleDuration = earliestIndex;

		if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
			return false;
		}

		double lastTwoLowsPercentage = 0.0;
		if (swingLowIndices.size() >= 2) {
			double recentLow = stockDataList.get(swingLowIndices.get(0)).getLow();
			double previousLow = stockDataList.get(swingLowIndices.get(1)).getLow();
			lastTwoLowsPercentage = (recentLow - previousLow) / previousLow * 100;
		}

		double rankScore = 0.0;
		if (swingLowIndices.size() >= 2 && swingHighIndices.size() >= 2) {
			double touchesScore = ((double) (swingHighIndices.size() + swingLowIndices.size()) - 4) / (10 - 4) * 100;
			touchesScore = Math.max(0, Math.min(100, touchesScore));

			double rangeScore = (1 - (triangleRange / 20.0)) * 100;
			double lowsSpreadScore = (1 - (lastTwoLowsPercentage / maxLastTwoLowsPercentage)) * 100;
			rangeScore = Math.max(0, (rangeScore + lowsSpreadScore) / 2);

			double timeScore = 100.0;
			if (swingLowIndices.size() >= 2) {
				int minLowBarDistance = Integer.MAX_VALUE;
				for (int i = 1; i < swingLowIndices.size(); i++) {
					int distance = swingLowIndices.get(i - 1) - swingLowIndices.get(i);
					minLowBarDistance = Math.min(minLowBarDistance, distance);
				}
				timeScore = ((double) minLowBarDistance / minBarsBetweenLows) * 100;
				timeScore = Math.max(0, Math.min(100, timeScore));
			}

			double slopeScore = (1 - Math.abs(supportSlope - idealSlope) / maxSlopeDeviation) * 100;
			slopeScore = Math.max(0, Math.min(100, slopeScore));

			double recencyScore = (1 - ((double) latestIndex / maxTriangleBars)) * 100;
			recencyScore = Math.max(0, Math.min(100, recencyScore));

			rankScore = 0.35 * touchesScore + 0.25 * rangeScore + 0.20 * timeScore + 0.15 * slopeScore
					+ 0.10 * recencyScore;
		}

		StringBuilder highTouchDates = new StringBuilder();
		StringBuilder highTouchPrices = new StringBuilder();
		for (int index : swingHighIndices) {
			LocalDate date = stockDataList.get(index).getDate();
			double price = stockDataList.get(index).getHigh();
			if (date != null) {
				highTouchDates.append(date.toString()).append(",");
				highTouchPrices.append(String.format("%.2f", price)).append(",");
			}
		}
		if (highTouchDates.length() > 0) {
			highTouchDates.setLength(highTouchDates.length() - 1);
			highTouchPrices.setLength(highTouchPrices.length() - 1);
		}

		StringBuilder lowTouchDates = new StringBuilder();
		StringBuilder lowTouchPrices = new StringBuilder();
		for (int index : swingLowIndices) {
			LocalDate date = stockDataList.get(index).getDate();
			double price = stockDataList.get(index).getLow();
			if (date != null) {
				lowTouchDates.append(date.toString()).append(",");
				lowTouchPrices.append(String.format("%.2f", price)).append(",");
			}
		}
		if (lowTouchDates.length() > 0) {
			lowTouchDates.setLength(lowTouchDates.length() - 1);
			lowTouchPrices.setLength(lowTouchPrices.length() - 1);
		}

		Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).rank(rankScore).country(config.country).build();
		String rankStr = " ResistanceLevel:" + String.format("%.2f", resistanceLevel) + " SupportLow:"
				+ String.format("%.2f", triangleMinLow) + " StartDate:"
				+ (startDate != null ? startDate.toString() : "N/A") + " EndDate:"
				+ (endDate != null ? endDate.toString() : "N/A") + " LatestClose:" + String.format("%.2f", latestClose)
				+ " TriangleRange:" + String.format("%.2f%%", triangleRange) + " TriangleDuration:" + triangleDuration
				+ " HighTouches:" + swingHighIndices.size() + " LowTouches:" + swingLowIndices.size()
				+ " HighTouchDates:" + (highTouchDates.length() > 0 ? highTouchDates.toString() : "N/A")
				+ " LowTouchDates:" + (lowTouchDates.length() > 0 ? lowTouchDates.toString() : "N/A")
				+ " HighsPercentage:" + String.format("%.2f%%", highsPercentage) + " LastTwoLowsPercentage:"
				+ String.format("%.2f%%", lastTwoLowsPercentage) + " SupportSlope:"
				+ (swingLowIndices.size() >= 2 ? String.format("%.2f", supportSlope) : "N/A") + " HighTouchPrices:"
				+ (highTouchPrices.length() > 0 ? highTouchPrices.toString() : "N/A") + " LowTouchPrices:"
				+ (lowTouchPrices.length() > 0 ? lowTouchPrices.toString() : "N/A") + " RankScore:"
				+ String.format("%.2f", rankScore);
		pattern.setRankStr(rankStr);
		patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		return true;
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

	public void getRestingStocksAfterBurst() {
		MarketConfig config = marketConfigs.get(Market.NSE); // Default to NSE, adjust if needed
		if (config == null)
			return;

		List<String> patternNames = Arrays.asList("Gapup", "PowerUpCandle", "PurpleDot");
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate;
		int businessDaysToSubtract = 45;

		while (businessDaysToSubtract > 0) {
			startDate = startDate.minusDays(1);
			if (startDate.getDayOfWeek() != DayOfWeek.SATURDAY && startDate.getDayOfWeek() != DayOfWeek.SUNDAY) {
				businessDaysToSubtract--;
			}
		}

		List<Pattern> results = patternRepository.findDistinctByPatternNameInAndCountryEqualsAndDateRange(patternNames,
				config.country, startDate, endDate);

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

				double percentage = (pattern.getClose().doubleValue() - stockDataList.get(j).getClose())
						/ pattern.getClose().doubleValue() * 100;
				if (percentage > 5 || percentage < -5) {
					validPattern = false;
					break;
				}
			}

			if (!validPattern)
				continue;

			BBValues bb = calculateBBAndMaValues(pattern.getSymbol(), stockDataList, 0);

			String type = "BurstRetracement";

			StockData sd1 = new StockData();
			sd1.setSymbol(pattern.getSymbol());
			pattern.setStockData(sd1);
			pattern.setMaDistance(sd.getClose_0() - bb.getMa_10());
			String rankStr = "Pattern:" + pattern.getPatternName() + " On:" + pattern.getDate() + " MA10 distance "
					+ Double.toString(pattern.getMaDistance());
			pattern.setRankStr(rankStr);
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			patternResultsSet.add(pattern.getSymbol());

		}

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

	private void markSwingHighsAndLows(List<StockData> stockDataList) {
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

	private void watchList() {
		File folder = new File("C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\");
		File[] files = folder.listFiles((dir, name) -> name.matches(".*Watchlist-.+\\.txt"));

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
					+ config.country + ")</h2>");
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
				String rank = pattern.getRank() > 0 ? Double.toString(pattern.getRank()) : "";

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

		if (sd.volume_0 < 50000)
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

}
