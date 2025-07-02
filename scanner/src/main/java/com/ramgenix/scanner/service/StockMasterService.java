package com.ramgenix.scanner.service;

import com.ramgenix.scanner.entity.Pattern;
import com.ramgenix.scanner.entity.StockData;
import com.ramgenix.scanner.entity.StockMaster;
import com.ramgenix.scanner.repository.StockMasterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for loading stock master data.
 */
@Service
public class StockMasterService {

    @Autowired
    private StockMasterRepository stockMasterRepository;

    /**
     * Load stock master data from a file.
     * 
     * @param filename The name of the file containing stock master data.
     * @throws Exception If an error occurs while reading the file.
     */
    public void loadStockMasterData(String filename) throws Exception {
    	/*String newFilename="C:\\\\Users\\\\USER\\\\OneDrive - RamGenix\\\\ASX\\"+filename;
        try (BufferedReader br = new BufferedReader(new FileReader(newFilename))) {
            String line;
            boolean isHeader = true;

            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue; // Skip the header line
                }

                String[] fields = line.split(",");
                if (fields.length != 8) {
                    // Handle cases where the line doesn't have the expected number of fields
                    System.err.println("Invalid line: " + line);
                    continue;
                }

                String ticker = fields[1];

                // Check if the record already exists
                Optional<StockMaster> existingRecord = stockMasterRepository.findByTicker(ticker);

                StockMaster stockMaster;
                if (existingRecord.isPresent()) {
                    // Update existing record
                    stockMaster = existingRecord.get();
                } else {
                    // Create a new record
                    stockMaster = new StockMaster();
                }

                System.out.println(line);
                stockMaster.setName(removeQuotes(fields[0]));
                stockMaster.setTicker(ticker);
                stockMaster.setSubSector(removeQuotes(fields[2]));
                stockMaster.setMarketCap(parseDouble(removeQuotes(fields[3])));
                stockMaster.setTtmPeRatio(parseDouble(removeQuotes(fields[4])));
                stockMaster.setPbRatio(parseDouble(removeQuotes(fields[5])));
                stockMaster.setDividendYield(parseDouble(removeQuotes(fields[6])));
                stockMaster.setPeRatio(parseDouble(removeQuotes(fields[7])));

                stockMasterRepository.save(stockMaster);
            }
        }*/
        
        createWatchLists();
        
        
    }

    private void createWatchLists() {
    	 List<StockMaster> stockMasterList = stockMasterRepository.findAll();

         // Group the entries by sub_sector
         Map<String, List<StockMaster>> groupedBySubSector = stockMasterList.stream()
                 .collect(Collectors.groupingBy(StockMaster::getSubSector));

         groupedBySubSector.forEach((subSector, stocks) -> {
             String fileName = subSector+ ".txt";
             try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            	 int i=0;
                 for (StockMaster stock : stocks) {
                	 i++;
                     writer.write("NSE:"+stock.getTicker()+"-EQ");
                     if(i<stocks.size())  writer.write(",");
                     
                 }
                 System.out.println("Created watchlist file: " + fileName);
             } catch (IOException e) {
                 System.err.println("Error writing to file: " + fileName);
                 e.printStackTrace();
             }
             
             String tradingViewFileName = subSector+ ".tv.txt";
             try (BufferedWriter writer = new BufferedWriter(new FileWriter(tradingViewFileName))) {
            	 int i=0;
                 for (StockMaster stock : stocks) {
                	 i++;
                     writer.write("NSE:"+stock.getTicker());
                     if(i<stocks.size())  writer.write("+");
                     
                 }
                 System.out.println("Created watchlist file: " + tradingViewFileName);
             } catch (IOException e) {
                 System.err.println("Error writing to file: " + tradingViewFileName);
                 e.printStackTrace();
             }
             
             
         });
		
	}

	
    
    private String removeQuotes(String value) {
        if (value != null) {
            return value.replace("\"", "").trim();
        }
        return null;
    }
}
