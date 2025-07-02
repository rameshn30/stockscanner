package com.ramgenix.scanner.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class FinancialInstrument {
    private String tradDt;
    private String bizDt;
    private String sgmt;
    private String src;
    private String finInstrmTp;
    private String finInstrmId;
    private String isin;
    private String tckrSymb;
    private String sctySrs;
    private String xpryDt;
    private String fininstrmActlXpryDt;
    private String strkPric;
    private String optnTp;
    private String finInstrmNm;
    private String opnPric;
    private String hghPric;
    private String lwPric;
    private String clsPric;
    private String lastPric;
    private String prvsClsgPric;
    private String undrlygPric;
    private String sttlmPric;
    private String opnIntrst;
    private String chngInOpnIntrst;
    private String ttlTradgVol;
    private String ttlTrfVal;
    private String ttlNbOfTxsExctd;
    private String ssnId;
    private String newBrdLotQty;
    private String rmks;
    private String rsvd1;
    private String rsvd2;
    private String rsvd3;
    private String rsvd4;
}
