package dev.poleszczuk.ticksentry.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluralTest {

    @Test
    @DisplayName("Liczebniki odmieniaja sie po polsku")
    void polishPluralRules() {
        assertEquals("1 incydent", Plural.incidents(1));
        assertEquals("2 incydenty", Plural.incidents(2));
        assertEquals("4 incydenty", Plural.incidents(4));
        assertEquals("5 incydentow", Plural.incidents(5));
        assertEquals("0 incydentow", Plural.incidents(0));
    }

    @Test
    @DisplayName("Nastolatki biora dopelniacz mimo koncowki 2-4")
    void teensUseGenitive() {
        assertEquals("12 incydentow", Plural.incidents(12));
        assertEquals("13 incydentow", Plural.incidents(13));
        assertEquals("14 incydentow", Plural.incidents(14));
    }

    @Test
    @DisplayName("Po setkach regula liczy sie od dwoch ostatnich cyfr")
    void hundredsFollowLastTwoDigits() {
        assertEquals("22 incydenty", Plural.incidents(22));
        assertEquals("25 incydentow", Plural.incidents(25));
        assertEquals("102 incydenty", Plural.incidents(102));
        assertEquals("112 incydentow", Plural.incidents(112));
        assertEquals("101 incydentow", Plural.incidents(101));
    }
}
