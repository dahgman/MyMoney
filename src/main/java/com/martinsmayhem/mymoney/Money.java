package com.martinsmayhem.mymoney;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Money is always handled as a long number of cents, never as a double.
 * This class converts between cents and the text the user sees or types.
 */
public final class Money {

    private Money() {
        // static helper only
    }

    /** 125050 -> "$1,250.50", -50000 -> "-$500.00" */
    public static String format(long cents) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(BigDecimal.valueOf(cents, 2));
    }

    /**
     * Parses what a user types into cents. Accepts "1250.50", "$1,250.50",
     * "-500", "-$500.00" and "(500.00)" (parentheses mean negative).
     *
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public static long parse(String text) {
        String original = text == null ? "" : text.trim();
        String s = original;
        boolean negative = false;

        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace("$", "").replace(",", "").replace(" ", "");
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1);
        }
        if (!s.matches("\\d+(\\.\\d*)?|\\.\\d+")) {
            throw new IllegalArgumentException("\"" + original + "\" is not a valid amount.");
        }
        try {
            long cents = new BigDecimal(s).movePointRight(2).longValueExact();
            return negative ? -cents : cents;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amounts can have at most two decimal places.");
        }
    }
}
