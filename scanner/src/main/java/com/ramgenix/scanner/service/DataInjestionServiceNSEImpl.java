package com.ramgenix.scanner.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
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
	}

	BBValues bbValues = null;
	Map<String, List<StockData>> stockDataMap = new ConcurrentHashMap<>();

	Map<String, List<Pattern>> patternResults = new ConcurrentHashMap<>();
	Set<String> patternResultsSet = new HashSet<>();
	String outputFilePath = "C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\newhtml\\";
	private String stockchartsurl = "p=D&b=5&g=0&i=t2623804363c&r=1751514219638";
	Set<String> inputWatchList = new HashSet<>();
	String watchlist = "";

	private static Logger LOG = LoggerFactory.getLogger(DataInjestionServiceNSEImpl.class);

	public void processLatestData(String watchlist) {
		this.watchlist = watchlist;
		// Construct the file path using the watchlist parameter
		String filePath = "C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\" + "Watchlist-" + watchlist + ".txt";

		// Read from the file and add strings to the inputWatchList set
		try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
			String line;
			while ((line = reader.readLine()) != null) {
				inputWatchList.add(line);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		processLatestData();
	}

	public String processLatestData() {

		stockDataMap.clear();
		stockDataMap = prepareDataForProcessing();

		stockDataMap.forEach((symbol, stockDataList) -> {
			if (inputWatchList.isEmpty() || inputWatchList.contains(symbol)) {
				markSwingHighsAndLows(stockDataList);

				bbValues = calculateBBAndMaValues(symbol, stockDataList, 0);

				if (volumeCheck(stockDataList) && priceCheck(stockDataList) && symbolCheck(stockDataList)) {
					findVolumeShockers(symbol, stockDataList);
					findPurpleDotStocks(symbol, stockDataList);
					findGapUpStocks(symbol, stockDataList);
					findPowerUpCandleStocks(symbol, stockDataList);
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

		if (StringUtils.isEmpty(watchlist))
			watchList();

		getRestingStocksAfterBurst();

		// Process all pattern types
		patternToFileNameMap.forEach(this::saveSortedPatterns);

		return null;
	}

	private void saveSortedPatterns(String patternName, String fileName) {
		List<Pattern> patterns = patternResults.get(patternName);
		if (patterns != null && !patterns.isEmpty()) {
			// Sort and save full pattern file
			patterns.sort(new RankComparator());
			savePatternsToFile(dates.get(0), patterns, fileName, false, false);

			// Generate incremental patterns
			List<Pattern> incrementalPatterns = getIncrementalPatterns(patternName, patterns);
			if (!incrementalPatterns.isEmpty()) {
				incrementalPatterns.sort(new RankComparator());
				savePatternsToFile(dates.get(0), incrementalPatterns, "incremental-" + fileName, false, false);
			}
		}
	}

	private List<Pattern> getIncrementalPatterns(String patternName, List<Pattern> currentPatterns) {
		Set<String> previousSymbols = getPreviousDaySymbols(patternName);
		List<Pattern> incrementalPatterns = new ArrayList<>();

		for (Pattern pattern : currentPatterns) {
			String symbol = pattern.getStockData().getSymbol();
			if (!previousSymbols.contains(symbol)) {
				incrementalPatterns.add(pattern);
			}
		}

		return incrementalPatterns;
	}

	private Set<String> getPreviousDaySymbols(String patternName) {
		Set<String> symbols = new HashSet<>();
		String fileName = patternToFileNameMap.get(patternName);
		if (fileName == null) {
			LOG.warn("No file name mapping found for pattern: {}", patternName);
			return symbols;
		}

		LocalDate[] previousDates = { processingDate.minusDays(1), processingDate.minusDays(2) };

		for (LocalDate date : previousDates) {
			String formattedDate = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
			String filePath = outputFilePath + fileName + "_" + formattedDate + ".html";
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

		LOG.info("No previous file found for pattern {}, treating all patterns as incremental", patternName);
		return symbols;
	}

	private boolean symbolCheck(List<StockData> stockDataList) {
		for (StockData sd : stockDataList) {
			if (!sd.getSeries().equals("EQ"))
				return false;
		}

		if (isETFStock(stockDataList.get(0).getSymbol()))
			return false;

		return true;
	}

	private Map<String, List<StockData>> prepareDataForProcessing() {
		hammerStrings.clear();
		bullEngulfStrings.clear();
		greenRedStrings.clear();
		shootingStarStrings.clear();
		bearEngulfStrings.clear();
		redGreenStrings.clear();

		patternResults.clear();
		dates.clear();

		bbValues = null;

		LOG.info("EXECUTING : command line runner");

		try (BufferedReader dateFile = new BufferedReader(
				new FileReader("C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\dates.txt"))) {
			String line;
			while ((line = dateFile.readLine()) != null) {
				if (line.contains("#")) {
					continue;
				}
				dates.add(line);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		String lowercaseDateString = dates.get(0).toLowerCase();
		processingDate = DateFormatChecker.processDate(lowercaseDateString);

		for (String date : dates) {
			if (!date.isEmpty()) {
				if (DateFormatChecker.isNewDateFormat(date))
					GetStockDataFromNewCSV(date, stockDataMap);
				else
					GetStockDataFromCSV(date, stockDataMap);

			}
		}
		return stockDataMap;
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

	private boolean findPowerUpCandleStocks(String symbol, List<StockData> stockDataList) {
		StockDataInfo sd = new StockDataInfo(stockDataList);

		double changePct = ((sd.close_0 - sd.close_1) / sd.close_1) * 100;

		if (changePct > 7) {
			String type = "PowerUpCandle";

			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

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

	private boolean findGapUpStocks(String symbol, List<StockData> stockDataList) {

		StockDataInfo sd = new StockDataInfo(stockDataList);

		double gapPercentage = ((sd.open_0 - sd.close_1) / sd.close_1) * 100;

		if (gapPercentage > 3.5 && sd.volume_0 > sd.volume_1 && sd.close_0 > sd.open_0 && sd.close_0 > sd.close_1) {
			String type = "Gapup";

			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

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

		if (sd.tail_0 >= (2 * sd.body_0) && sd.low_0 <= sd.low_1 && (sd.tail_0 / sd.head_0) > 2) {
			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

			pattern.setRank(calculateADR(stockDataList, 20));
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
			hammerStrings.add(symbol);
			return true;
		}
		return false;

	}

	private boolean findNearSupportStocks(String symbol, List<StockData> stockDataList) {
		// Validate input
		if (stockDataList == null || stockDataList.isEmpty() || !volumeCheck(stockDataList)
				|| !priceCheck(stockDataList)) {
			return false;
		}

		String type = "NearSupport";
		double proximityThreshold = 2.0; // Price within ±2% of support level
		StockDataInfo sd = new StockDataInfo(stockDataList);

		// Ensure enough data points for swing detection
		if (stockDataList.size() < 3) {
			return false;
		}

		// Get the latest closing price
		double latestClose = stockDataList.get(0).getClose();
		if (latestClose == 0.0) {
			return false; // Avoid invalid close price
		}

		// Find the first prior swing low below the latest close
		double supportLevel = 0.0;
		double percentageDiff = Double.MAX_VALUE;
		LocalDate supportDate = null;

		for (int i = 1; i < stockDataList.size(); i++) {
			StockData stockData = stockDataList.get(i);
			if ("L".equals(stockData.getSwingType())) {
				double lowPrice = stockData.getLow();
				if (lowPrice < latestClose) { // Strictly less than current close
					percentageDiff = Math.abs((latestClose - lowPrice) / lowPrice) * 100;
					if (percentageDiff > proximityThreshold) {
						return false; // Return false if above threshold
					}
					supportLevel = lowPrice;
					supportDate = stockData.getDate();
					break; // Stop at the first valid swing low
				}
			}
		}

		// If no valid swing low found, return false
		if (supportLevel == 0.0) {
			return false;
		}

		// Create pattern for valid support level
		Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).build();
		String rankStr = " DistanceToSupport:" + String.format("%.2f%%", percentageDiff) + " SupportLevel:"
				+ String.format("%.2f", supportLevel) + " SupportDate:"
				+ (supportDate != null ? supportDate.toString() : "N/A") + " LatestClose:"
				+ String.format("%.2f", latestClose);
		pattern.setRankStr(rankStr);
		patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		return true;
	}

	private boolean findBreakoutBars(String symbol, List<StockData> stockDataList) {
		// Validate input
		if (stockDataList == null || stockDataList.isEmpty() || !volumeCheck(stockDataList)
				|| !priceCheck(stockDataList)) {
			return false;
		}

		// Configuration
		final String patternType = "BreakoutBars";
		final int maxSwingHighLookback = 80; // Max bars to find breakout level bar
		final int breakoutLevelWindow = 20; // Window (20 bars prior) for breakout level high
		final double postBreakoutThreshold = 10.0; // Minimum 10% high above breakout level after breakout bar
		final double proximityThreshold = 5.0; // Current close within ±5% of breakout level
		final int recentBarsToExclude = 3; // Exclude most recent 3 bars from pullback check

		// Ensure sufficient data
		if (stockDataList.size() < maxSwingHighLookback + breakoutLevelWindow + 1) {
			return false;
		}

		// Get current closing price
		double currentClose = stockDataList.get(0).getClose();
		if (currentClose == 0.0) {
			return false;
		}

		// Find breakout level bar, breakout bar, and post-breakout high
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
				// Check if breakout level is highest high in prior 20 bars
				boolean isHighest = true;
				for (int j = i + 1; j <= Math.min(stockDataList.size() - 1, i + breakoutLevelWindow); j++) {
					if (stockDataList.get(j).getHigh() > high) {
						isHighest = false;
						break;
					}
				}
				if (isHighest) {
					// Check if current price is within ±5% of breakout level
					double proximityPercent = Math.abs((currentClose - high) / high) * 100;
					if (proximityPercent <= proximityThreshold) {
						// Find first breakout bar after swing high
						for (int j = i - 1; j >= 0; j--) {
							double close = stockDataList.get(j).getClose();
							if (close > high) {
								// Temporarily store breakout bar details
								breakoutBarClose = close;
								breakoutBarIndex = j;
								breakoutBarDate = stockDataList.get(j).getDate();
								breakoutBarLow = stockDataList.get(j).getLow();
								// Find maximum post-breakout high (10% above breakout level)
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
								// Check if post-breakout high exists
								if (postBreakoutHighIndex != -1) {
									// Check no pullback below breakout bar low after breakout bar (exclude last 3
									// bars)
									validPullback = true;
									for (int k = breakoutBarIndex; k >= recentBarsToExclude; k--) {
										double low = stockDataList.get(k).getLow();
										if (low < breakoutBarClose) {
											validPullback = false;
											break;
										}
									}
									// If all conditions are met, accept this breakout bar and stop searching
									if (validPullback) {
										breakoutLevelPrice = high;
										breakoutLevelDate = data.getDate();
										breakoutLevelIndex = i;
										break;
									}
								}
								// If conditions fail, break to try the next breakout bar
								break;
							}
						}
						if (breakoutLevelIndex != -1 && postBreakoutHighIndex != -1 && validPullback) {
							break; // Stop at the first valid breakout with post-breakout high and no pullback
						}
					}
				}
			}
		}

		// Return false if no valid breakout, post-breakout high, or pullback condition
		// met
		if (breakoutLevelPrice == 0.0 || breakoutBarIndex == -1 || postBreakoutHighIndex == -1 || !validPullback) {
			return false;
		}

		// Create and store pattern
		StockDataInfo sd = new StockDataInfo(stockDataList);
		Pattern pattern = Pattern.builder().patternName(patternType).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).build();
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
		// Validate input
		if (stockDataList == null || stockDataList.isEmpty() || !priceCheck(stockDataList)) {
			return false;
		}

		String type = "BullFlag";
		double flagRangeThreshold = 10.0; // Flag highs and lows within 10% of flagpole high
		double priceIncreaseThreshold = 20.0; // Flagpole price increase > 20%
		double volumeIncreaseThreshold = 1.5; // Flagpole volume > 1.5x historical average (calculated, not enforced)
		int minFlagpoleBars = 4; // Minimum bars in flagpole
		int maxFlagpoleBars = 15; // Maximum bars in flagpole
		int minFlagBars = 5; // Minimum bars in flag
		int maxFlagBars = 50; // Maximum bars in flag
		int historicalVolumePeriod = 20; // Historical volume period
		StockDataInfo sd = new StockDataInfo(stockDataList);

		// Ensure enough data points
		if (stockDataList.size() < minFlagpoleBars + minFlagBars) {
			return false;
		}

		// Get the latest closing price
		double latestClose = stockDataList.get(0).getClose();
		if (latestClose == 0.0) {
			return false; // Avoid invalid close price
		}

		// Find flag: highest swing high where subsequent highs/lows are within 10%
		double flagpoleHigh = 0.0;
		int highIndex = -1;
		double highestSwingHigh = 0.0;
		int highestHighIndex = -1;

		for (int i = 1; i <= maxFlagBars && i < stockDataList.size(); i++) {
			if ("H".equals(stockDataList.get(i).getSwingType())) {
				// Skip swing highs not higher than the current highest
				if (stockDataList.get(i).getHigh() <= highestSwingHigh) {
					continue;
				}

				double currentHigh = stockDataList.get(i).getHigh();

				// Check if current swing high invalidates flag by exceeding latestClose by more
				// than flagRangeThreshold
				double highMovement = Math.abs(currentHigh - latestClose) / latestClose * 100;
				if (highMovement > flagRangeThreshold) {
					return false; // Swing high breaks consolidation relative to latest close, invalidating flag
				}

				// Check if all subsequent highs and lows are within 10% of currentHigh
				for (int j = i - 1; j >= 0; j--) {
					double highPrice = stockDataList.get(j).getHigh();
					double lowPrice = stockDataList.get(j).getLow();
					double highPriceMovement = Math.abs(highPrice - currentHigh) / currentHigh * 100;
					double lowPriceMovement = Math.abs(lowPrice - currentHigh) / currentHigh * 100;
					if (highPriceMovement > flagRangeThreshold || lowPriceMovement > flagRangeThreshold) {
						return false; // Recent bar breaks consolidation, invalidating flag
					}
				}

				// Update highest swing high
				highestSwingHigh = currentHigh;
				highestHighIndex = i;
			}
		}

		if (highestHighIndex == -1 || highestHighIndex < minFlagBars || highestHighIndex > maxFlagBars) {
			return false; // No valid swing high or invalid flag duration
		}

		highIndex = highestHighIndex;
		flagpoleHigh = highestSwingHigh;
		int flagBarCount = highIndex;

		// Calculate flag range and dates
		double flagMaxHigh = flagpoleHigh;
		double flagMinLow = Double.MAX_VALUE;
		for (int i = highIndex - 1; i >= 0; i--) {
			flagMaxHigh = Math.max(flagMaxHigh, stockDataList.get(i).getHigh());
			flagMinLow = Math.min(flagMinLow, stockDataList.get(i).getLow());
		}
		double flagRange = (flagMaxHigh - flagMinLow) / flagMinLow * 100;
		LocalDate flagStart = highIndex > 1 ? stockDataList.get(highIndex - 1).getDate() : null;
		LocalDate flagEnd = stockDataList.get(0).getDate();

		// Find flagpole: lowest swing low within maxFlagpoleBars that meets criteria
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

				// Check if swing low is valid and lower than current flagpoleLow
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
			return false; // No valid swing low found
		}

		// Verify chronological order
		if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
			return false; // Invalid dates or startDate not before endDate
		}

		// Calculate flagpole volume
		int barCount = lowIndex - highIndex;
		double flagpoleVolumeSum = 0.0;
		for (int i = highIndex; i <= lowIndex; i++) {
			flagpoleVolumeSum += stockDataList.get(i).getVolume();
		}
		double avgFlagpoleVolume = flagpoleVolumeSum / barCount;

		// Calculate historical volume (20 bars before swing low)
		double historicalVolumeSum = 0.0;
		int historicalCount = 0;
		for (int i = lowIndex + 1; i < lowIndex + 1 + historicalVolumePeriod && i < stockDataList.size(); i++) {
			historicalVolumeSum += stockDataList.get(i).getVolume();
			historicalCount++;
		}
		double volumeIncrease = (historicalCount > 0) ? avgFlagpoleVolume / (historicalVolumeSum / historicalCount)
				: 0.0;

		// Create pattern for valid bull flag
		Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).build();
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
		// Validate input
		if (stockDataList == null || stockDataList.isEmpty() || !priceCheck(stockDataList)
				|| !volumeCheck(stockDataList) || !symbolCheck(stockDataList)) {
			return false;
		}

		if (!symbol.equals("DALBHARAT")) {
			// return false;
		}

		String type = "AscendingTriangle";
		double resistanceTolerance = 1.5; // Swing highs within 3% of resistance level
		int maxTriangleBars = 50; // Maximum bars in triangle
		int minTouches = 2; // Minimum swing highs/lows for resistance/support
		int minBarsBetweenHighs = 5; // Minimum bars between swing highs
		double maxHighsPercentage = 0.5; // Maximum percentage difference between swing highs
		double maxTriangleRange = 15.0; // Maximum triangle range percentage
		double maxLastTwoLowsPercentage = 5.0; // Maximum percentage difference between last two lows
		int minBarsBetweenLows = 5; // Minimum bars between swing lows for time cohesion
		double idealSlope = 1.0; // Ideal support slope for ranking
		double maxSlopeDeviation = 5.0; // Maximum slope deviation for ranking
		double minRankScore = 50.0; // Minimum rank score to accept pattern
		StockDataInfo sd = new StockDataInfo(stockDataList);

		// Get the latest closing price
		double latestClose = stockDataList.get(0).getClose();
		if (latestClose == 0.0) {
			return false; // Avoid invalid close price
		}

		// Find resistance: swing highs within resistanceTolerance of highest high
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
			return false; // Insufficient swing highs or invalid duration
		}

		highIndex = swingHighIndices.get(0); // Latest swing high for resistance
		resistanceLevel = highestHigh;

		// Calculate maximum percentage difference between any two swing highs
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
					// return false; // Maximum highs percentage too large
				}
			}
		}

		// Find support: swing lows with increasing prices when viewed forward in time
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
			return false; // Insufficient swing lows or invalid duration
		}

		// Calculate support slope using linear regression
		double supportSlope = 0.0;
		if (swingLowIndices.size() >= 2) {
			double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumXX = 0.0;
			int n = swingLowIndices.size();
			for (int index : swingLowIndices) {
				double x = -index; // Flip index to align with forward time
				double y = stockDataList.get(index).getLow();
				sumX += x;
				sumY += y;
				sumXY += x * y;
				sumXX += x * x;
			}
			double xMean = sumX / n;
			double yMean = sumY / n;
			supportSlope = (sumXY - n * xMean * yMean) / (sumXX - n * xMean * xMean);
			// if (supportSlope <= 0) {
			// return false; // Support line slope not positive
			// }
		}

		// Calculate triangle range and dates
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
			return false; // Triangle range too large
		}

		LocalDate startDate = stockDataList.get(earliestIndex).getDate();
		LocalDate endDate = stockDataList.get(0).getDate();
		int triangleDuration = earliestIndex;

		// Verify chronological order
		if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
			return false; // Invalid dates
		}

		// Calculate percentage between last two swing lows (bottom ascending line)
		double lastTwoLowsPercentage = 0.0;
		if (swingLowIndices.size() >= 2) {
			double recentLow = stockDataList.get(swingLowIndices.get(0)).getLow();
			double previousLow = stockDataList.get(swingLowIndices.get(1)).getLow();
			lastTwoLowsPercentage = (recentLow - previousLow) / previousLow * 100;
		}

		// Calculate ranking score
		double rankScore = 0.0;
		if (swingLowIndices.size() >= 2 && swingHighIndices.size() >= 2) {
			// Touches score: Normalize HighTouches + LowTouches (4 to 10)
			double touchesScore = ((double) (swingHighIndices.size() + swingLowIndices.size()) - 4) / (10 - 4) * 100;
			touchesScore = Math.max(0, Math.min(100, touchesScore));

			// Range score: Average of triangleRange and lastTwoLowsPercentage
			double rangeScore = (1 - (triangleRange / 20.0)) * 100; // maxTriangleRange for ranking = 20%
			double lowsSpreadScore = (1 - (lastTwoLowsPercentage / maxLastTwoLowsPercentage)) * 100;
			rangeScore = Math.max(0, (rangeScore + lowsSpreadScore) / 2);

			// Time cohesion score: Minimum bar distance between consecutive lows
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

			// Slope score: Proximity to ideal slope
			double slopeScore = (1 - Math.abs(supportSlope - idealSlope) / maxSlopeDeviation) * 100;
			slopeScore = Math.max(0, Math.min(100, slopeScore));

			// Recency score: Latest touch proximity
			double recencyScore = (1 - ((double) latestIndex / maxTriangleBars)) * 100;
			recencyScore = Math.max(0, Math.min(100, recencyScore));

			// Weighted total
			rankScore = 0.35 * touchesScore + 0.25 * rangeScore + 0.20 * timeScore + 0.15 * slopeScore
					+ 0.10 * recencyScore;
			if (rankScore < minRankScore) {
				// return false; // Pattern too weak
			}
		}

		// Collect high and low touch dates and prices
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
			highTouchDates.setLength(highTouchDates.length() - 1); // Remove trailing comma
			highTouchPrices.setLength(highTouchPrices.length() - 1); // Remove trailing comma
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
			lowTouchDates.setLength(lowTouchDates.length() - 1); // Remove trailing comma
			lowTouchPrices.setLength(lowTouchPrices.length() - 1); // Remove trailing comma
		}

		// Create pattern for valid ascending triangle
		Pattern pattern = Pattern.builder().patternName(type).stockData(stockDataList.get(0)).head(sd.head_0)
				.tail(sd.tail_0).body0(sd.body_0).rank(rankScore) // Set rank field
				.build();
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
		int rank = 0;

		if (sd.head_0 >= (1.5 * sd.body_0) && sd.high_0 >= sd.high_1 && (sd.head_0 > sd.tail_0)) {
			Pattern pattern = new Pattern();
			pattern.setPatternName(type);
			pattern.setStockData(stockDataList.get(0));
			pattern.setTail(sd.tail_0);
			pattern.setHead(sd.head_0);
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
		double half = (sd.open_1 + sd.close_1) / 2;

		if (sd.close_0 < sd.open_0 && sd.close_1 > sd.open_1 && sd.close_0 < half && sd.open_0 >= sd.close_1) {
			Pattern pattern = new Pattern();
			pattern.setPatternName(type);
			pattern.setStockData(stockDataList.get(0));
			pattern.setHead(sd.head_0);
			pattern.setTail(sd.tail_0);
			pattern.setBody0(sd.body_0);
			// pattern.setBbValues(bbValues);

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

		if (sd.close_0 < sd.open_0 && sd.close_1 > sd.open_1 && sd.close_2 > sd.open_2) {
			Pattern pattern = new Pattern();
			pattern.setPatternName(type);
			pattern.setStockData(stockDataList.get(0));
			pattern.setHead(sd.head_0);
			pattern.setTail(sd.tail_0);
			pattern.setBody0(sd.body_0);
			// pattern.setBbValues(bbValues);

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

		boolean pbs = false;

		if (sd.close_0 < bbValues.getMa_50() || bbValues.getMa_20() < bbValues.getMa_50())
			return false;

		if (sd.high_0 <= sd.high_1 && sd.high_1 <= sd.high_2 && sd.low_0 <= sd.low_1 && sd.low_1 <= sd.low_2) {
			pbs = true;
		}

		if ((sd.close_0 <= sd.open_0 && sd.close_1 <= sd.open_1 && sd.close_2 <= sd.open_2) /*
																							 * && sd.low_0 <= sd.low_1
																							 * && sd.low_1 <= sd.low_2
																							 */
				&& sd.close_3 > sd.open_3 && (sd.close_3 >= bbValues.getMa_20())) {
			pbs = true;
		}

		if (!pbs && (sd.close_0 <= sd.open_0 && sd.close_1 <= sd.open_1 && sd.close_2 <= sd.open_2
				&& sd.close_3 <= sd.open_3) /* && sd.low_0 <= sd.low_1 && sd.low_1 <= sd.low_2 */
				&& sd.close_4 > sd.open_4 && (sd.close_4 >= bbValues.getMa_20())) {
			pbs = true;
		}

		if (!pbs && (sd.close_0 <= sd.open_0
				&& sd.close_1 <= sd.open_1 && sd.close_2 <= sd.open_2 && sd.close_3 <= sd.open_3
				&& sd.close_4 <= sd.open_4) /* && sd.low_0 <= sd.low_1 && sd.low_1 <= sd.low_2 */
				&& sd.close_5 > sd.open_5 && (sd.close_5 >= bbValues.getMa_20())) {
			pbs = true;
		}

		if (!pbs)
			return false;

		int rank = 0;

		Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
				.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

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

		boolean pss = false;

		if ((sd.close_0 >= sd.open_0 && sd.close_1 >= sd.open_1 && sd.close_2 >= sd.open_2) /*
																							 * && sd.low_0 <= sd.low_1
																							 * && sd.low_1 <= sd.low_2
																							 */
				&& sd.close_3 < sd.open_3 && (sd.close_3 <= bbValues.getMa_20())) {
			pss = true;
		}

		if (!pss && (sd.close_0 >= sd.open_0 && sd.close_1 >= sd.open_1 && sd.close_2 >= sd.open_2
				&& sd.close_3 >= sd.open_3) /* && sd.low_0 <= sd.low_1 && sd.low_1 <= sd.low_2 */
				&& sd.close_4 < sd.open_4 && (sd.close_4 <= bbValues.getMa_20())) {
			pss = true;
		}

		if (!pss && (sd.close_0 >= sd.open_0
				&& sd.close_1 >= sd.open_1 && sd.close_2 >= sd.open_2 && sd.close_3 >= sd.open_3
				&& sd.close_4 >= sd.open_4) /* && sd.low_0 <= sd.low_1 && sd.low_1 <= sd.low_2 */
				&& sd.close_5 < sd.open_5 && (sd.close_5 <= bbValues.getMa_20())) {
			pss = true;
		}

		if (!pss && (sd.high_0 > sd.high_1 && sd.high_1 > sd.high_2) && sd.close_3 <= bbValues.getMa_20()) {
			pss = true;
		}

		if (!pss)
			return false;

		Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
				.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

		pattern.setRank(1);

		patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);

		return true;

	}

	private boolean greenRed(String symbol, List<StockData> stockDataList) {
		boolean ib = false;
		String type = "GreenRed";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		if (sd.high_0 <= sd.high_1 && sd.low_0 >= sd.low_1 && sd.close_1 >= sd.open_1)
			ib = true;
		int rank = 0;

		if (ib == true || (sd.close_0 < sd.open_0 && (sd.close_1 > sd.open_1) && sd.close_0 > sd.open_1
				&& sd.body_1 > sd.body_0 * 1.5)) {
			{
				Pattern pattern = new Pattern();
				String rankStr = "";
				pattern.setPatternName(type);
				pattern.setStockData(stockDataList.get(0));
				pattern.setHead(sd.head_0);
				pattern.setTail(sd.tail_0);
				pattern.setBody0(sd.body_1);
				/*
				 * if(sd.high_0 > bbValues.getUpper()) rank++; if(sd.volume_0 < sd.volume_1 )
				 * rank++;
				 */

				/*
				 * if(sd.tail_0 > sd.head_0) { rankStr ="tail bigger;" ; rank++;} if(sd.low_0 <
				 * bbValues.getUpper() && sd.high_0 > bbValues.getUpper()) { rankStr ="UBB;" ;
				 * rank++;} if(sd.low_0 < bbValues.getLower() && sd.high_0 >
				 * bbValues.getLower()) { rankStr ="LBB;" ; rank++;} if(sd.body_0 > sd.tail_0 &&
				 * sd.close_0 < sd.open_0) rank--;
				 * 
				 * if(sd.close_0 >200) { rankStr += "close > 200;" ; rank++;}
				 */

				pattern.setRank(rank);
				pattern.setRankStr(rankStr);
				pattern.setBbValues(bbValues);
				patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
				greenRedStrings.add(symbol);

				return true;

			}
		}
		return false;
	}

	private boolean redGreen(String symbol, List<StockData> stockDataList) {

		boolean ib = false;
		String type = "RedGreen";
		StockDataInfo sd = new StockDataInfo(stockDataList);
		if (sd.high_0 <= sd.high_1 && sd.low_0 >= sd.low_1 && sd.close_1 <= sd.open_1)
			ib = true;
		int rank = 0;

		if (ib == true || (sd.close_0 > sd.open_0 && (sd.close_1 < sd.open_1 && sd.close_2 < sd.open_2)
				&& (sd.body_1 / sd.body_0) >= 2) || (sd.close_1 <= sd.open_1 && sd.body_1 / sd.body_0 >= 2)) {
			if (sd.body_1 / sd.body_0 >= 2) {
				String rankStr = "";
				Pattern pattern = new Pattern();
				pattern.setPatternName(type);
				pattern.setStockData(stockDataList.get(0));
				pattern.setHead(sd.head_0);
				pattern.setTail(sd.tail_0);
				pattern.setBody0(sd.body_1);
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

				// if yday was bearish engulfing
				if (sd.close_1 < sd.open_1 && sd.close_2 > sd.open_2 && sd.body_1 > 5 && sd.body_1 > sd.body_2) {
					{
						rankStr += "Yesterday Bearish engulf;";
						rank++;
					}
				}

				if (sd.close_0 > sd.open_0 && sd.body_0 < sd.body_1) {
					rankStr += "Today green;";
					rank++;
					rank++;
				}

				// if(sd.volume_0 < sd.volume_1 ) { rankStr += "volume contraction;" ; rank++;}

				pattern.setRank(rank);
				pattern.setRankStr(rankStr);
				pattern.setBbValues(bbValues);
				patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
				redGreenStrings.add(symbol);

				return true;
			}
		}
		return false;
	}

	private boolean findVolumeShockers(String symbol, List<StockData> stockDataList) {
		StockDataInfo sd = new StockDataInfo(stockDataList);

		/*
		 * if (sd.close_0 < bbValues.getMa_200()) return false;
		 */

		if (sd.close_0 > sd.open_0 && ((sd.close_0 - sd.open_0) / sd.open_0) * 100 > 1
				&& ((bbValues.getTwentyMaAverage() > 0 && (sd.volume_0 > (bbValues.getTwentyMaAverage() * 1.5)))
						|| sd.volume_0 > (sd.volume_1 * 3))) {
			String type = "VolumeShockers";

			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();

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
				savePatternsToFile(dates.get(0), watchListResults, patternName, false, false);
			}
		}

	}

	public String getWatchlistForSymbol(String symbol) {
		return symbolToWatchlistMap.get(symbol.trim());
	}

	private void upday(String symbol, List<StockData> stockDataList) {
		String type = "UpDay";

		if (!volumeCheck(stockDataList) || !priceCheck(stockDataList))
			return;

		StockDataInfo sd = new StockDataInfo(stockDataList);

		if (sd.close_0 > sd.open_0) {
			Pattern pattern = new Pattern();
			pattern.setPatternName(type);
			pattern.setStockData(stockDataList.get(0));
			pattern.setHead(sd.head_0);
			pattern.setTail(sd.tail_0);
			pattern.setBody0(sd.body_0);
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		}

	}

	private void downDay(String symbol, List<StockData> stockDataList) {

		String type = "DownDay";

		StockDataInfo sd = new StockDataInfo(stockDataList);

		if (sd.close_0 < sd.open_0) {
			Pattern pattern = new Pattern();
			pattern.setPatternName(type);
			pattern.setStockData(stockDataList.get(0));
			pattern.setHead(sd.head_0);
			pattern.setTail(sd.tail_0);
			pattern.setBody0(sd.body_0);
			patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
		}

	}

	private void GetStockDataFromCSV(String date, Map<String, List<StockData>> stockDataMap) {
		String myfile = "C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\NSE\\cm" + date + "bhav.csv";
		// System.out.println(myfile);

		try (BufferedReader file = new BufferedReader(new FileReader(myfile))) {
			if (!file.ready()) {
				System.out.println("Unable to open " + myfile);
				return;
			}

			String line;
			file.readLine();

			while ((line = file.readLine()) != null) {
				String[] data = line.split(",");
				if (data.length == 13) {
					String symbol = data[0];
					String series = data[1];
					double open = Double.parseDouble(data[2]);
					double high = Double.parseDouble(data[3]);
					double low = Double.parseDouble(data[4]);
					double close = Double.parseDouble(data[5]);
					double last = Double.parseDouble(data[6]);
					double prevClose = Double.parseDouble(data[7]);
					long totalTradedQty = Long.parseLong(data[8]);
					double totalTradedValue = Double.parseDouble(data[9]);
					String timestamp = data[10];
					long totalTrades = Long.parseLong(data[11]);
					String isin = data[12];

					StockData stockData = new StockData();
					stockData.setSymbol(symbol);
					stockData.setSeries(series);
					stockData.setClose(close);
					stockData.setHigh(high);
					stockData.setLow(low);
					stockData.setOpen(open);
					stockData.setVolume(totalTradedQty);
					stockData.setSwingType("");

					DateTimeFormatter formatter = new DateTimeFormatterBuilder().parseCaseInsensitive()
							.appendPattern("ddMMMuuuu").toFormatter(Locale.ENGLISH);

					String lowercaseDateString = date.toLowerCase();
					LocalDate localDate = LocalDate.parse(lowercaseDateString, formatter);
					stockData.setDate(localDate);

					if (!series.equals("EQ")) {
						continue;
					}

					if (symbol.contains("ETF") || symbol.contains("BEES") || isETFStock(symbol)
							|| symbol.contains("LIQUID") || symbol.endsWith("LIQ") && symbol.endsWith("GOLD")
							|| symbol.endsWith("ADD") || symbol.contains("NIFTY") || symbol.endsWith("SENSEX")) {
						// continue;
					}

					stockDataMap.computeIfAbsent(symbol, k -> new ArrayList<>()).add(stockData);

				} else {
					System.out.println("Invalid number of fields in the line.");
				}

			}

			// System.out.println("getData over");
		} catch (IOException e) {
			// e.printStackTrace();
		}
	}

	private void GetStockDataFromNewCSV(String date, Map<String, List<StockData>> stockDataMap) {
		String myfile = "C:\\Users\\USER\\OneDrive - RamGenix\\ASX\\NSE\\" + "BhavCopy_NSE_CM_0_0_0_" + date
				+ "_F_0000.csv";
		// System.out.println(myfile);

		try (Reader reader = new FileReader(myfile)) {
			// Create a CsvToBeanBuilder instance
			CsvToBean<FinancialInstrument> csvToBean = new CsvToBeanBuilder<FinancialInstrument>(reader)
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

					DateTimeFormatter formatter = new DateTimeFormatterBuilder().parseCaseInsensitive()
							.appendPattern("ddMMMuuuu").toFormatter(Locale.ENGLISH);

					String lowercaseDateString = date.toLowerCase();
					// LocalDate localDate = LocalDate.parse(lowercaseDateString, formatter);
					stockData.setDate(DateFormatChecker.processDate(lowercaseDateString));

					if (!fi.getSctySrs().equals("EQ")) {
						continue;
					}

					if (symbol.contains("ETF") || symbol.contains("BEES") || isETFStock(symbol)
							|| symbol.contains("LIQUID") || symbol.endsWith("LIQ") && symbol.endsWith("GOLD")
							|| symbol.endsWith("ADD") || symbol.contains("NIFTY") || symbol.endsWith("SENSEX")) {
						continue;
					}

					stockDataMap.computeIfAbsent(symbol, k -> new ArrayList<>()).add(stockData);

				}
			}
		} catch (IOException e) {
			// e.printStackTrace();
			return;
		}

	}

	private void savePatternsToFile(String date, List<Pattern> results, String patternName, boolean saveToTable,
			boolean isSector) {
		String fileName = (!watchlist.isEmpty()) ? outputFilePath + watchlist + "_" : outputFilePath;
		fileName += (isSector ? "sector\\" : "") + patternName + "_" + date + ".html";

		try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
			// Writing HTML header
			writer.println("<!DOCTYPE html>");
			writer.println("<html lang=\"en\">");
			writer.println("<head>");
			writer.println("<meta charset=\"UTF-8\">");
			writer.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
			writer.println("<title>" + patternName + " Patterns</title>");
			writer.println(
					"<link href=\"https://stackpath.bootstrapcdn.com/bootstrap/4.5.2/css/bootstrap.min.css\" rel=\"stylesheet\">");
			writer.println("</head>");
			writer.println("<body>");

			// Writing patterns in a 3-column layout
			writer.println("<div class=\"container-fluid\">");
			writer.println("<div class=\"row\">");

			int count = 0;
			for (Pattern pattern : results) {

				if (saveToTable)
					savePattern(pattern, patternName, null);

				String rankStr = "";
				if (StringUtils.isNotEmpty(pattern.getRankStr()))
					rankStr = pattern.getRankStr();

				boolean purpleDot = false;

				/*
				 * if (pattern.getStockData() != null) {
				 * 
				 * List<Pattern> existingPatterns =
				 * patternRepository.findBySymbol(pattern.getStockData().getSymbol());
				 * 
				 * if (existingPatterns != null && existingPatterns.size() > 0) { for (Pattern
				 * patternResult : existingPatterns) { rankStr += patternResult.getPatternName()
				 * + " On " + (patternResult.getDate() != null ?
				 * patternResult.getDate().toString() : "") + " || "; if
				 * ("PurpleDot".equals(patternResult.getPatternName())) { purpleDot = true; } }
				 * }
				 * 
				 * }
				 */

				writer.println("<div class=\"col-md-6\">");
				writer.println("<div class=\"card\">");
				writer.println("<div class=\"card-body\">");
				writer.println("<h5 class=\"card-title\">Symbol: " + pattern.getStockData().getSymbol() + "</h5>");

				// displayStockMarketCap(writer, pattern);

				String backgroundClass = "white";
				if (rankStr.contains("Two day BB") || rankStr.contains("Three days BB")
						|| rankStr.contains("Four days" + " BB")) {
					backgroundClass = "style=\"background-color: green;\"";
				}

				else {
					backgroundClass = purpleDot ? "style=\"background-color: #dda0dd;\"" : ""; // Light purple
				}
				writer.println("<p class=\"card-text\" " + backgroundClass + ">Rank Str: " + rankStr + "</p>");

				if ("Upper BB".equals(patternName)) {
					String baseUrl = "http://localhost:8080";
					writer.println("<form action=\"" + baseUrl + "/watchlist\" method=\"post\">");
					writer.println("<input type=\"hidden\" name=\"symbol\" value=\""
							+ pattern.getStockData().getSymbol() + "\">");
					writer.println("<input type=\"hidden\" name=\"patternName\" value=\"" + patternName + "\">");
					writer.println("<input type=\"hidden\" name=\"date\" value=\"" + date + "\">");
					writer.println("<button type=\"submit\" class=\"btn btn-primary\">+ BB watchlist</button>");
					writer.println("</form>");
				}

				// Add more pattern information as needed
				writer.println("<IMG SRC=\"https://stockcharts.com/c-sc/sc?s=" + pattern.getStockData().getSymbol()
						+ ".IN&" + stockchartsurl + "\"" + " class=\"img-fluid\"" + ">");
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

			System.out.println("HTML file saved: " + fileName);
		} catch (IOException e) {
			e.printStackTrace();
			// Handle the exception (e.g., log it, show an error message, etc.)
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
			if (stockDataList.get(0).getClose() >= 20 && stockDataList.get(0).getClose() <= 10000)
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

	public void processLatestDataForPast() {

		Map<String, List<StockData>> stockDataMap = prepareDataForProcessing();

		stockDataMap.forEach((symbol, stockDataList) -> {
			processForSymbol(stockDataMap, symbol, 51);
		});
	}

	private void processForSymbol(Map<String, List<StockData>> stockDataMap, String symbol, int times) {
		List<StockData> stockDataList = stockDataMap.get(symbol);

		if (!volumeCheck(stockDataList) || !priceCheck(stockDataList) || !symbolCheck(stockDataList))
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
					volumeShockersFound = findVolumeShockers(symbol, newDataList);
				findPurpleDotStocks(symbol, newDataList);
				findGapUpStocks(symbol, newDataList);
				findPowerUpCandleStocks(symbol, newDataList);
			}
		}
	}

	public void getRestingStocksAfterBurst() {
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

		List<Pattern> results = patternRepository.findDistinctByPatternNameInAndDateRange(patternNames, startDate,
				endDate);

		patternResultsSet.clear();
		for (Pattern pattern : results) {

			if (pattern.getDate().equals(processingDate))
				continue;

			List<StockData> stockDataList = stockDataMap.get(pattern.getSymbol());
			if (stockDataList == null || stockDataList.isEmpty())
				continue;

			if (!volumeCheck(stockDataList) || !priceCheck(stockDataList) || !symbolCheck(stockDataList))
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
			{
				String type = "BurstRetracement";

				StockData sd1 = new StockData();
				sd1.setSymbol(pattern.getSymbol());
				pattern.setStockData(sd1);
				pattern.setRank(sd.getClose_0() - bb.getMa_10());
				String rankStr = "Pattern:" + pattern.getPatternName() + " On:" + pattern.getDate() + " MA10 distance "
						+ Double.toString(pattern.getMaDistance());
				pattern.setRankStr(rankStr);
				patternResults.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
				patternResultsSet.add(pattern.getSymbol());

			}
		}

	}

	private void findPurpleDotStocks(String symbol, List<StockData> stockDataList) {

		StockData stockData = stockDataList.get(0);
		StockDataInfo sd = new StockDataInfo(stockDataList);

		if (isPurpleCandle(stockData)) {
			String type = "PurpleDot";
			Pattern pattern = Pattern.builder().bbValues(bbValues).patternName(type).stockData(stockDataList.get(0))
					.head(sd.head_0).tail(sd.tail_0).body0(sd.body_0).build();
			savePattern(pattern, type, sd);
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
						.stockData(stockDataList.get(0)).build();
				patterns.add(pattern);
			}
			savePatternsToFile(dates.get(0), patterns, fileName, false, true);

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
