package trader;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import managers.BrokerManager_T;
import marketdata.MarketData_T;
import marketdata.Symbol_T;

import com.ib.controller.OrderType;
import com.ib.controller.Types.Action;
import com.ib.controller.Types.SecType;

import dayTrader.DayTrader_T;

public class Trader_T {
  /* {src_lang=Java}*/

    /** Minimum number of shares a security has to trade for us to buy. */
    public static final double MIN_TRADE_VOLUME = 10000;  //SALxx was int - needs to agree w/ DB definition
    /** Minimum price for a security for us to buy it. */
    public static final double MIN_BUY_PRICE = 0.50;
    /** The maximum number of positions we want to buy. */
    public static final int MAX_BUY_POSITIONS = 25;
    /** The minimum account balance we want to have. */
    public static final int MIN_ACCOUNT_BALANCE = 25000;
    
    /** A reference to the BrokerManager_T class. */
    private BrokerManager_T brokerManager = null;
    

    /**
     * 
     */
    public Trader_T() {
        
        brokerManager = (BrokerManager_T) DayTrader_T.getManager(BrokerManager_T.class);
    }

    public void buy() {
    }
    
    /**
     * Create a sell order for all the holdings contained within the List parameter. Returns a list of Holdings_T whose
     * contract and order fields will contain the data necessary to place the sell order
     * 
     * @param holdings
     * @return sell orders
     */
    public List<Holding_T> createSellOrders(ArrayList<Holding_T> orders) {
        
        
        //were going to return the same holdings, but
        //with the contract and order fields populated for a sell order
        //ArrayList<Holding_T> orders = new ArrayList<Holding_T>(holdings);
        
        Iterator<Holding_T> it = orders.iterator();
        while(it.hasNext()) {
            Holding_T order = it.next();
            
            //get the next available orderID
            int orderId = brokerManager.reqNextValidId();
            order.setOrderId(orderId);
            
            order.getOrder().m_orderId = order.getOrderId();
            //clientId fields should already be populated
            
            order.getOrder().m_action = Action.SELL.toString();
            order.getOrder().m_orderType = "MKT";
            order.getOrder().m_totalQuantity = order.getRemaining();
            order.getOrder().m_transmit = true;
            
            order.getOrder().m_lmtPrice = 0;
            order.getOrder().m_auxPrice = 0;
            order.getOrder().m_allOrNone = false;
            order.getOrder().m_blockOrder = false;
            order.getOrder().m_outsideRth = false;
           
            //the contractId field will already be populated
            order.getContract().m_currency = "USD";
            order.getContract().m_exchange = order.getSymbol().getExchange();
            order.getContract().m_primaryExch = order.getSymbol().getExchange();
            //order.getContract().m_expiry = String.valueOf(TimeManager_T.getYear4Digit() + TimeManager_T.getMonthDigit());
            order.getContract().m_localSymbol = order.getSymbol().getSymbol();
            order.getContract().m_symbol = order.getSymbol().getSymbol();
            //order.getContract().m_right = "PUT";
            order.getContract().m_secType = SecType.STK.toString();
            
            
        }
              
        
        return orders;
    }
    
    /**
     * Create market buy orders for the provided list of symbols
     * 
     * @param symbols - list of symbols to buy
     */
    public List<Holding_T> createMktBuyOrders(List<Symbol_T> symbols, int clientId) {
        
        List<Holding_T> buyOrders = new ArrayList<Holding_T>();
        
        //calculate the amount in dollars that we're allowed to buy
        double buyAmount = (brokerManager.getAccount().getBalance() - MIN_ACCOUNT_BALANCE) / MAX_BUY_POSITIONS;
        
        Iterator<Symbol_T> it = symbols.iterator();
        while (it.hasNext()) {
            Symbol_T symbol = it.next();
            
            Holding_T order = new Holding_T();
            
            //get the next available orderID
            int orderId = brokerManager.reqNextValidId();
            order.setOrderId(orderId);
            
            order.getOrder().m_orderId = order.getOrderId();
            order.setClientId(clientId);
            
            order.getOrder().m_action = Action.BUY.toString();
            //submit these orders as market-to-limit orders
            order.getOrder().m_orderType = OrderType.MTL.toString();
            //get the latest price of this symbol
            MarketData_T snapshot = brokerManager.reqSymbolSnapshot(symbol);
            //TODO: how do I know when the price is updated?
            
            order.getOrder().m_totalQuantity = (int) Math.floor(buyAmount / snapshot.getAskPrice());
            order.getOrder().m_transmit = true;
            
            order.getOrder().m_lmtPrice = 0;
            order.getOrder().m_auxPrice = 0;
            order.getOrder().m_allOrNone = false;
            order.getOrder().m_blockOrder = false;
            order.getOrder().m_outsideRth = false;
           
            //the contractId field will already be populated
            order.getContract().m_currency = "USD";
            order.getContract().m_exchange = symbol.getExchange();
            order.getContract().m_primaryExch = symbol.getExchange();
            //order.getContract().m_expiry = String.valueOf(TimeManager_T.getYear4Digit() + TimeManager_T.getMonthDigit());
            order.getContract().m_localSymbol = symbol.getSymbol();
            order.getContract().m_symbol = symbol.getSymbol();
            //order.getContract().m_right = "PUT";
            order.getContract().m_secType = SecType.STK.toString();
            
            
        }
        
        
        
        return buyOrders;
    }

}