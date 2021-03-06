package managers;

import interfaces.Manager_IF;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import marketdata.RTData_T;
import marketdata.Symbol_T;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;

import trader.Holding_T;
import trader.TraderCalculator_T;
import trader.Trader_T;
import util.Calendar_T;
import util.MovingAverage_T;
import util.Utilities_T;
import util.XMLTags_T;
import util.dtLogger_T;
import dayTrader.DayTrader_T;




/**
 * This manager class will keep track of the current market trading time as returned by our brokerage
 *  so we can trigger time based quote queries and buy/sell executions
 * 
 * @author nathan
 *
 */
public class TimeManager_T implements Manager_IF, Runnable {

	/* version as defined in the config file */
	private String VERSION = "Unknown";
	
	/* time zone offset from EST.  eg UTC = 5 hours */
	private int TZOffset = 0;

	/** Time in minutes before the close of the market day that we want to capture our snapshot of the market
     * and execute our buy orders.
     */
    private int MINUTES_BEFORE_CLOSE_TO_BUY = 20;			// TODO - not to buy, but to close todays transactions
    private int LATEST_BUY_TIME = 45;						// minutes before close
    
    /** The number of milliseconds in one minute. */
    private final int MS_IN_MINUTE = 1000 * 60;
    /** The number of minutes in one hour. */
    private final int MIN_IN_HOUR = 60;
    /** real time scan interval */
    private int RT_SCAN_INTERVAL = 5 * MS_IN_MINUTE;
    
    /** how long we should wait MAX after liquidation and buying */
    public static int MINUTES_TO_WAIT_FOR_EXECUTION = 5;	// TODO - move to Trader
    
    /** for testing algorithms */
    public static boolean g_buyToday = false;
    public static boolean g_buyOnTrend = true;		// mutually exclusive with above
    												// but we still buy today
    public static boolean g_useMarketPrice = false;	// for sells; false = use bid price
    public static boolean g_useRollingAvg = false;
    
    private static boolean d_backTest = false;		// mutually exclusive with useIB/useTD
    private static int     d_candidates = 1;		// which candidates algorithm
    
    /** A reference to the java Calendar class used to format and convert Date objects. */
    private static Calendar calendar;
    /** A reference to the Calendar_T class used to query the database to see if the market
     * is open or not. May be able to get rid of this if we can query IB or TDA for that information
     */
    private Calendar_T calendar_t;
    /** The last known time as returned by our broker. */
    private static Date time;
    /** scan interval keeper */
    private static Date prevScanTime;
    /** The time we want to execute our buys. buyTime = calendar_t.marketClose() - MINUTES_BEFORE_CLOSE_TO_BUY */
    private Date buyTime;
    /** latest time to execute buys */
    private Date latestBuyTime;
    /** A reference to the MarketDataManager used to retrieve market information. */
    private MarketDataManager_T marketDataManager;
    /** A reference to the BrokerManager. */
    private BrokerManager_T brokerManager;
    /** A reference to the DatabaseManager */
    private DatabaseManager_T databaseManager;
    
    /** our trader / calculator **/
    private TraderCalculator_T tCalculator;
    private Trader_T trader;
    
    private dtLogger_T Log;
    
    
    public TimeManager_T() {
       
        //default the time to the current system time
        time = new Date(System.currentTimeMillis());
        calendar = Calendar.getInstance();
        calendar.setTime(time);

        prevScanTime = new Date(System.currentTimeMillis() - 10000);  // before now

    }
    

    public void run() {

if (d_backTest) {
	Simulate();
	return;
}

        boolean running = true;

        Log.println("\n*** Day Trader "+VERSION+" has started at "+TimeNow()+" ***\n");

        if (!isMarketOpen()) {
        	Log.println("Market is not open.  Bye.");
        	return;
        }

        // we'll need this before we can use any brokerMgr/trader functions and as it could take time to init, do it here
        // and as it could take time to init, do it here
		if (!trader.init()) {
			Log.println("[FATAL ERROR] Cannot get account info or nextValidOrderId");
			System.err.println("Cannot Connect to IB.  Terminating");
			return;
		}

        /*
         * This loop will update the application time every three seconds and when a trigger time is reached
         * execute the appropriate actions. Triggers times can be the market open, a specified buy time,
         * and the market close.
         */

//TEST
//trader.TestCode();
//trader.liquidateHoldings();
//boolean b = trader.buyHoldings();
//trader.getOutstandingOrders();
//boolean b = trader.liquidateHoldings();            		
//tCalculator.calculateRealizedNet();
//tCalculator.dailyReport();
//tCalculator.reconciliationReport();
//tCalculator.ibPNLReport();
//databaseManager.updateSymbolAverages();
//List<Symbol_T> ls = databaseManager.determineBiggestLosers(); logCandidates(ls);
//running = false;
//TEST        

		// do any old holdings need attention?
		if (tCalculator.PreludeReport())
			Log.println("[ATTENTION] Review Prelude Report for Holdings needing attention\n");
		
        // whenever we start/restart, get open orders, and unrecorded execute orders
if (DayTrader_T.d_useIB) {
	
		//Log.println("Retrieving Outstanding orders...");
   		int nOutstandingOrders = trader.recoverMissedExecutions();  // from IB 
   		Log.println("There are "+nOutstandingOrders+" Outstanding Orders");
}

//SALxx
	if (g_buyToday) {
        // first thing, buy the holdings we identified yesterday
		// this has a failsafe to be sure we dont buy twice
        if (trader.buyHoldings() != 0) {
         
        	// wait a reasonable amount of time for buys to complete
        	// if we still have partially/unfilled buy orders,
        	// cancel the remaining and adjust buy volume/remaining
        	trader.waitForBuyCompletion();
        }
	}
//SALxx

        while (running) {

            try {            
                
                //updateTime is a blocking call that won't return until the time has been updated
            	updateTime();

            	// make sure we have a connection - cant do much without it
            	// we'll just wait a bit and try again
            	// this will also recover from the lost connection by updating our
            	// status from IB
            	// TODO: maybe a counter - this will print every time we go thru this loop
            	if (!trader.checkConnection()) {
            		Log.println("[ERROR] no connection at "+TimeNow());
            		continue;
            	}
 

            	/*
            	 * During market open hours, get RealTime Quotes for our Holdings
            	 * according to the scan interval
            	 * 
            	 * determine if we should sell now
            	 */
// debug only            	
if (DayTrader_T.d_getRTData) {
		
				// run every n minutes as define by RT_SCAN_INTERVAL
				// sell a holding if it meets the criteria.  The Order is executed,
				// but it may fill asynchronously.  At the end of the day, we'll
				// check that is has completely sold
            	if (time.after(prevScanTime))
            	{
            		
            		List <RTData_T> rtData = marketDataManager.getRealTimeQuotes();

                    // add the prices to the rolling average
                    trader.rollingAverage(rtData);
                    
if (g_buyOnTrend) {
					// determine if its time to buy... maybe we need to put a limit
					// on this, like before noon.  We still need to cancel any orders
					// that were placed, but werent filled.
	
    				// but not too late in the day
					if (time.before(latestBuyTime)) {
						trader.shouldWeBuy(rtData);
					}
}

            		trader.shouldWeSell(rtData);
					tCalculator.calculateNet();
            		
            		prevScanTime.setTime(prevScanTime.getTime() + RT_SCAN_INTERVAL);
            	}
}
                /*
                 * At the end of the market day, perform the following 
                 * 1. get a market snapshot
                 * 2. sell any outstanding positions we're still holding 
                 * 3. identify the positions we want to buy
                 * 4. execute buy orders 
                 */
                if (time.after(buyTime)) {

                	// We need to conclude todays business by selling any remaining stocks
                	// Then calculate our net position for today
                	
                    // Execute any remaining sell orders and then wait until they sell
                	// Do not include any that are in progress from selling during the day

                    //we need to wait until they sell so we have money to buy new stocks
                    //but is this really feasible? Will the money be credited to our account immediately
                    //if the money isn't instantly credited what do we do? how do we buy additional positions?
                    //what is we can't sell a position? how are we going to handle that?

					// first, get the most recent RT data (not necessary - we just got it)
					//marketDataManager.getRealTimeQuotes();

if (g_buyOnTrend) {
					// If there were any outstanding buys that didnt fill
					trader.cancelBuyOrders();
}


					trader.liquidateHoldings();


					// get todays closing market info from TD and store in EndOfDayQuotes
					// this will give us time for the liquidated holdings to be sold
					// (I hope its enough time...)
if ( DayTrader_T.d_takeSnapshot) {

					if (marketDataManager.takeMarketSnapshot()==0) {
						Log.println("[FATAL ERROR] No EOD Data!");
						
						// TODO throw global exception
					}
}

					// hang around a while so we can check if the orders
					// have been filled. If we still have partially/unfilled sell orders,
					// we'll deal with them tomorrow, or manually
					// (they'll be in the daily reports and email)
					trader.waitForLiquidateCompletion();


					// for what we just liquidated
					tCalculator.calculateNet();
					
                    // now we can calculate total net (how much we gained/lost)
					// for the end of the day
					// also calculate the estimate of unsold holdings, and
					// the actual net of any deferred holdings that were realized today
            		tCalculator.calculateRealizedNet();
            		tCalculator.calculateUnrealizedNet();
            		tCalculator.calculateDeferredNet();

            		
                    // Now we can determine todays holdings candidates
            		Log.println("** Determing todays candidates ***");
            		
            		// test algorithms 1_ losers, 2) winners, 3) most active...
            		List<Symbol_T> candidates = new ArrayList<Symbol_T>();
            		switch (d_candidates) {
            		  case 1:
            			candidates = databaseManager.determineBiggestLosers();
            		    break;
            		  case 2:
            			candidates = databaseManager.determineBiggestWinners();
            			break;
            		  case 3:
                        candidates = databaseManager.determineMostActive();
                        break;
            		}

                    // save in log file
                    logCandidates(candidates);

                    // Finally, update Holdings Table w/tomorrows candidates
                    // positions are calculated here based on EOD prices
                    databaseManager.addHoldings(candidates);
                
//SALxx
if (!g_buyToday && !g_buyOnTrend) {

                    // Now buy them
                    trader.buyHoldings();
                     
					// hang around a while so we can check if the orders
					// have been filled. If we wait around long enough, the unfilled
                    // ones should fill but if we're too close to the end of the day,
                    // and we still have partially/unfilled buy orders,
					// cancel the remaining and adjust buy volume/remaining
                    trader.waitForBuyCompletion();
} //SALxx


					// reports and emails
					Log.println("Creating reports and emails...");
                    ArrayList<String> attachments = new ArrayList<String>();
    
                    String report = tCalculator.dailyReport();
                    if (report != null) { attachments.add(report); }
                    report = tCalculator.reconciliationReport();
                    if (report != null) { attachments.add(report); }
                    
                    tCalculator.ibPNLReport();
                    
                    EmailManager_T emailMgr = (EmailManager_T) DayTrader_T.getManager(EmailManager_T.class);
                    emailMgr.sendEmail("Daily Reports", "Attached are the DayTrader daily reports.", attachments);


                    //set buy_time to tomorrow so we don't execute this block again
                    //TODO: Check that this works
                    buyTime.setTime(buyTime.getTime() + (MS_IN_MINUTE * MIN_IN_HOUR * 24));
                    
                    //re-calculate the 15 day moving average. This can take a while
                    System.out.println("Calculating moving averages.  Please wait.");
                    databaseManager.updateSymbolAverages();

                    
                    //TODO: For now terminate the application at the end of each day
                    Log.println("\n*** dayTrader is exiting at "+TimeNow()+"  Bye ***");
                    throw new InterruptedException("Terminating TimeManager thread because the market is now closed");
                
                }  // end EOD
                
            } catch (InterruptedException e) {
                running = false;
            }

        }  // while running

    }

    @Override
    public void initialize() {
        
        marketDataManager = (MarketDataManager_T) DayTrader_T.getManager(MarketDataManager_T.class);
        brokerManager = (BrokerManager_T) DayTrader_T.getManager(BrokerManager_T.class);
        databaseManager = (DatabaseManager_T) DayTrader_T.getManager(DatabaseManager_T.class);
        
        tCalculator = new TraderCalculator_T(); 
        trader      = new Trader_T();
        
        Log = DayTrader_T.dtLog;
        
        ConfigurationManager_T cfgMgr = (ConfigurationManager_T) DayTrader_T.getManager(ConfigurationManager_T.class);
        MINUTES_BEFORE_CLOSE_TO_BUY = Integer.parseInt(cfgMgr.getConfigParam(XMLTags_T.CFG_MINUTES_BEFORE_CLOSE_TO_BUY));
        LATEST_BUY_TIME = Integer.parseInt(cfgMgr.getConfigParam(XMLTags_T.CFG_LATEST_BUY_TIME));
        MINUTES_TO_WAIT_FOR_EXECUTION = Integer.parseInt(cfgMgr.getConfigParam(XMLTags_T.CFG_MINUTES_TO_WAIT_FOR_EXECUTION));
        RT_SCAN_INTERVAL = Integer.parseInt(cfgMgr.getConfigParam(XMLTags_T.CFG_RT_SCAN_INTERVAL_MINUTES)) * MS_IN_MINUTE;
        TZOffset = Integer.parseInt(cfgMgr.getConfigParam(XMLTags_T.CFG_TZOFFSET));

        VERSION = cfgMgr.getConfigParam(XMLTags_T.CFG_VERSION);

        // these are for algorithm testing
        g_buyToday = Boolean.parseBoolean(cfgMgr.getConfigParam(XMLTags_T.CFG_tBuyToday));
        g_buyOnTrend = Boolean.parseBoolean(cfgMgr.getConfigParam(XMLTags_T.CFG_tBuyOnTrend));
        g_useMarketPrice = Boolean.parseBoolean(cfgMgr.getConfigParam(XMLTags_T.CFG_tUseMarketPrice));
        g_useRollingAvg = Boolean.parseBoolean(cfgMgr.getConfigParam(XMLTags_T.CFG_tUseRollingAvg));
        d_backTest = Boolean.parseBoolean(cfgMgr.getConfigParam(XMLTags_T.CFG_tBackTest));
        String cand = cfgMgr.getConfigParam(XMLTags_T.CFG_tCandidates);
        if (cand.equalsIgnoreCase("LOSERS")) d_candidates = 1;
        else if (cand.equalsIgnoreCase("WINNERS")) d_candidates = 2;
        else if (cand.equalsIgnoreCase("VOLUME")) d_candidates = 3;
        else if (cand.equalsIgnoreCase("SMA")) d_candidates = 4;
        // end testing params
    
        
        calendar_t = (Calendar_T) databaseManager.query(Calendar_T.class, time);
        
        updateTime();

        this.buyTime = new Date(calendar_t.getCloseTime().getTime() - MINUTES_BEFORE_CLOSE_TO_BUY * MS_IN_MINUTE);
        this.latestBuyTime = new Date(calendar_t.getCloseTime().getTime() - LATEST_BUY_TIME * MS_IN_MINUTE);
        
        // compensate for timezone, if necessary
        if (TZOffset != 0) {
            Calendar c = Calendar.getInstance();
            c.setTime(this.buyTime);
            c.add(Calendar.HOUR, TZOffset);
            this.buyTime = c.getTime();
            
            c.setTime(this.latestBuyTime);
            c.add(Calendar.HOUR, TZOffset);
            this.latestBuyTime = c.getTime();
        }
        
        // for simulation, buy date is always before now
        if (!DayTrader_T.d_useSimulateDate.isEmpty()) { this.buyTime.setTime(0); }
        
    }

    @Override
    public void sleep() {
        // TODO Auto-generated method stub
    }

    @Override
    public void terminate() {
        // TODO Auto-generated method stub

    }

    @Override
    public void wakeup() {
        // TODO Auto-generated method stub

    }
    
    /**
     * Return true if the market is open for trading, otherwise return false
     * 
     * @return true if the market is open for trading, else false
     */
    public boolean isMarketOpen() {

        //TODO: Can we use TDA or IB to determine if the market is open or not?
    	 
    	if (DayTrader_T.d_ignoreMarketClosed)
    		return true;
        
        boolean isOpen = false;
        
        calendar.setTime(time);
        
        Calendar_T open = (Calendar_T) databaseManager.query(Calendar_T.class, time);
        
        Date closeTime = open.getCloseTime();

        // compensate for timezone, if necessary
        if (TZOffset != 0) {
            Calendar c = Calendar.getInstance();
            c.setTime(closeTime);
            c.add(Calendar.HOUR, TZOffset);
            closeTime = c.getTime();
        }

        if (open.isMarketOpen() && time.compareTo(closeTime) <= 0) {
            isOpen = true;
        }
        
        return isOpen;
        
    }

    public String mysqlDate() {
        
        calendar.setTime(time);
        String date = calendar.get(Calendar.YEAR) + "-" 
                + (calendar.get(Calendar.MONTH) + 1) + "-" 
                + calendar.get(Calendar.DAY_OF_MONTH);        
        
        return date;
    }

    /**
     * 
     * @return the current time
     */
    public Date TimeNow() {
        return time;
    }
    
    /**
     * get todays date with 00:00:00
     * @return Date
     */
    public Date Today()
    {
        Calendar c = Calendar.getInstance();
        c.setTime(time);
        
        if (!DayTrader_T.d_useSimulateDate.isEmpty())
        {
        	 SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        	  try { 
        	    Date d = df.parse(DayTrader_T.d_useSimulateDate);
        	    c.setTime(d);
        	  } catch (ParseException e1)  { /*do nothing*/ System.out.println("sim time parse error");}
        	    
        }
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        Date today = c.getTime(); //  midnight, that's the first second of the day
        
        return today;
    }

    /**
     * Update the time by retrieving the last known time from the broker.
     * 
     */
    private void updateTime() {
        long oldTime = time.getTime();
        
        //TODO: update this method so that if IB doesn't return an updated time for whatever reason
        //use the system time
        
//SAL
if (!DayTrader_T.d_useSystemTime) {
        //the broker manager will invoke the setTime() method when the current time has been returned so
        //loop until we get an updated time.
		//we can get stuck in an infinite loop here if we never connect to IB
        while(oldTime == time.getTime()) {

            brokerManager.reqCurrentTime();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
}
else  //SALxx - get system time
{
	// block for a bit
    try { Thread.sleep(2000);
    } catch (InterruptedException e) { e.printStackTrace(); }
    
	long now = System.currentTimeMillis();
	setTime(now);
	//Date d = new Date(now);
	//System.out.println("updateTime(): " + now + " " + d.toString());
}

        return;
    }
    
    /**
     * Get the year represented as YYYY
     * 
     * @return YYYY
     */
    public static int getYear4Digit() {
        calendar.setTime(time);
        return calendar.get(Calendar.YEAR);
    }
    
    /**
     * Get the month represented as 01-12
     * 
     * @return MM
     */
    public static int getMonthDigit() {
        calendar.setTime(time);
        return calendar.get(Calendar.MONTH) + 1;
    }
    
    /**
     * get the time - not this also does an update time (get from server) which blocks
     *  if you dont want to block, use TimeNow
     * @return the time   
     */
    public Date getTime() {
    	updateTime();
        return time;
    }


    /**
     * @param time the time to set
     */
    public void setTime(long time) {
        TimeManager_T.time.setTime(time);
    }

    /**
     * return date of the most current open market (accounts for weekends and holidays)
     * 
     * @return Date    Date(0) on error
     */
    public Date getCurrentTradeDate()
    {
    	if (!DayTrader_T.d_useSimulateDate.isEmpty())
    		return Utilities_T.StringToDate(DayTrader_T.d_useSimulateDate);

    	
    	//SELECT date, market_is_open FROM calendar WHERE date BETWEEN DATE_ADD(\"$date\", INTERVAL -7 DAY) AND \"$date\" ORDER BY date DESC
        Session session = DatabaseManager_T.getSessionFactory().openSession();
        
        Calendar c = Calendar.getInstance();
        c.setTime(Today());
        c.add(Calendar.DATE, -7);
        Date lastWeek = c.getTime();

        Criteria criteria = session.createCriteria(Calendar_T.class)
            .add(Restrictions.between("date", lastWeek, Today()))        		
            .addOrder(Order.desc("date"));

        @SuppressWarnings("unchecked")
        List<Calendar_T> dates = criteria.list();
        
        session.close();
        
        Iterator<Calendar_T> it = dates.iterator();
        while (it.hasNext()) {
            Calendar_T date = it.next();
            if (date.isMarketOpen()) return date.getDate();
        }
      
        Date d = new Date(0);
        return d;		// error
    }

    /**
     * Previous Trade Date (previous date market was open)
	 * return date of the previous open market date (accounts for weekends and holidays)
	 * input is the 'current' date; return is the date previous
	 * default input is today
     * 
     * @return Date  Date(0) on error
     */
    public Date getPreviousTradeDate()
    {
//SALxx
    	if (g_buyToday || g_buyOnTrend) return getCurrentTradeDate();
//SALxx
    	
        
        Calendar c = Calendar.getInstance();
        c.setTime(getCurrentTradeDate());
        c.add(Calendar.DATE, -1);
        Date yesterday = c.getTime();
        
        c.add(Calendar.DATE, -7);
        Date lastWeek = c.getTime();      

    	//SELECT date, market_is_open FROM calendar WHERE date BETWEEN DATE_ADD(\"$date\", INTERVAL -8 DAY) AND DATE_ADD(\"$date\", INTERVAL -1 DAY) ORDER BY date DESC
        Session session = DatabaseManager_T.getSessionFactory().openSession();

        Criteria criteria = session.createCriteria(Calendar_T.class)
            .add(Restrictions.between("date", lastWeek, yesterday))        		
            .addOrder(Order.desc("date"));

        @SuppressWarnings("unchecked")
        List<Calendar_T> dates = criteria.list();
        
        session.close();
        
        Iterator<Calendar_T> it = dates.iterator();
        while (it.hasNext()) {
            Calendar_T date = it.next();
            if (date.isMarketOpen()) return date.getDate();
        }
      
        Date d = new Date(0);
        return d;		// error
    }

    
    /**
     * return date of the next current open market (accounts for weekends and holidays)
     * 
     * @return Date    Date(0) on error
     */
    public Date getNextTradeDate()
    {
   	
    	//SELECT date, market_is_open FROM calendar WHERE date BETWEEN DATE_ADD(\"$date\", INTERVAL 1 DAY) AND DATE_ADD(\"$date\", INTERVAL 8 DAY) AND market_is_open = 1 ORDER BY date LIMIT 1
        Session session = DatabaseManager_T.getSessionFactory().openSession();
        
        Calendar c = Calendar.getInstance();
        c.setTime(getCurrentTradeDate());
        c.add(Calendar.DATE, 1);
        Date nextDay = c.getTime();
        
        c.add(Calendar.DATE, 7);
        Date nextWeek = c.getTime();       

        Criteria criteria = session.createCriteria(Calendar_T.class)
            .add(Restrictions.between("date", nextDay, nextWeek))
            .add(Restrictions.eq("marketOpen", true))
            .addOrder(Order.asc("date"))
            .setMaxResults(1);

        @SuppressWarnings("unchecked")
        List<Calendar_T> dates = criteria.list();

        session.close();
        
        if (!dates.isEmpty())
        	return dates.get(0).getDate();
        
      
        Date d = new Date(0);
        return d;		// error
    }

    /**
     * Log Helper
     */
    private void logCandidates(List<Symbol_T> losers) {
    	Iterator<Symbol_T> it = losers.iterator();
    	Log.print("Todays candidates are: ");
    	while (it.hasNext()) {
    		Symbol_T symbol = it.next();
    		Log.print(symbol.getSymbol()+ " ");
    	}
    	Log.newline();
    }



/************************************************************************/
/*
 * for backtesting, you must have Holdings and RT data for the simulation date
 * and set the config file params accordingly.
 * 
 * NOTE: the actual RT data will have data for every scan interval, whereas
 * duplicate data is not saved in the DB. EG, RTdata could be flat whereas
 * simulated data will show a trend more readily
 * 
 * To prepare the database,
 * CREATE DATABASE test_database;
 * mysqldump -u root -psal dayTrader_beta | mysql -u root -psal test_database
 * 
 * update Holdings set sell_date = null, sell_price=0, net=null, order_id=0, order_id2=0, actual_buy_price = 0, filled = 0, remaining = volume, avg_fill_price = 0, order_status="PreSubmitted";
 * make sure there is a non-zero volume
 *
 * NOTE: for buytoday/buyonspread/buyontrend, buy_price is filled on AddHoldings with the price
 * at the time the holdings were added (when candidates are calculated the day before).  This is only used
 * to get the approx number of shares to buy.  It will be updated again when we have a desired price to
 * sell (in shouldWebuy).  The actual_buy_price (and time) is filled in when the order is placed (or simulated)
 * 
 */
/************************************************************************/
    private void Simulate()
    {    	
    	time = Utilities_T.StringToDate(DayTrader_T.d_useSimulateDate);
        calendar_t = (Calendar_T) databaseManager.query(Calendar_T.class, time);

        latestBuyTime = new Date(calendar_t.getCloseTime().getTime() - LATEST_BUY_TIME * MS_IN_MINUTE);
        

        Log.println("\n*** Simulated Day Trader "+VERSION+" has started for "+TimeNow()+" ***\n");


        // get the list of symbols for todays Holdings
        List<Symbol_T> symbols = databaseManager.getHoldingsSymbols();
        
        // for each symbol
        Iterator<Symbol_T> it = symbols.iterator();
        while (it.hasNext()) {
            Symbol_T symbol = it.next();
        		
            List<RTData_T> simulatedRTData = getSimulatedRTData(symbol.getSymbol());
            
            // for each RT scan
            
            // initialize these (as a list of one)
            RTData_T prevRTData = new RTData_T();
            prevRTData.setPrice(0.0); prevRTData.setDate(time);
            ArrayList<RTData_T> prevData = new ArrayList<RTData_T>();
            prevData.add(prevRTData);
            
            Iterator<RTData_T> itd = simulatedRTData.iterator();
            while (itd.hasNext()) {
                RTData_T d = itd.next();    
       			
                // we need a list of one...
                ArrayList<RTData_T> rtData = new ArrayList<RTData_T>();
                rtData.add(d);
                
                // add the prices to the rolling average - use last
                // if nothings changed (as we get rt data every scan interval
                long timeDiff = d.getDate().getTime() - prevData.get(0).getDate().getTime();
                int  n = (int)(timeDiff/RT_SCAN_INTERVAL)-1;
                if (prevData.get(0).getPrice().floatValue() != 0.0 && n > 0) {
                	for (int i=0; i<n; i++) { trader.rollingAverage(prevData); }
                }
                // save for next time
                prevData.get(0).setPrice(d.getPrice()); prevData.get(0).setDate(d.getDate());
                
                // add this one
                trader.rollingAverage(rtData);
               
				// determine if its time to buy... maybe we need to put a limit
				// on this, like before noon.  We still need to cancel any orders
				// that were placed, but werent filled.
               
                // but not too late in the day
				if (d.getDate().before(latestBuyTime)) {
					trader.shouldWeBuy(rtData);
				}

            	trader.shouldWeSell(rtData);

            } // next scan
            
			tCalculator.calculateNet();
			
        } // next symbol
            	
        trader.liquidateHoldings();

		// for what we just liquidated
		tCalculator.calculateNet();
					
        // now we can calculate total net (how much we gained/lost)
		// for the end of the day
		tCalculator.calculateRealizedNet();

		tCalculator.dailyReport();

    }

    
    /*  select * from RealTimeQuotes where DATE(date) = "date" and symbol = "symbol" order by date;
    */
    public List <RTData_T>  getSimulatedRTData(String symbol)
	{
    	Date date = getCurrentTradeDate();  	
    	

    	Session session = databaseManager.getSessionFactory().openSession();
          
    	Criteria criteria = session.createCriteria(RTData_T.class)
        .add(Restrictions.between("date", date, Utilities_T.tomorrow(date) ))
        .add(Restrictions.eq("symbol", symbol));         
          
         @SuppressWarnings("unchecked")
         List<RTData_T> rtData = criteria.list();
          
         session.close();
    	
    	 return rtData;
	}


/**************************************************************************/
    
}