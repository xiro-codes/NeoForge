package forge.neo.deck;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.neo.NeoSettings;
import forge.neo.ascent.AscentPool;

/**
 * <b>Filtro global de expansiones</b> en Ajustes: cuando está puesto, el juego
 * solo muestra las cartas y los mazos que usan cartas de esas expansiones.
 *
 * <p>Reutiliza la representación de {@link AscentPool} ({@code ""},
 * {@code "range:LEA:4ED"}, {@code "set:ZEN,WWK,ROE"}), pero incluye <b>todas</b>
 * las cartas que tienen alguna impresión en las expansiones elegidas (incluidas
 * tierras no básicas), además de las tierras básicas que entran siempre.
 *
 * <p>Un mazo entra ({@link #allows(Deck)}) si todas las cartas que lleva tienen
 * alguna impresión en las expansiones permitidas (o son tierras básicas).
 *
 * <p>Compatible con Java 8 / Android API 26.
 */
public final class SetFilter {

    /** Propiedad de sistema para pruebas/maquetas ({@code -Dneo.setFilter=range:LEA:4ED}). */
    public static final String SYS_PROP = "neo.setFilter";

    private static volatile String cachedKey;
    private static volatile AscentPool cachedPool;
    private static volatile Index cachedIndex;

    private static final class Index {
        final Set<String> codes;
        final Map<String, PaperCard> byName;
        final int cardCount;

        Index(final Set<String> codes, final Map<String, PaperCard> byName, final int cardCount) {
            this.codes = Collections.unmodifiableSet(codes);
            this.byName = Collections.unmodifiableMap(byName);
            this.cardCount = cardCount;
        }
    }

    private SetFilter() {
    }

    /** El valor guardado ahora (o la propiedad de sistema si está puesta). */
    private static String raw() {
        final String sys = System.getProperty(SYS_PROP);
        if (sys != null && !sys.trim().isEmpty()) {
            return sys.trim();
        }
        return NeoSettings.get(NeoSettings.SET_FILTER, "").trim();
    }

    /** El pozo configurado ahora ({@link AscentPool#ALL} si no hay filtro). */
    public static AscentPool current() {
        final String r = raw();
        final String k = cachedKey;
        final AscentPool p = cachedPool;
        if (p != null && r.equals(k)) {
            return p;
        }
        synchronized (SetFilter.class) {
            if (cachedPool != null && r.equals(cachedKey)) {
                return cachedPool;
            }
            final AscentPool parsed = AscentPool.parse(r);
            cachedKey = r;
            cachedPool = parsed;
            cachedIndex = null;
            return parsed;
        }
    }

    /**
     * Si el filtro está activo (no es "Todas", y en "Elegir expansiones" hay al
     * menos una elegida).
     */
    public static boolean isActive() {
        final AscentPool p = current();
        if (p.isAll()) {
            return false;
        }
        if (p.kind == AscentPool.Kind.SET && p.sets.isEmpty()) {
            return false;
        }
        return true;
    }

    /** Guarda un nuevo filtro y vacía los índices de cartas para que se aplique al momento. */
    public static void set(final AscentPool pool) {
        final AscentPool p = pool == null ? AscentPool.ALL : pool;
        final String serialized = p.isAll() || (p.kind == AscentPool.Kind.SET && p.sets.isEmpty())
                ? "" : p.serialize();
        final String previous = NeoSettings.get(NeoSettings.SET_FILTER, "").trim();
        NeoSettings.set(NeoSettings.SET_FILTER, serialized.isEmpty() ? null : serialized);
        NeoSettings.save();
        if (!serialized.equals(previous) || !serialized.equals(cachedKey)) {
            invalidate();
        }
    }

    /** Vacía la caché del filtro y de {@link CardLibrary} / {@link CardIndex}. */
    public static void invalidate() {
        synchronized (SetFilter.class) {
            cachedKey = null;
            cachedPool = null;
            cachedIndex = null;
        }
        CardLibrary.invalidate();
    }

    /** Los códigos de las expansiones permitidas, o vacío si no hay filtro activo. */
    public static Set<String> codes() {
        return isActive() ? index().codes : Collections.<String>emptySet();
    }

    /** Cuántas cartas distintas (sin contar tierras básicas) entran en este pozo. */
    public static int cardCount(final AscentPool pool) {
        if (pool == null || pool.isAll() || (pool.kind == AscentPool.Kind.SET && pool.sets.isEmpty())) {
            return FModel.getMagicDb().getCommonCards().getUniqueCards().size();
        }
        if (pool.equals(current())) {
            return index().cardCount;
        }
        return buildIndex(pool).cardCount;
    }

    /** Si esta edición entra en el filtro (o true si no hay filtro activo). */
    public static boolean allowsEdition(final String code) {
        return !isActive() || (code != null && index().codes.contains(code));
    }

    /**
     * Si la carta entra en el filtro configurado. Las tierras básicas entran
     * siempre.
     */
    public static boolean allows(final PaperCard card) {
        if (!isActive() || card == null) {
            return true;
        }
        if (card.getRules() != null && card.getRules().getType().isBasicLand()) {
            return true;
        }
        return index().byName.containsKey(card.getName());
    }

    /**
     * La impresión de esa carta dentro de las expansiones del filtro (o la
     * misma carta si ya pertenece a ellas o si no hay filtro activo).
     */
    public static PaperCard printing(final PaperCard card) {
        if (!isActive() || card == null) {
            return card;
        }
        if (index().codes.contains(card.getEdition())) {
            return card;
        }
        final PaperCard p = index().byName.get(card.getName());
        return p != null ? p : card;
    }

    /**
     * Si el mazo entero usa solo cartas permitidas por el filtro (comandante,
     * principal, banquillo y mazos extra).
     */
    public static boolean allows(final Deck deck) {
        if (!isActive()) {
            return true;
        }
        if (deck == null) {
            return false;
        }
        final Index idx = index();
        for (final Map.Entry<DeckSection, CardPool> sec : deck) {
            final CardPool pool = sec.getValue();
            if (pool == null) {
                continue;
            }
            for (final Map.Entry<PaperCard, Integer> e : pool) {
                final PaperCard c = e.getKey();
                if (c == null) {
                    continue;
                }
                if (c.getRules() != null && c.getRules().getType().isBasicLand()) {
                    continue;
                }
                if (!idx.byName.containsKey(c.getName())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Filtra una lista de mazos dejando solo los que cumplen {@link #allows(Deck)}. */
    public static List<Deck> filterDecks(final Collection<Deck> decks) {
        if (decks == null) {
            return Collections.emptyList();
        }
        if (!isActive()) {
            return decks instanceof List ? (List<Deck>) decks : new ArrayList<>(decks);
        }
        final List<Deck> out = new ArrayList<>(decks.size());
        for (final Deck d : decks) {
            if (allows(d)) {
                out.add(d);
            }
        }
        return out;
    }

    private static Index index() {
        final AscentPool p = current();
        Index idx = cachedIndex;
        if (idx != null) {
            return idx;
        }
        synchronized (SetFilter.class) {
            idx = cachedIndex;
            if (idx == null) {
                idx = buildIndex(p);
                cachedIndex = idx;
            }
            return idx;
        }
    }

    private static Index buildIndex(final AscentPool pool) {
        final Set<String> codes = new HashSet<>(pool.codes());
        final Map<String, PaperCard> byName = new HashMap<>();
        if (codes.isEmpty()) {
            return new Index(codes, byName, 0);
        }
        final Map<String, Date> dates = new HashMap<>();
        final CardEdition.Collection editions = FModel.getMagicDb().getEditions();
        int nonBasic = 0;
        for (final PaperCard p : FModel.getMagicDb().getCommonCards().getAllCards()) {
            if (!codes.contains(p.getEdition())) {
                continue;
            }
            final CardEdition ed = editions.get(p.getEdition());
            final Date d = ed == null ? null : ed.getDate();
            final String name = p.getName();
            final boolean isNew = !byName.containsKey(name);
            final Date had = dates.get(name);
            if (isNew || (d != null && (had == null || d.after(had)))) {
                byName.put(name, p);
                dates.put(name, d);
                if (isNew && (p.getRules() == null || !p.getRules().getType().isBasicLand())) {
                    nonBasic++;
                }
            }
        }
        for (final PaperCard p : FModel.getMagicDb().getVariantCards().getAllCards()) {
            if (!codes.contains(p.getEdition()) || DeckEditor.extraSectionOf(p) == null) {
                continue;
            }
            if (!byName.containsKey(p.getName())) {
                byName.put(p.getName(), p);
                nonBasic++;
            }
        }
        return new Index(codes, byName, nonBasic);
    }
}
