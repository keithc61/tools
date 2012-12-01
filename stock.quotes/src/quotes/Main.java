package quotes;

import quotes.yahoo.HistoryService;

public final class Main {

	private static final java.lang.String[] symbols = { "EGHT", "AMD", "ALU", "AAPL", "APM.V", "RPT.V", "BNS.TO",
			"BCE.TO", "BRK-B", "BRCM", "CWL.TO", "CM.TO", "CWA.V", "CUX.TO", "FTS.TO", "GNV.TO", "GRC.TO", "HSE.TO",
			"IBM", "JML.V", "JDSU", "JDU.TO", "MAS.V", "MSFT", "NA.TO", "NA-PO.TO", "NBD.TO", "NRTLQ.PK", "ORCL",
			"PCE.TO", "PPL.TO", "PWT.TO", "PG.TO", "POW.TO", "RMK.TO", "SXG.V", "SGF.TO", "SLF.TO", "SU.TO", "SNCR",
			"TTWO", "T.TO", "TA.TO", "TRP.TO", "WVNTF.PK", "WPK.TO", "^DJI", "^IXIC", "^GSPC", "^GSPTSE", "XIC.TO",
			"XIU.TO" };

	public static void main(String[] args) {
		Date date;

		if (args.length > 0) {
			date = new Date(args[0]);
		} else {
			date = Date.yesterday();
		}

		System.out.format("Quotes for %s\n", date);
		System.out.println();
		System.out.println("Symbol        Price         Volume");
		System.out.println("------        -----         ------");

		for (String symbol : symbols) {
			printQuote(symbol, date);
		}
	}

	private static void printQuote(String symbol, Date date) {
		Quote quote = HistoryService.getQuote(symbol, date);

		if (quote == null) {
			System.out.format("%-8s %10s\n", // <br/>
					symbol, "-.--");
		} else {
			System.out.format("%-8s %,10.2f %,14d\n", // <br/>
					symbol, Double.valueOf(quote.getPrice()), Long.valueOf(quote.getVolume()));

		}
	}

	private Main() {
		super();
	}
}