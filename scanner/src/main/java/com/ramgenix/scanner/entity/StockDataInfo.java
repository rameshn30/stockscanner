package com.ramgenix.scanner.entity;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode 
@ToString
public class StockDataInfo {	
	public StockDataInfo(List<StockData> stockDataList) {
		if (stockDataList.size() >= 2) {
            open_0 = stockDataList.get(0).getOpen();
            high_0 = stockDataList.get(0).getHigh();
            low_0 = stockDataList.get(0).getLow();
            close_0 = stockDataList.get(0).getClose();
            volume_0 = stockDataList.get(0).getVolume();

            open_1 = stockDataList.get(1).getOpen();
            high_1 = stockDataList.get(1).getHigh();
            low_1 = stockDataList.get(1).getLow();
            close_1 = stockDataList.get(1).getClose();
            volume_1 = stockDataList.get(1).getVolume();
            
            if (stockDataList.size() >= 3) {
	            open_2 = stockDataList.get(2).getOpen();
	            high_2 = stockDataList.get(2).getHigh();
	            low_2 = stockDataList.get(2).getLow();
	            close_2 = stockDataList.get(2).getClose();
	            volume_2 = stockDataList.get(2).getVolume();
            }
            if (stockDataList.size() >= 4) {
	            open_3 = stockDataList.get(3).getOpen();
	            high_3 = stockDataList.get(3).getHigh();
	            low_3 = stockDataList.get(3).getLow();
	            close_3 = stockDataList.get(3).getClose();
	            volume_3 = stockDataList.get(3).getVolume();
            }
            
            if (stockDataList.size() >= 5) {
	            open_4 = stockDataList.get(4).getOpen();
	            high_4 = stockDataList.get(4).getHigh();
	            low_4 = stockDataList.get(4).getLow();
	            close_4 = stockDataList.get(4).getClose();
	            volume_4 = stockDataList.get(4).getVolume();
            }
            
            if (stockDataList.size() >= 6) {
	            open_5 = stockDataList.get(5).getOpen();
	            high_5 = stockDataList.get(5).getHigh();
	            low_5 = stockDataList.get(5).getLow();
	            close_5 = stockDataList.get(5).getClose();
	            volume_5 = stockDataList.get(5).getVolume();
            }

            tail_0 = Math.min(open_0, close_0) - low_0;
            head_0 = high_0 - Math.max(open_0, close_0);
            body_0 = Math.max(open_0, close_0) - Math.min(open_0, close_0);
            
            tail_1 = Math.min(open_1, close_1) - low_1;
            head_1 = high_1 - Math.max(open_1, close_1);
            body_1 = Math.max(open_1, close_1) - Math.min(open_1, close_1);
            
            tail_2 = Math.min(open_2, close_2) - low_2;
            head_2 = high_2 - Math.max(open_2, close_2);
            body_2 = Math.max(open_2, close_2) - Math.min(open_2, close_2);
            
            tail_3 = Math.min(open_3, close_3) - low_3;
            head_3 = high_3 - Math.max(open_3, close_3);
            body_3 = Math.max(open_3, close_3) - Math.min(open_3, close_3);
            
            tail_4 = Math.min(open_4, close_4) - low_4;
            head_4 = high_4 - Math.max(open_4, close_4);
            body_4 = Math.max(open_4, close_4) - Math.min(open_4, close_4);
        }
	}
	public double open_0, high_0, low_0, close_0;
	public double open_1, high_1, low_1, close_1;
	public double open_2, high_2, low_2, close_2;
	public double open_3, high_3, low_3, close_3;
	public double open_4, high_4, low_4, close_4;
	public double open_5, high_5, low_5, close_5;
	public double tail_0, head_0, body_0;
	public double tail_1, head_1, body_1;
	public double tail_2, head_2, body_2;
	public double tail_3, head_3, body_3;
	public double tail_4, head_4, body_4;
	public double volume_0, volume_1, volume_2 , volume_3, volume_4 , volume_5;
}
