/**
 * 
 */
package util;

import java.util.regex.Pattern;

/**
 * @author nathan
 *
 */
public class Utilities_T {

    /**
     * Convert a String value to a Double. This method was taken diretly from the JavaDocs for the 
     * Double class. To ensure a call to Double.valueOf(string) does not throw an exception, the string
     * value should parse through these filters first.
     * 
     * @param s - string representing a double value
     * @return double
     */
    public static Double stringToDouble(String s) {
        
        Double returnVal = 0d;
        
        final String Digits     = "(\\p{Digit}+)";
        final String HexDigits  = "(\\p{XDigit}+)";
        // an exponent is 'e' or 'E' followed by an optionally 
        // signed decimal integer.
        final String Exp        = "[eE][+-]?"+Digits;
        final String fpRegex    =
                ("[\\x00-\\x20]*[+-]?(NaN|Infinity|((("+Digits+"(\\.)?("+Digits+"?)("+Exp+")?)|(\\.("+Digits+")("+Exp+")?)|(((0[xX]"
                    + HexDigits + "(\\.)?)|(0[xX]" + HexDigits + "?(\\.)" + HexDigits + "))[pP][+-]?" + Digits + "))[fFdD]?))[\\x00-\\x20]*");

        if (s.equalsIgnoreCase("")) {
            returnVal = 0d;
        }
        else if (Pattern.matches(fpRegex, s)) {
            returnVal = Double.valueOf(s);
        }
        
        return returnVal;
    }


}