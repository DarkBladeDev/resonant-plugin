package com.resonant.utils;

import java.util.Locale;

public final class DurationParser {

    private DurationParser() {
    }

    public static Long parseSeconds(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent") || value.equals("permanente")) {
            return 0L;
        }
        long multiplier = 1;
        char last = value.charAt(value.length() - 1);
        if (Character.isLetter(last)) {
            value = value.substring(0, value.length() - 1);
            multiplier = switch (last) {
                case 's' -> 1;
                case 'm' -> 60;
                case 'h' -> 3600;
                case 'd' -> 86400;
                default -> -1;
            };
        }
        if (multiplier <= 0) {
            return null;
        }
        try {
            long base = Long.parseLong(value);
            if (base < 0) {
                return null;
            }
            return base * multiplier;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
