package dev.portsync.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerNumberTest {

    private static final String REFERENCE = "CSQU3054383";

    @Test
    @DisplayName("CSQU3054383 (the textbook example) validates")
    void referenceNumberIsValid() {
        assertThat(ContainerNumber.isValid(REFERENCE)).isTrue();
        assertThat(ContainerNumber.checkDigit("CSQU305438")).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MSKU9070323", "MAEU1234567", "TGHU7000015", "HLXU8044100"})
    @DisplayName("other numbers whose check digit was computed independently validate")
    void otherValidNumbers(String number) {
        assertThat(ContainerNumber.isValid(number)).as(number).isTrue();
    }

    @Test
    @DisplayName("changing any digit (positions 5-11) always fails")
    void everySingleDigitChangeFails() {
        for (int position = 4; position < 11; position++) {
            for (char digit = '0'; digit <= '9'; digit++) {
                if (digit == REFERENCE.charAt(position)) {
                    continue;
                }
                String mutated = replace(REFERENCE, position, digit);
                assertThat(ContainerNumber.isValid(mutated)).as(mutated).isFalse();
            }
        }
    }

    @Test
    @DisplayName("changing any letter fails, except the 4 substitutions ISO 6346 mathematically cannot detect")
    void singleLetterChangesFailExceptKnownBlindSpots() {
        List<String> stillValid = new ArrayList<>();
        for (int position = 0; position < 4; position++) {
            for (char letter = 'A'; letter <= 'Z'; letter++) {
                if (letter == REFERENCE.charAt(position)) {
                    continue;
                }
                String mutated = replace(REFERENCE, position, letter);
                if (ContainerNumber.isValid(mutated)) {
                    stillValid.add(mutated);
                }
            }
        }
        // C(13)->M(24), C->W(35), S(30)->I(19), Q(28)->G(17): value differences of exactly 11 or 22.
        assertThat(stillValid).containsExactlyInAnyOrder("MSQU3054383", "WSQU3054383", "CIQU3054383", "CSGU3054383");
    }

    @Test
    @DisplayName("changing a character into one of the wrong class (letter<->digit) fails")
    void wrongCharacterClassFails() {
        assertThat(ContainerNumber.isValid("CSQ03054383")).isFalse();
        assertThat(ContainerNumber.isValid("CSQUA054383")).isFalse();
        assertThat(ContainerNumber.isValid("CSQU305438X")).isFalse();
    }

    @Test
    void categoryMustBeUJOrZ() {
        String withCategoryX = "CSQX305438" + ContainerNumber.checkDigit("CSQX305438");
        assertThat(ContainerNumber.validate(withCategoryX).valid()).isFalse();
        assertThat(ContainerNumber.validate(withCategoryX).reason()).contains("category");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "CSQU305438", "CSQU30543831", "1234567890X"})
    void malformedInputIsRejectedWithAReason(String input) {
        ContainerNumber.Validation result = ContainerNumber.validate(input);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void wrongCheckDigitReportsTheExpectedDigit() {
        ContainerNumber.Validation result = ContainerNumber.validate("CSQU3054384");
        assertThat(result.valid()).isFalse();
        assertThat(result.expectedCheckDigit()).isEqualTo(3);
        assertThat(result.reason()).contains("expected 3");
    }

    @Test
    void inputIsNormalisedBeforeValidation() {
        ContainerNumber.Validation result = ContainerNumber.validate("  csqu 3054383 ");
        assertThat(result.valid()).isTrue();
        assertThat(result.input()).isEqualTo(REFERENCE);
    }

    @Test
    @DisplayName("remainder 10 maps to check digit 0")
    void remainderTenBecomesZero() {
        // Search a serial whose weighted sum is 10 (mod 11) and check the digit becomes 0.
        for (int serial = 0; serial < 1000; serial++) {
            String prefix = "ABCU" + String.format("%06d", serial);
            int sum = 0;
            for (int i = 0; i < 10; i++) {
                sum += ContainerNumber.charValue(prefix.charAt(i)) * (1 << i);
            }
            if (sum % 11 == 10) {
                assertThat(ContainerNumber.checkDigit(prefix)).isZero();
                assertThat(ContainerNumber.isValid(prefix + "0")).isTrue();
                return;
            }
        }
        throw new AssertionError("no serial with remainder 10 found in range");
    }

    @Test
    @DisplayName("generator only ever produces valid numbers")
    void generatedNumbersAreAlwaysValid() {
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < 10_000; i++) {
            String number = ContainerNumber.random(random, "PSY", "TRX", "OCN");
            assertThat(ContainerNumber.isValid(number)).as(number).isTrue();
            assertThat(number).hasSize(11);
            assertThat(number.substring(0, 3)).isIn("PSY", "TRX", "OCN");
        }
    }

    @Test
    void corruptedCheckDigitIsAlwaysInvalid() {
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 1_000; i++) {
            String valid = ContainerNumber.random(random, "PSY");
            assertThat(ContainerNumber.isValid(ContainerNumber.withWrongCheckDigit(valid))).isFalse();
        }
    }

    @Test
    void ofRejectsBadParts() {
        assertThatThrownBy(() -> ContainerNumber.of("cs", 'U', 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerNumber.of("CSQ", 'X', 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerNumber.of("CSQ", 'U', 1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContainerNumber.of("CSQ", 'U', 305438)).isEqualTo(REFERENCE);
    }

    private static String replace(String s, int position, char c) {
        return s.substring(0, position) + c + s.substring(position + 1);
    }
}
