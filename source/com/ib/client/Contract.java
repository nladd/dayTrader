package com.ib.client;

import java.util.Vector;

public class Contract implements Cloneable {
  /* {src_lang=Java}*/


  public int m_conId;

  public String m_symbol;

  public String m_secType;

  public String m_expiry;

  public double m_strike;

  public String m_right;

  public String m_multiplier;

  public String m_exchange;

  public String m_currency;

  public String m_localSymbol;

  public String m_primaryExch;

  public boolean m_includeExpired;

  public String m_secIdType;

  public String m_secId;

  public String m_comboLegsDescrip;

  public Vector m_comboLegs = new Vector();

  public UnderComp m_underComp;

  public Contract() {
    	m_conId = 0;
        m_strike = 0;
        m_includeExpired = false;
  }

  public Object clone() {
        Contract retval = (Contract)super.clone();
        retval.m_comboLegs = (Vector)retval.m_comboLegs.clone();
        return retval;
  }

  public Contract(int p_conId, String p_symbol, String p_secType, String p_expiry, double p_strike, String p_right, String p_multiplier, String p_exchange, String p_currency, String p_localSymbol, Vector p_comboLegs, String p_primaryExch, boolean p_includeExpired, String p_secIdType, String p_secId) {
    	m_conId = p_conId;
        m_symbol = p_symbol;
        m_secType = p_secType;
        m_expiry = p_expiry;
        m_strike = p_strike;
        m_right = p_right;
        m_multiplier = p_multiplier;
        m_exchange = p_exchange;
        m_currency = p_currency;
        m_includeExpired = p_includeExpired;
        m_localSymbol = p_localSymbol;
        m_comboLegs = p_comboLegs;
        m_primaryExch = p_primaryExch;
        m_secIdType = p_secIdType;
        m_secId = p_secId ;
  }

  public boolean equals(Object p_other) {
    	if (this == p_other) {
    		return true;
    	}
    	if (p_other == null || !(p_other instanceof Contract)) {
    		return false;
    	}
        Contract l_theOther = (Contract)p_other;
        
        if (m_conId != l_theOther.m_conId) {
        	return false;
        }
        if (Util.StringCompare(m_secType, l_theOther.m_secType) != 0) {
        	return false;
        }
        if (Util.StringCompare(m_symbol, l_theOther.m_symbol) != 0 ||
        	Util.StringCompare(m_exchange, l_theOther.m_exchange) != 0 ||
        	Util.StringCompare(m_primaryExch, l_theOther.m_primaryExch) != 0 ||
        	Util.StringCompare(m_currency, l_theOther.m_currency) != 0) {
        	return false;
        }
        if (!Util.NormalizeString(m_secType).equals("BOND")) {
        	if (m_strike != l_theOther.m_strike) {
        		return false;
        	}
        	if (Util.StringCompare(m_expiry, l_theOther.m_expiry) != 0 ||
        		Util.StringCompare(m_right, l_theOther.m_right) != 0 ||
        		Util.StringCompare(m_multiplier, l_theOther.m_multiplier) != 0 ||
        		Util.StringCompare(m_localSymbol, l_theOther.m_localSymbol) != 0) {
        		return false;
        	}
        }
        if (Util.StringCompare(m_secIdType, l_theOther.m_secIdType) != 0) {
        	return false;
        }
        
        if (Util.StringCompare(m_secId, l_theOther.m_secId) != 0) {
        	return false;
        }
    	// compare combo legs
        if (!Util.VectorEqualsUnordered(m_comboLegs, l_theOther.m_comboLegs)) {
        	return false;
        }
        
        if (m_underComp != l_theOther.m_underComp) {
        	if (m_underComp == null || l_theOther.m_underComp == null) {
        		return false;
        	}
        	if (!m_underComp.equals(l_theOther.m_underComp)) {
        		return false;
        	}
        }
        return true;
  }

  public void newOperation() {
  }

}