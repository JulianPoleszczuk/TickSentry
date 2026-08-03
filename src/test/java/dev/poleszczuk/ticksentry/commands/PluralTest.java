package dev.poleszczuk.ticksentry.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluralTest {

    @Test
    @DisplayName("Only an exact one takes the singular form")
    void singularOnlyForOne() {
        assertEquals("1 incident", Plural.incidents(1));
        assertEquals("2 incidents", Plural.incidents(2));
        assertEquals("0 incidents", Plural.incidents(0));
        assertEquals("21 incidents", Plural.incidents(21));
    }

    @Test
    @DisplayName("The bare form can be taken without the number")
    void formWithoutNumber() {
        assertEquals("incident", Plural.form(1, "incident", "incidents"));
        assertEquals("incidents", Plural.form(7, "incident", "incidents"));
    }
}
