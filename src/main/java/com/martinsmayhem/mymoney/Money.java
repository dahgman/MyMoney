package com.martinsmayhem.mymoney;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * The "automatically insert the decimal point" preference: when on, an
     * amount typed without a decimal point is read as cents, so 12345 is
     * $123.45, 12 is $0.12 and 1 is $0.01. Set from MainFrame.
     */
    private static volatile boolean autoDecimal;

    public static void setAutoDecimal(boolean on) {
        autoDecimal = on;
    }

    /**
     * Parses an amount the user typed: like parse(), but with the automatic
     * decimal point preference on, digits with no decimal point are cents
     * (12345 -> $123.45, 123 -> $1.23, 12 -> $0.12, 1 -> $0.01). A decimal
     * point typed by hand always counts, and one with nothing after it gets
     * zeroes (12. -> $12.00). Amounts read from
     * files use parse(), never this.
     *
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public static long parseTyped(String text) {
        if (isCalculation(text)) {
            return calculate(text);
        }
        long cents = parse(text);
        if (autoDecimal && text != null && !text.contains(".")) {
            return cents / 100;                      // whole "dollars" were really cents
        }
        return cents;
    }

    // ----- Calculations --------------------------------------------------------

    /**
     * True if typed text is a sum like "12.50+3.25" or "1299*3" rather than a
     * single amount: it has +, -, * or / after the start (a leading minus is
     * just a negative amount).
     */
    public static boolean isCalculation(String text) {
        if (text == null) {
            return false;
        }
        String s = text.replace("$", "").replace(",", "").replace(" ", "");
        if (s.endsWith("=")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.length() > 1 && s.substring(1).matches(".*[-+*/xX].*") && s.matches("[-+*/xX0-9.]+");
    }

    /**
     * Works out a typed calculation, in cents (rounded to the cent).
     * + and - join amounts (each read like a typed amount, so the automatic
     * decimal point applies); * and / multiply or divide by plain numbers
     * (1299*3 is $12.99 x 3 with the automatic decimal point on, 12.99*3
     * either way). * and / come before + and -. "x" works for *, and a
     * trailing = is ignored.
     *
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public static long calculate(String text) {
        String s = text.replace("$", "").replace(",", "").replace(" ", "").replace('x', '*').replace('X', '*');
        if (s.endsWith("=")) {
            s = s.substring(0, s.length() - 1);
        }
        // Tokens: numbers (with a sign if one starts the sum or follows an operator) and operators.
        java.util.List<String> tokens = new java.util.ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            boolean signed = (c == '-' || c == '+') && (tokens.isEmpty() || isOperator(tokens.get(tokens.size() - 1)));
            if (Character.isDigit(c) || c == '.' || signed) {
                int j = i + 1;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                tokens.add(s.substring(i, j));
                i = j;
            } else if (c == '+' || c == '-' || c == '*' || c == '/') {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                throw new IllegalArgumentException("\"" + text + "\" is not a calculation Money can work out.");
            }
        }
        if (tokens.isEmpty() || isOperator(tokens.get(tokens.size() - 1)) || isOperator(tokens.get(0))) {
            throw new IllegalArgumentException("\"" + text + "\" is not finished: it ends (or starts) with + - * or /.");
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal term = null;
        String join = "+";
        for (int t = 0; t < tokens.size(); t++) {
            String tok = tokens.get(t);
            if (isOperator(tok)) {
                continue;
            }
            String op = t == 0 ? "+" : tokens.get(t - 1);
            if (op.equals("*") || op.equals("/")) {
                BigDecimal factor = number(tok, text);
                if (op.equals("/") && factor.signum() == 0) {
                    throw new IllegalArgumentException("Can't divide by zero.");
                }
                term = op.equals("*") ? term.multiply(factor) : term.divide(factor, 10, RoundingMode.HALF_UP);
            } else {
                if (term != null) {
                    total = join.equals("+") ? total.add(term) : total.subtract(term);
                }
                String amount = tok.startsWith("+") ? tok.substring(1) : tok;
                term = BigDecimal.valueOf(parseTyped(amount), 2);   // an amount (automatic decimal point applies)
                join = op;
            }
        }
        total = join.equals("+") ? total.add(term) : total.subtract(term);
        return total.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static boolean isOperator(String token) {
        return token.length() == 1 && "+-*/".contains(token);
    }

    private static BigDecimal number(String token, String text) {
        try {
            return new BigDecimal(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a calculation Money can work out.");
        }
    }

    /**
     * Makes a text field work out a typed calculation when it's left (or Enter
     * is pressed), showing the result as the amount: "12.50+3.25" becomes
     * "$15.75". Anything else is left as typed.
     */
    public static void calculateOnExit(javax.swing.JTextField field) {
        Runnable work = () -> {
            String t = field.getText();
            if (isCalculation(t)) {
                try {
                    field.setText(format(calculate(t)));
                } catch (IllegalArgumentException ignored) {
                    // left as typed; saving reports the problem
                }
            }
        };
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                work.run();
            }
        });
        field.addActionListener(e -> work.run());
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
