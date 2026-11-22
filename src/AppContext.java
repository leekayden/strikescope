import java.util.HashMap;
import java.util.Map;

/** Shared application state: dataset, per-airport models, selected airport. */
final class AppContext {

    StrikeData data;                      // null until a dataset is loaded
    Map<String, RiskModel> models = Map.of();

    /** Airport currently selected; shared across all pages. */
    String selectedAirport;

    /** Set by MainFrame: pushes the selected airport to every page. */
    Runnable onAirportChange;

    void set(StrikeData d) {
        this.data = d;
        this.selectedAirport = null;
        Map<String, RiskModel> m = new HashMap<>();
        for (String id : d.modelableAirports()) {
            m.put(id, new RiskModel(d.recordsFor(id), id));
        }
        this.models = m;
    }

    /** Call when the user changes the airport on any page. */
    void selectAirport(String id) {
        if (id == null || id.equals(selectedAirport)) return;
        selectedAirport = id;
        if (onAirportChange != null) onAirportChange.run();
    }
}
