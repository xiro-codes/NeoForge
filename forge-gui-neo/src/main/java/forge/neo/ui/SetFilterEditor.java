package forge.neo.ui;

import java.util.Locale;

import forge.neo.NeoText;
import forge.neo.ascent.AscentPool;
import forge.neo.deck.SetFilter;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * <b>Filtro global de expansiones</b> en Ajustes → Modos: elegir con qué
 * expansiones juega el juego (Todas, Desde/hasta, o Elegir expansiones). Cuando
 * está activo, solo se muestran los mazos y las cartas que usan cartas de esas
 * expansiones.
 */
final class SetFilterEditor extends VBox {

    SetFilterEditor() {
        super(8);
        final SetPoolPicker picker = new SetPoolPicker(SetFilter::set, SetFilterEditor::describe);
        picker.preset(SetFilter.current());
        final Label note = new Label(NeoText.get("setfilter.note"));
        note.getStyleClass().add("settings-note");
        note.setWrapText(true);
        note.setMaxWidth(UiScale.px(640));
        note.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().addAll(picker, note);
    }

    private static String[] describe(final AscentPool p) {
        if (p == null || p.isAll()) {
            return new String[] {"", null};
        }
        if (p.kind == AscentPool.Kind.SET && p.sets.isEmpty()) {
            return new String[] {NeoText.get("ascent.pool.problem.none"), "problem"};
        }
        final String count = String.format(Locale.getDefault(), "%,d", SetFilter.cardCount(p));
        return new String[] {NeoText.get("setfilter.count", count), null};
    }
}
