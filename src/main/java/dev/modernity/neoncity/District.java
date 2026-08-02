package dev.modernity.neoncity;

import java.util.Locale;
import java.util.Optional;

/** Cultural districts that make up the finite megacity. */
public enum District {
    A_CORP("A Corp", "Obsidian corporate crown", "A", "a", "A",
            Architecture.CORPORATE, StreetPattern.CEREMONIAL_AXES, RoofStyle.BLACK_CROWN,
            TreeStyle.FORMAL, 52, 118, 288, -8, 0.88, 0.08, false),
    B_CORP("B Corp", "Bay Area garden campuses", "B", "b", "B",
            Architecture.CAMPUS, StreetPattern.CAMPUS_LOOPS, RoofStyle.GREEN_TERRACE,
            TreeStyle.BROADLEAF, 46, 34, 106, 21, 0.55, 0.48, false),
    C_CORP("C Corp", "Portland brick and timber", "C", "c", "C",
            Architecture.BRICK, StreetPattern.PORTLAND_GREENWAYS, RoofStyle.SAWTOOTH,
            TreeStyle.BROADLEAF, 38, 28, 92, -17, 0.63, 0.42, false),
    D_CORP("D Corp", "Seattle-Bellevue evergreen tech", "D", "d", "D",
            Architecture.EVERGREEN, StreetPattern.EVERGREEN_ARCS, RoofStyle.GLASS_CANOPY,
            TreeStyle.EVERGREEN, 44, 46, 148, 31, 0.63, 0.62, false),
    E_CORP("E Corp", "Mexico City courtyards", "E", "e", "E",
            Architecture.COURTYARD, StreetPattern.COURTYARD_LANES, RoofStyle.COURTYARD_TILE,
            TreeStyle.ARID, 36, 26, 88, 7, 0.72, 0.20, false),
    F_CORP("F Corp", "Miami tropical art deco", "F", "f", "F",
            Architecture.TROPICAL_DECO, StreetPattern.COASTAL_SWEEPS, RoofStyle.DECO_FIN,
            TreeStyle.TROPICAL, 40, 32, 122, -27, 0.65, 0.34, false),
    G_CORP("G Corp", "Jakarta vertical center", "G", "g", "G",
            Architecture.TROPICAL_DENSE, StreetPattern.TROPICAL_WEAVE, RoofStyle.TROPICAL_GARDEN,
            TreeStyle.TROPICAL, 30, 44, 164, 13, 0.86, 0.28, false),
    H_CORP("H Corp", "Hong Kong hyper-density", "H", "h", "H",
            Architecture.HYPER_DENSE, StreetPattern.VERTICAL_ALLEYS, RoofStyle.MECHANICAL_CLUSTER,
            TreeStyle.TROPICAL, 24, 72, 256, -4, 0.96, 0.10, false),
    I_CORP("I Corp", "Roman stone terraces", "I", "i", "I",
            Architecture.CLASSICAL, StreetPattern.CLASSICAL_RADIALS, RoofStyle.TERRACOTTA_DOME,
            TreeStyle.MEDITERRANEAN, 42, 30, 98, 18, 0.62, 0.24, false),
    J_CORP("J Corp", "Las Vegas spectacle", "J", "j", "J",
            Architecture.CASINO, StreetPattern.SPECTACLE_STRIP, RoofStyle.CASINO_CROWN,
            TreeStyle.FORMAL, 48, 48, 174, -33, 0.72, 0.16, false),
    K_CORP("K Corp", "Sterile research arcology", "K", "k", "K",
            Architecture.RESEARCH, StreetPattern.RESEARCH_CAMPUS, RoofStyle.LAB_DOME,
            TreeStyle.FORMAL, 54, 46, 166, 29, 0.62, 0.30, false),
    L_CORP("L Corp", "Seoul metropolitan glass", "L", "l", "L",
            Architecture.KOREAN_METRO, StreetPattern.SEOUL_SUPERBLOCKS, RoofStyle.METRO_ANTENNA,
            TreeStyle.CHERRY, 36, 52, 196, -21, 0.82, 0.18, false),
    M_CORP("M Corp", "Toronto metropolitan slabs", "M", "m", "M",
            Architecture.METROPOLITAN, StreetPattern.TORONTO_CONCESSIONS, RoofStyle.STEPPED_SLAB,
            TreeStyle.BROADLEAF, 46, 48, 182, 11, 0.68, 0.26, false),
    N_CORP("N Corp", "Parisian boulevards", "N", "n", "N",
            Architecture.HAUSSMANN, StreetPattern.PARIS_BOULEVARDS, RoofStyle.MANSARD,
            TreeStyle.FORMAL, 34, 30, 92, -12, 0.82, 0.20, false),
    O_CORP("O Corp", "Viennese grand blocks", "O", "o", "O",
            Architecture.VIENNESE, StreetPattern.VIENNA_RINGS, RoofStyle.COPPER_DOME,
            TreeStyle.FORMAL, 40, 34, 106, 26, 0.70, 0.22, false),
    P_CORP("P Corp", "New York art deco", "P", "p", "P",
            Architecture.ART_DECO, StreetPattern.MANHATTAN_AVENUES, RoofStyle.ART_DECO_SPIRE,
            TreeStyle.FORMAL, 32, 70, 278, -6, 0.93, 0.08, false),
    Q_CORP("Q Corp", "Fukuoka Tenjin transit and maker districts", "Q", "q", "Q",
            Architecture.JAPANESE_METRO, StreetPattern.FUKUOKA_TRANSIT_LANES,
            RoofStyle.FACTORY_VENTS, TreeStyle.EVERGREEN,
            42, 38, 132, 16, 0.68, 0.26, false),
    R_CORP("R Corp", "Osaka neon mercantile", "R", "r", "R",
            Architecture.OSAKA_NEON, StreetPattern.OSAKA_MERCHANT_LANES, RoofStyle.NEON_BILLBOARD,
            TreeStyle.CHERRY, 28, 36, 144, -29, 0.88, 0.12, false),
    S_CORP("S Corp", "Busan citadel and Joseon fields", "S", "s", "S",
            Architecture.JOSEON, StreetPattern.JOSEON_FIELD_ROADS, RoofStyle.HANOK_GABLE,
            TreeStyle.CHERRY, 52, 18, 112, 4, 0.35, 0.36, false),
    T_CORP("T Corp", "Victorian steamworks", "T", "t", "T",
            Architecture.STEAMPUNK, StreetPattern.STEAMWORKS_YARDS, RoofStyle.STEAM_STACKS,
            TreeStyle.INDUSTRIAL, 44, 32, 126, 24, 0.76, 0.14, false),
    U_CORP("U Corp", "Global container harbor", "U", "u", "U",
            Architecture.HARBOR, StreetPattern.PORT_QUAYS, RoofStyle.PORT_CRANE,
            TreeStyle.TROPICAL, 60, 24, 104, -18, 0.58, 0.08, false),
    V_CORP("V Corp", "Swiss canal cantons", "V", "v", "V",
            Architecture.ALPINE_CANAL, StreetPattern.ALPINE_CANALS, RoofStyle.ALPINE_GABLE,
            TreeStyle.ALPINE, 42, 24, 92, 34, 0.50, 0.44, false),
    W_CORP("W Corp", "Shenzhen future skyline", "W", "w", "W",
            Architecture.SHENZHEN, StreetPattern.SHENZHEN_AXES, RoofStyle.FUTURE_SPIRE,
            TreeStyle.INDUSTRIAL, 38, 64, 248, -25, 0.88, 0.16, false),
    X_CORP("X Corp", "Hanoi industry and extraction", "X", "x", "X",
            Architecture.HANOI_INDUSTRIAL, StreetPattern.HANOI_INDUSTRIAL,
            RoofStyle.INDUSTRIAL_TANKS, TreeStyle.INDUSTRIAL,
            34, 24, 104, 9, 0.66, 0.24, false),
    Y_CORP("Y Corp", "Nordic-Muscovite winter city", "Y", "y", "Y",
            Architecture.WINTER_MONUMENTAL, StreetPattern.WINTER_PROSPEKTS, RoofStyle.SNOW_CROWN,
            TreeStyle.WINTER, 46, 36, 142, -14, 0.62, 0.34, true),
    Z_CORP("Z Corp", "Tokyo electric crossroads", "Z", "z", "Z",
            Architecture.TOKYO_ELECTRIC, StreetPattern.TOKYO_CROSSINGS, RoofStyle.ELECTRIC_SIGNS,
            TreeStyle.CHERRY, 26, 46, 184, 20, 0.94, 0.10, false),

    // New districts are appended to preserve the persisted A-Z ordinal contract.
    AE_DISTRICT("District \u00C6", "Oslo-Helsinki Nordic waterfronts", "AE", "ae", "AE",
            Architecture.NORDIC_MARITIME, StreetPattern.NORDIC_WATERFRONTS, RoofStyle.NORDIC_GABLE,
            TreeStyle.WINTER, 40, 32, 120, -12, 0.68, 0.42, true),
    YI_DISTRICT("District Yi", "Moscow-centered Russian brutalist infrastructure", "YI", "yi", "YI",
            Architecture.RUSSIAN_BRUTALIST, StreetPattern.MOSCOW_RADIALS, RoofStyle.BRUTALIST_CROWN,
            TreeStyle.WINTER, 56, 42, 156, 0, 0.74, 0.18, true),
    WANG_DISTRICT("District \u738B", "Historic and industrial Boston row blocks", "WANG", "wang", "WANG",
            Architecture.BOSTON_INDUSTRIAL, StreetPattern.BOSTON_ROW_STREETS,
            RoofStyle.BOSTON_COPPER_SAWTOOTH, TreeStyle.BROADLEAF,
            36, 28, 112, 9, 0.72, 0.22, false),
    XI_DISTRICT("District Xi", "Bangkok tropical mixed-use density", "XI", "xi", "XI",
            Architecture.BANGKOK_MIXED, StreetPattern.BANGKOK_SOIS, RoofStyle.THAI_TIERED,
            TreeStyle.TROPICAL, 30, 36, 150, -18, 0.86, 0.34, false),
    UI_DISTRICT("District Ui", "Singapore biophilic vertical urbanism", "UI", "ui", "UI",
            Architecture.SINGAPORE_BIOPHILIC, StreetPattern.SINGAPORE_SUPERBLOCKS,
            RoofStyle.SKYGARDEN_CROWN, TreeStyle.TROPICAL,
            44, 48, 188, 14, 0.78, 0.56, false),
    UANG_DISTRICT("District Uang", "Amsterdam canal houses and industrial docks", "UANG", "uang", "UANG",
            Architecture.AMSTERDAM_CANAL, StreetPattern.AMSTERDAM_CANAL_RINGS, RoofStyle.DUTCH_GABLE,
            TreeStyle.BROADLEAF, 32, 26, 102, -8, 0.70, 0.32, false),
    PON_DISTRICT("District Pon", "Madrid avenues with Lisbon backstreets", "PON", "pon", "PON",
            Architecture.IBERIAN_HILLS, StreetPattern.IBERIAN_BOULEVARDS, RoofStyle.IBERIAN_TILE,
            TreeStyle.MEDITERRANEAN, 38, 30, 118, 19, 0.72, 0.22, false),
    POK_DISTRICT("District Pok", "Austin technology corridors and warehouse blocks", "POK", "pok", "POK",
            Architecture.AUSTIN_TECH, StreetPattern.AUSTIN_GRID, RoofStyle.TEXAS_INDUSTRIAL,
            TreeStyle.ARID, 46, 24, 108, 7, 0.58, 0.30, false),
    PAK_DISTRICT("District Pak", "Dubai desert towers and engineered infrastructure", "PAK", "pak", "PAK",
            Architecture.DUBAI_FUTURIST, StreetPattern.DUBAI_AXES, RoofStyle.DESERT_SPIRE,
            TreeStyle.ARID, 54, 58, 244, -24, 0.76, 0.08, false);

    public enum Architecture {
        CORPORATE,
        CAMPUS,
        BRICK,
        EVERGREEN,
        COURTYARD,
        TROPICAL_DECO,
        TROPICAL_DENSE,
        HYPER_DENSE,
        CLASSICAL,
        CASINO,
        RESEARCH,
        KOREAN_METRO,
        METROPOLITAN,
        HAUSSMANN,
        VIENNESE,
        ART_DECO,
        JAPANESE_METRO,
        OSAKA_NEON,
        JOSEON,
        STEAMPUNK,
        HARBOR,
        ALPINE_CANAL,
        SHENZHEN,
        HANOI_INDUSTRIAL,
        WINTER_MONUMENTAL,
        TOKYO_ELECTRIC,
        NORDIC_MARITIME,
        RUSSIAN_BRUTALIST,
        BOSTON_INDUSTRIAL,
        BANGKOK_MIXED,
        SINGAPORE_BIOPHILIC,
        AMSTERDAM_CANAL,
        IBERIAN_HILLS,
        AUSTIN_TECH,
        DUBAI_FUTURIST
    }

    /** District-scale circulation grammar; every district owns a distinct plan. */
    public enum StreetPattern {
        CEREMONIAL_AXES,
        CAMPUS_LOOPS,
        PORTLAND_GREENWAYS,
        EVERGREEN_ARCS,
        COURTYARD_LANES,
        COASTAL_SWEEPS,
        TROPICAL_WEAVE,
        VERTICAL_ALLEYS,
        CLASSICAL_RADIALS,
        SPECTACLE_STRIP,
        RESEARCH_CAMPUS,
        SEOUL_SUPERBLOCKS,
        TORONTO_CONCESSIONS,
        PARIS_BOULEVARDS,
        VIENNA_RINGS,
        MANHATTAN_AVENUES,
        FUKUOKA_TRANSIT_LANES,
        OSAKA_MERCHANT_LANES,
        JOSEON_FIELD_ROADS,
        STEAMWORKS_YARDS,
        PORT_QUAYS,
        ALPINE_CANALS,
        SHENZHEN_AXES,
        HANOI_INDUSTRIAL,
        WINTER_PROSPEKTS,
        TOKYO_CROSSINGS,
        NORDIC_WATERFRONTS,
        MOSCOW_RADIALS,
        BOSTON_ROW_STREETS,
        BANGKOK_SOIS,
        SINGAPORE_SUPERBLOCKS,
        AMSTERDAM_CANAL_RINGS,
        IBERIAN_BOULEVARDS,
        AUSTIN_GRID,
        DUBAI_AXES
    }

    /** Roof silhouette used by the per-column cultural massing pass. */
    public enum RoofStyle {
        BLACK_CROWN,
        GREEN_TERRACE,
        SAWTOOTH,
        GLASS_CANOPY,
        COURTYARD_TILE,
        DECO_FIN,
        TROPICAL_GARDEN,
        MECHANICAL_CLUSTER,
        TERRACOTTA_DOME,
        CASINO_CROWN,
        LAB_DOME,
        METRO_ANTENNA,
        STEPPED_SLAB,
        MANSARD,
        COPPER_DOME,
        ART_DECO_SPIRE,
        FACTORY_VENTS,
        NEON_BILLBOARD,
        HANOK_GABLE,
        STEAM_STACKS,
        PORT_CRANE,
        ALPINE_GABLE,
        FUTURE_SPIRE,
        INDUSTRIAL_TANKS,
        SNOW_CROWN,
        ELECTRIC_SIGNS,
        NORDIC_GABLE,
        BRUTALIST_CROWN,
        BOSTON_COPPER_SAWTOOTH,
        THAI_TIERED,
        SKYGARDEN_CROWN,
        DUTCH_GABLE,
        IBERIAN_TILE,
        TEXAS_INDUSTRIAL,
        DESERT_SPIRE
    }

    public enum TreeStyle {
        FORMAL,
        BROADLEAF,
        EVERGREEN,
        ARID,
        TROPICAL,
        MEDITERRANEAN,
        CHERRY,
        ALPINE,
        WINTER,
        INDUSTRIAL
    }

    public record CultureSignature(
            Architecture architecture,
            StreetPattern streets,
            RoofStyle roof,
            TreeStyle trees
    ) {}

    private final String label;
    private final String flavor;
    private final String catalogCode;
    private final String resourceCode;
    private final String commandCode;
    private final Architecture architecture;
    private final StreetPattern streetPattern;
    private final RoofStyle roofStyle;
    private final TreeStyle treeStyle;
    private final int parcelSize;
    private final int minHeight;
    private final int maxHeight;
    private final int orientationDegrees;
    private final double density;
    private final double vegetation;
    private final boolean sharedWinter;

    District(
            String label,
            String flavor,
            String catalogCode,
            String resourceCode,
            String commandCode,
            Architecture architecture,
            StreetPattern streetPattern,
            RoofStyle roofStyle,
            TreeStyle treeStyle,
            int parcelSize,
            int minHeight,
            int maxHeight,
            int orientationDegrees,
            double density,
            double vegetation,
            boolean sharedWinter) {
        this.label = label;
        this.flavor = flavor;
        this.catalogCode = catalogCode;
        this.resourceCode = resourceCode;
        this.commandCode = commandCode;
        this.architecture = architecture;
        this.streetPattern = streetPattern;
        this.roofStyle = roofStyle;
        this.treeStyle = treeStyle;
        this.parcelSize = parcelSize;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.orientationDegrees = orientationDegrees;
        this.density = density;
        this.vegetation = vegetation;
        this.sharedWinter = sharedWinter;
    }

    public String label() { return label; }
    public String flavor() { return flavor; }
    public String catalogCode() { return catalogCode; }
    public String resourceCode() { return resourceCode; }
    public String commandCode() { return commandCode; }
    /** Compact player-facing district mark; command and resource identifiers stay ASCII. */
    public String code() { return shortLabel(label); }
    public Architecture architecture() { return architecture; }
    public StreetPattern streetPattern() { return streetPattern; }
    public RoofStyle roofStyle() { return roofStyle; }
    public TreeStyle treeStyle() { return treeStyle; }
    public int parcelSize() { return parcelSize; }
    public int minHeight() { return minHeight; }
    public int maxHeight() { return maxHeight; }
    public int orientationDegrees() { return orientationDegrees; }
    public double density() { return density; }
    public double vegetation() { return vegetation; }
    public boolean isSharedWinter() { return sharedWinter; }

    /** Resolves command, catalog, resource, full-label, and short display aliases. */
    public static Optional<District> fromCode(String value) {
        if (value == null) return Optional.empty();
        String normalized = value.trim();
        String alias = shortLabel(normalized);
        for (District district : values()) {
            if (normalized.equalsIgnoreCase(district.commandCode)
                    || normalized.equalsIgnoreCase(district.catalogCode)
                    || normalized.equalsIgnoreCase(district.resourceCode)
                    || normalized.equalsIgnoreCase(district.label)
                    || normalized.equalsIgnoreCase(shortLabel(district.label))
                    || alias.equalsIgnoreCase(district.commandCode)
                    || alias.equalsIgnoreCase(district.catalogCode)
                    || alias.equalsIgnoreCase(district.resourceCode)) {
                return Optional.of(district);
            }
        }
        return Optional.empty();
    }

    private static String shortLabel(String label) {
        if (label.regionMatches(true, 0, "District ", 0, "District ".length())) {
            return label.substring("District ".length());
        }
        return label.toUpperCase(Locale.ROOT).endsWith(" CORP")
                ? label.substring(0, label.length() - " Corp".length())
                : label;
    }

    public CultureSignature cultureSignature() {
        return new CultureSignature(architecture, streetPattern, roofStyle, treeStyle);
    }
}
