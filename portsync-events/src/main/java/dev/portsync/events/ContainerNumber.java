package dev.portsync.events;

import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * An ISO 6346 freight container identification number, e.g. {@code CSQU3054383}.
 *
 * <pre>
 *   C S Q   U   3 0 5 4 3 8   3
 *   └owner┘ │   └─serial──┘   └ check digit
 *           └ equipment category (U = freight container, J = detachable equipment, Z = trailer/chassis)
 * </pre>
 *
 * <h2>Check digit algorithm</h2>
 * Each of the first 10 characters is given a numeric value: digits keep their value and letters
 * map to 10..38 starting at A=10 but <em>skipping multiples of 11</em> (so B=12, L=23, V=34).
 * Character {@code i} (0-based) is multiplied by {@code 2^i}; the products are summed; the sum is
 * taken modulo 11 and then modulo 10 (a remainder of 10 becomes check digit 0).
 *
 * <h2>Known limitation of the standard</h2>
 * Because letter values such as C=13 and M=24 differ by exactly 11, a handful of single-letter
 * substitutions in the owner code leave the sum unchanged modulo 11 and therefore still validate
 * (for CSQU3054383: MSQU…, WSQU…, CIQU…, CSGU…). Every single-digit change is always caught.
 * {@code ContainerNumberTest} pins this behaviour down exhaustively instead of pretending otherwise.
 */
public final class ContainerNumber {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{3}[UJZ][0-9]{7}");
    private static final int[] LETTER_VALUES = buildLetterValues();

    private ContainerNumber() {
    }

    /** Result of validating a candidate string, with a human readable reason when invalid. */
    public record Validation(String input, boolean valid, String reason, Integer expectedCheckDigit) {

        static Validation ok(String input) {
            return new Validation(input, true, null, null);
        }

        static Validation fail(String input, String reason, Integer expected) {
            return new Validation(input, false, reason, expected);
        }
    }

    /** Returns true when {@code candidate} is a well-formed ISO 6346 number with a correct check digit. */
    public static boolean isValid(String candidate) {
        return validate(candidate).valid();
    }

    /**
     * Validates format and check digit. Input is trimmed and upper-cased first so that
     * " csqu3054383 " typed by an operator is accepted.
     */
    public static Validation validate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Validation.fail(candidate, "Container number is empty", null);
        }
        String normalised = normalise(candidate);
        if (normalised.length() != 11) {
            return Validation.fail(normalised, "Container number must be 11 characters (got " + normalised.length() + ")", null);
        }
        if (!FORMAT.matcher(normalised).matches()) {
            return Validation.fail(normalised,
                    "Expected 3-letter owner code, category letter U/J/Z, 6-digit serial and a check digit", null);
        }
        int expected = checkDigit(normalised.substring(0, 10));
        int actual = normalised.charAt(10) - '0';
        if (expected != actual) {
            return Validation.fail(normalised, "Check digit mismatch: expected " + expected + " but was " + actual, expected);
        }
        return Validation.ok(normalised);
    }

    /** Upper-cases and strips whitespace. */
    public static String normalise(String candidate) {
        return Objects.requireNonNull(candidate).trim().toUpperCase().replace(" ", "");
    }

    /**
     * Computes the ISO 6346 check digit for the first 10 characters (owner code, category, serial).
     *
     * @throws IllegalArgumentException if the prefix is not exactly 10 valid characters
     */
    public static int checkDigit(String prefix) {
        if (prefix == null || prefix.length() != 10) {
            throw new IllegalArgumentException("Check digit needs exactly 10 characters, got: " + prefix);
        }
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += charValue(prefix.charAt(i)) * (1 << i);
        }
        return (sum % 11) % 10;
    }

    /** Builds a full, valid container number from its parts. */
    public static String of(String ownerCode, char category, int serial) {
        if (ownerCode == null || !ownerCode.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Owner code must be 3 upper-case letters: " + ownerCode);
        }
        if (category != 'U' && category != 'J' && category != 'Z') {
            throw new IllegalArgumentException("Category must be U, J or Z: " + category);
        }
        if (serial < 0 || serial > 999_999) {
            throw new IllegalArgumentException("Serial must be 0..999999: " + serial);
        }
        String prefix = ownerCode + category + String.format("%06d", serial);
        return prefix + checkDigit(prefix);
    }

    /** Generates a random valid freight container number (category U) for one of the given owner codes. */
    public static String random(RandomGenerator random, String... ownerCodes) {
        if (ownerCodes.length == 0) {
            throw new IllegalArgumentException("At least one owner code is required");
        }
        String owner = ownerCodes[random.nextInt(ownerCodes.length)];
        return of(owner, 'U', random.nextInt(1_000_000));
    }

    /**
     * Returns a copy of a valid number whose check digit has been deliberately corrupted.
     * Used by the simulator's fault injection and by tests.
     */
    public static String withWrongCheckDigit(String validNumber) {
        int correct = validNumber.charAt(10) - '0';
        int wrong = (correct + 1) % 10;
        return validNumber.substring(0, 10) + wrong;
    }

    /** Parses and returns the normalised number, or empty when invalid. */
    public static Optional<String> parse(String candidate) {
        Validation v = validate(candidate);
        return v.valid() ? Optional.of(v.input()) : Optional.empty();
    }

    static int charValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'Z') {
            return LETTER_VALUES[c - 'A'];
        }
        throw new IllegalArgumentException("Invalid character in container number: '" + c + "'");
    }

    private static int[] buildLetterValues() {
        int[] values = new int[26];
        int value = 10;
        for (int i = 0; i < 26; i++) {
            if (value % 11 == 0) {
                value++; // skip 11, 22, 33
            }
            values[i] = value++;
        }
        return values;
    }
}
