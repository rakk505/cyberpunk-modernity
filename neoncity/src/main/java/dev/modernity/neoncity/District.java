package dev.modernity.neoncity;

/**
 * The twenty-six cultural biomes that make up the finite megacity.
 *
 * <p>Values are deliberately gameplay-facing rather than copies of real
 * buildings. Arnis/OSM studies provide the urban scale and street grain;
 * Minecraft palettes and the procedural massing grammar provide the final
 * architecture.</p>
 */
public enum District {
    A_CORP("A Corp", "Obsidian corporate crown", Architecture.CORPORATE, 52, 118, 288, -8, 0.88, 0.08),
    B_CORP("B Corp", "Bay Area garden campuses", Architecture.CAMPUS, 46, 34, 106, 21, 0.55, 0.48),
    C_CORP("C Corp", "Portland brick and timber", Architecture.BRICK, 38, 28, 92, -17, 0.63, 0.42),
    D_CORP("D Corp", "Seattle-Bellevue evergreen tech", Architecture.EVERGREEN, 44, 46, 148, 31, 0.63, 0.62),
    E_CORP("E Corp", "Mexico City courtyards", Architecture.COURTYARD, 36, 26, 88, 7, 0.72, 0.20),
    F_CORP("F Corp", "Miami tropical art deco", Architecture.TROPICAL_DECO, 40, 32, 122, -27, 0.65, 0.34),
    G_CORP("G Corp", "Jakarta vertical center", Architecture.TROPICAL_DENSE, 30, 44, 164, 13, 0.86, 0.28),
    H_CORP("H Corp", "Hong Kong hyper-density", Architecture.HYPER_DENSE, 24, 72, 256, -4, 0.96, 0.10),
    I_CORP("I Corp", "Roman stone terraces", Architecture.CLASSICAL, 42, 30, 98, 18, 0.62, 0.24),
    J_CORP("J Corp", "Macau-Las Vegas spectacle", Architecture.CASINO, 48, 48, 174, -33, 0.72, 0.16),
    K_CORP("K Corp", "Sterile research arcology", Architecture.RESEARCH, 54, 46, 166, 29, 0.62, 0.30),
    L_CORP("L Corp", "Seoul metropolitan glass", Architecture.KOREAN_METRO, 36, 52, 196, -21, 0.82, 0.18),
    M_CORP("M Corp", "Toronto metropolitan slabs", Architecture.METROPOLITAN, 46, 48, 182, 11, 0.68, 0.26),
    N_CORP("N Corp", "Parisian boulevards", Architecture.HAUSSMANN, 34, 30, 92, -12, 0.82, 0.20),
    O_CORP("O Corp", "Viennese grand blocks", Architecture.VIENNESE, 40, 34, 106, 26, 0.70, 0.22),
    P_CORP("P Corp", "New York art deco", Architecture.ART_DECO, 32, 70, 278, -6, 0.93, 0.08),
    Q_CORP("Q Corp", "Nagoya manufacturing metro", Architecture.JAPANESE_METRO, 42, 38, 132, 16, 0.68, 0.26),
    R_CORP("R Corp", "Osaka neon mercantile", Architecture.OSAKA_NEON, 28, 36, 144, -29, 0.88, 0.12),
    S_CORP("S Corp", "Busan citadel and Joseon fields", Architecture.JOSEON, 52, 18, 112, 4, 0.35, 0.36),
    T_CORP("T Corp", "Victorian steamworks", Architecture.STEAMPUNK, 44, 32, 126, 24, 0.76, 0.14),
    U_CORP("U Corp", "Global container harbor", Architecture.HARBOR, 60, 24, 104, -18, 0.58, 0.08),
    V_CORP("V Corp", "Swiss canal cantons", Architecture.ALPINE_CANAL, 42, 24, 92, 34, 0.50, 0.44),
    W_CORP("W Corp", "Shenzhen future skyline", Architecture.SHENZHEN, 38, 64, 248, -25, 0.88, 0.16),
    X_CORP("X Corp", "Hanoi industry and extraction", Architecture.HANOI_INDUSTRIAL, 34, 24, 104, 9, 0.66, 0.24),
    Y_CORP("Y Corp", "Nordic-Muscovite winter city", Architecture.WINTER_MONUMENTAL, 46, 36, 142, -14, 0.62, 0.34),
    Z_CORP("Z Corp", "Tokyo electric crossroads", Architecture.TOKYO_ELECTRIC, 26, 46, 184, 20, 0.94, 0.10);

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
        TOKYO_ELECTRIC
    }

    /** District-scale circulation grammar; every Corp owns a distinct plan. */
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
        NAGOYA_SPINES,
        OSAKA_MERCHANT_LANES,
        JOSEON_FIELD_ROADS,
        STEAMWORKS_YARDS,
        PORT_QUAYS,
        ALPINE_CANALS,
        SHENZHEN_AXES,
        HANOI_INDUSTRIAL,
        WINTER_PROSPEKTS,
        TOKYO_CROSSINGS
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
        ELECTRIC_SIGNS
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
    private final Architecture architecture;
    private final int parcelSize;
    private final int minHeight;
    private final int maxHeight;
    private final int orientationDegrees;
    private final double density;
    private final double vegetation;

    District(String label, String flavor, Architecture architecture, int parcelSize,
             int minHeight, int maxHeight, int orientationDegrees,
             double density, double vegetation) {
        this.label = label;
        this.flavor = flavor;
        this.architecture = architecture;
        this.parcelSize = parcelSize;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.orientationDegrees = orientationDegrees;
        this.density = density;
        this.vegetation = vegetation;
    }

    public String label() { return label; }
    public String flavor() { return flavor; }
    public Architecture architecture() { return architecture; }
    public int parcelSize() { return parcelSize; }
    public int minHeight() { return minHeight; }
    public int maxHeight() { return maxHeight; }
    public int orientationDegrees() { return orientationDegrees; }
    public double density() { return density; }
    public double vegetation() { return vegetation; }
    public String code() { return name().substring(0, 1); }

    public StreetPattern streetPattern() {
        return StreetPattern.values()[ordinal()];
    }

    public RoofStyle roofStyle() {
        return RoofStyle.values()[ordinal()];
    }

    public TreeStyle treeStyle() {
        return switch (this) {
            case A_CORP, J_CORP, K_CORP, N_CORP, O_CORP, P_CORP -> TreeStyle.FORMAL;
            case B_CORP, C_CORP, M_CORP -> TreeStyle.BROADLEAF;
            case D_CORP, Q_CORP -> TreeStyle.EVERGREEN;
            case E_CORP -> TreeStyle.ARID;
            case F_CORP, G_CORP, H_CORP, U_CORP -> TreeStyle.TROPICAL;
            case I_CORP -> TreeStyle.MEDITERRANEAN;
            case L_CORP, R_CORP, S_CORP, Z_CORP -> TreeStyle.CHERRY;
            case V_CORP -> TreeStyle.ALPINE;
            case Y_CORP -> TreeStyle.WINTER;
            case T_CORP, W_CORP, X_CORP -> TreeStyle.INDUSTRIAL;
        };
    }

    public CultureSignature cultureSignature() {
        return new CultureSignature(architecture, streetPattern(), roofStyle(), treeStyle());
    }
}
