package dev.poleszczuk.ticksentry.commands;

/**
 * Odmiana rzeczownikow po liczebniku wedlug zasad polskich.
 *
 * <p>Bez tego komunikaty wygladaja jak "1 incydentow". Regula: 1 to forma pojedyncza,
 * koncowki 2-4 (poza nastolatkami 12-14) to forma "few", reszta to dopelniacz mnogi.</p>
 */
public final class Plural {

    private Plural() {
    }

    /**
     * Skleja liczbe z poprawna forma rzeczownika.
     *
     * @param count liczba
     * @param one   forma dla 1, np. "incydent"
     * @param few   forma dla 2-4, np. "incydenty"
     * @param many  forma dla pozostalych, np. "incydentow"
     * @return liczba wraz z odmieniona forma, np. {@code "3 incydenty"}
     */
    public static String of(int count, String one, String few, String many) {
        return count + " " + form(count, one, few, many);
    }

    /**
     * Wybiera sama forme rzeczownika, bez liczby.
     *
     * @param count liczba
     * @param one   forma dla 1
     * @param few   forma dla 2-4
     * @param many  forma dla pozostalych
     * @return dopasowana forma
     */
    public static String form(int count, String one, String few, String many) {
        int abs = Math.abs(count);
        if (abs == 1) {
            return one;
        }
        int lastTwo = abs % 100;
        if (lastTwo >= 12 && lastTwo <= 14) {
            return many;
        }
        int last = abs % 10;
        return last >= 2 && last <= 4 ? few : many;
    }

    /**
     * Skrot dla najczestszego przypadku w tym pluginie.
     *
     * @param count liczba incydentow
     * @return np. {@code "1 incydent"}, {@code "3 incydenty"}, {@code "12 incydentow"}
     */
    public static String incidents(int count) {
        return of(count, "incydent", "incydenty", "incydentow");
    }
}
