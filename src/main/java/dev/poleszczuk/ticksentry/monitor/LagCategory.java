package dev.poleszczuk.ticksentry.monitor;

/**
 * Prawdopodobna przyczyna lagu przypisana do konkretnego chunka.
 * Opisy sa celowo napisane jezykiem admina, nie profilera.
 */
public enum LagCategory {

    /** Duzo encji jednego typu - klasyczna farma mobow lub niezabity spawn. */
    MOB_FARM("Farma mobow", "Duzo encji tego samego typu w jednym miejscu"),

    /** Zalegajace przedmioty i kule doswiadczenia. */
    ITEM_CLUTTER("Zalegajace przedmioty", "Setki itemow lub kul XP lezacych na ziemi"),

    /** Duzo block-entity, zwykle hoppery, droppery i sortownie. */
    REDSTONE("Redstone / hoppery", "Duzo urzadzen typu hopper, dropper czy piec"),

    /** Skupisko graczy, np. spawn albo bitwa na arenie. */
    PLAYER_CLUSTER("Skupisko graczy", "Wielu graczy w jednym chunku"),

    /** Duzo encji, ale bez jednego wyraznie dominujacego typu. */
    ENTITY_OVERLOAD("Przeciazenie encjami", "Bardzo duzo roznych encji naraz"),

    /** Nie udalo sie wskazac oczywistego winowajcy. */
    UNKNOWN("Nieoczywiste zrodlo", "Zaden chunk nie wyroznia sie wyraznie");

    private final String title;
    private final String description;

    LagCategory(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /** @return krotka nazwa kategorii do wyswietlenia */
    public String title() {
        return title;
    }

    /** @return jednozdaniowe wyjasnienie dla admina */
    public String description() {
        return description;
    }
}
