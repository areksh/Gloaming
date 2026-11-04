package sh.arek.gloaming.astronomy

/**
 * One star of a figure, as a point to draw and never as a subject of its own.
 *
 * @property id the star's Bayer letter spelled out (`"alpha"`, `"theta2"`),
 *   unique within its figure; a star borrowed from a neighbour is
 *   `"<letter>-<abbreviation>"`.
 * @property name the star's proper name, where it has one in common use.
 * @property raHours J2000 right ascension in hours.
 * @property dec J2000 declination in degrees.
 * @property magnitude visual magnitude, which sets how large the dot is drawn.
 * @property borrowedFrom the constellation the star really belongs to, for
 *   the two figures that share one with a neighbour: Auriga's tip of the
 *   bull's horn and the corner of the Square of Pegasus.
 */
data class FigureStar(
    val id: String,
    val name: String?,
    val raHours: Double,
    val dec: Double,
    val magnitude: Double,
    val borrowedFrom: String? = null,
)

/**
 * A pattern's stick figure: its stars, and the lines drawn through them.
 *
 * @property strokes each one a line drawn through the named stars in order.
 *   Strokes rather than pairs, because that is how a figure is read and
 *   checked: Orion's belt is one stroke through three stars, not two pairs.
 */
data class Figure(val stars: List<FigureStar>, val strokes: List<List<String>>) {

    /** Every line of the figure as a pair of indexes into [stars]. */
    fun segments(): List<Pair<Int, Int>> {
        val index = stars.withIndex().associate { (i, star) -> star.id to i }
        return strokes.flatMap { stroke ->
            stroke.zipWithNext { a, b -> index.getValue(a) to index.getValue(b) }
        }
    }
}

/**
 * The stick figure of every pattern in [Constellations.WATCHED].
 *
 * **Stars are drawn here and listed nowhere.** ADR 0012 kept individual stars
 * out of the app, and ADR 0017 records why a figure does not let them back in:
 * a star is a point of a pattern, the landmark case 0012 already allowed, and
 * never a row, a chip, a target or an entry of its own.
 *
 * **Typed in by hand.** Positions and magnitudes are from the Yale Bright Star
 * Catalogue, 5th revised edition, looked up by Bayer designation; the lines
 * are this app's own, so no third-party figure set has to be licensed.
 * Acrux, Rigil Kentaurus and epsilon Lyrae are each two catalogue entries
 * that the eye sees as one star; each is one star here, at the brighter
 * component, with the combined magnitude (for the first two, the one the
 * pattern table already uses).
 *
 * **J2000, and never precessed**, for the reason [Constellation] gives.
 * [SkyPositions.fixed] does the conversion to the frame of date when a figure
 * is placed.
 *
 * Kept apart from [Constellation], keyed by name, because that type is
 * serialized into snapshots and a figure is not worth changing their format
 * for.
 */
object ConstellationFigures {

    /** The figure for a pattern, or null for one the table does not know. */
    fun of(constellation: Constellation): Figure? = FIGURES[constellation.name]

    val FIGURES: Map<String, Figure> = mapOf(
        "Orion" to Figure(
            stars = listOf(
                FigureStar("gamma", "Bellatrix", 5.4189, 6.350, 1.64),
                FigureStar("lambda", "Meissa", 5.5856, 9.934, 3.54),
                FigureStar("alpha", "Betelgeuse", 5.9195, 7.407, 0.50),
                FigureStar("zeta", "Alnitak", 5.6793, -1.943, 2.05),
                FigureStar("epsilon", "Alnilam", 5.6036, -1.202, 1.70),
                FigureStar("delta", "Mintaka", 5.5334, -0.299, 2.23),
                FigureStar("kappa", "Saiph", 5.7959, -9.670, 2.06),
                FigureStar("beta", "Rigel", 5.2423, -8.202, 0.12),
            ),
            strokes = listOf(
                listOf("gamma", "lambda", "alpha", "gamma"),
                listOf("alpha", "zeta", "epsilon", "delta", "gamma"),
                listOf("zeta", "kappa", "beta", "delta"),
            ),
        ),
        "Ursa Major" to Figure(
            stars = listOf(
                FigureStar("eta", "Alkaid", 13.7923, 49.313, 1.86),
                FigureStar("zeta", "Mizar", 13.3987, 54.925, 2.27),
                FigureStar("epsilon", "Alioth", 12.9005, 55.960, 1.77),
                FigureStar("delta", "Megrez", 12.2571, 57.032, 3.31),
                FigureStar("alpha", "Dubhe", 11.0621, 61.751, 1.79),
                FigureStar("beta", "Merak", 11.0307, 56.383, 2.37),
                FigureStar("gamma", "Phecda", 11.8972, 53.695, 2.44),
            ),
            strokes = listOf(
                listOf("eta", "zeta", "epsilon", "delta", "alpha", "beta", "gamma", "delta"),
            ),
        ),
        "Cassiopeia" to Figure(
            stars = listOf(
                FigureStar("beta", "Caph", 0.1530, 59.150, 2.27),
                FigureStar("alpha", "Schedar", 0.6751, 56.537, 2.23),
                FigureStar("gamma", "Navi", 0.9451, 60.717, 2.47),
                FigureStar("delta", "Ruchbah", 1.4303, 60.235, 2.68),
                FigureStar("epsilon", "Segin", 1.9066, 63.670, 3.38),
            ),
            strokes = listOf(
                listOf("beta", "alpha", "gamma", "delta", "epsilon"),
            ),
        ),
        "Cygnus" to Figure(
            stars = listOf(
                FigureStar("alpha", "Deneb", 20.6905, 45.280, 1.25),
                FigureStar("gamma", "Sadr", 20.3705, 40.257, 2.20),
                FigureStar("beta", "Albireo", 19.5120, 27.960, 3.08),
                FigureStar("delta", "Al Fawaris", 19.7496, 45.131, 2.87),
                FigureStar("epsilon", null, 20.7702, 33.970, 2.46),
                FigureStar("zeta", null, 21.2156, 30.227, 3.20),
            ),
            strokes = listOf(
                listOf("alpha", "gamma", "beta"),
                listOf("delta", "gamma", "epsilon", "zeta"),
            ),
        ),
        "Lyra" to Figure(
            stars = listOf(
                FigureStar("alpha", "Vega", 18.6156, 38.784, 0.03),
                FigureStar("epsilon", null, 18.7390, 39.670, 4.67),
                FigureStar("zeta", null, 18.7462, 37.605, 4.36),
                FigureStar("delta", null, 18.9084, 36.899, 4.30),
                FigureStar("gamma", "Sulafat", 18.9824, 32.689, 3.24),
                FigureStar("beta", "Sheliak", 18.8347, 33.363, 3.45),
            ),
            strokes = listOf(
                listOf("alpha", "epsilon", "zeta", "alpha"),
                listOf("zeta", "delta", "gamma", "beta", "zeta"),
            ),
        ),
        "Aquila" to Figure(
            stars = listOf(
                FigureStar("gamma", "Tarazed", 19.7710, 10.613, 2.72),
                FigureStar("alpha", "Altair", 19.8464, 8.868, 0.77),
                FigureStar("beta", "Alshain", 19.9219, 6.407, 3.71),
                FigureStar("delta", null, 19.4250, 3.115, 3.36),
                FigureStar("lambda", "Al Thalimain", 19.1041, -4.883, 3.44),
                FigureStar("zeta", "Deneb el Okab", 19.0902, 13.863, 2.99),
                FigureStar("theta", null, 20.1884, -0.821, 3.23),
            ),
            strokes = listOf(
                listOf("gamma", "alpha", "beta"),
                listOf("alpha", "delta", "lambda"),
                listOf("zeta", "delta", "theta"),
            ),
        ),
        "Scorpius" to Figure(
            stars = listOf(
                FigureStar("beta", "Acrab", 16.0906, -19.806, 2.62),
                FigureStar("delta", "Dschubba", 16.0056, -22.622, 2.32),
                FigureStar("pi", null, 15.9809, -26.114, 2.89),
                FigureStar("sigma", "Al Niyat", 16.3531, -25.593, 2.89),
                FigureStar("alpha", "Antares", 16.4901, -26.432, 0.96),
                FigureStar("tau", "Alniyat", 16.5981, -28.216, 2.82),
                FigureStar("epsilon", null, 16.8361, -34.293, 2.29),
                FigureStar("mu1", null, 16.8645, -38.047, 3.08),
                FigureStar("zeta2", null, 16.9097, -42.361, 3.62),
                FigureStar("eta", null, 17.2026, -43.239, 3.33),
                FigureStar("theta", "Sargas", 17.6220, -42.998, 1.87),
                FigureStar("iota1", null, 17.7931, -40.127, 3.03),
                FigureStar("kappa", "Girtab", 17.7081, -39.030, 2.41),
                FigureStar("lambda", "Shaula", 17.5601, -37.104, 1.63),
                FigureStar("upsilon", "Lesath", 17.5127, -37.296, 2.69),
            ),
            strokes = listOf(
                listOf("beta", "delta", "pi"),
                listOf("delta", "sigma", "alpha", "tau", "epsilon", "mu1", "zeta2", "eta", "theta", "iota1", "kappa", "lambda", "upsilon"),
            ),
        ),
        "Sagittarius" to Figure(
            stars = listOf(
                FigureStar("epsilon", "Kaus Australis", 18.4029, -34.385, 1.85),
                FigureStar("gamma2", "Alnasl", 18.0968, -30.424, 2.99),
                FigureStar("delta", "Kaus Media", 18.3499, -29.828, 2.70),
                FigureStar("lambda", "Kaus Borealis", 18.4662, -25.422, 2.81),
                FigureStar("phi", null, 18.7609, -26.991, 3.17),
                FigureStar("sigma", "Nunki", 18.9211, -26.297, 2.02),
                FigureStar("tau", null, 19.1157, -27.671, 3.32),
                FigureStar("zeta", "Ascella", 19.0435, -29.880, 2.60),
            ),
            strokes = listOf(
                listOf("epsilon", "gamma2", "delta", "epsilon"),
                listOf("delta", "lambda", "phi", "delta"),
                listOf("phi", "sigma", "tau", "zeta", "phi"),
                listOf("epsilon", "zeta"),
            ),
        ),
        "Taurus" to Figure(
            stars = listOf(
                FigureStar("gamma", "Hyadum I", 4.3299, 15.628, 3.65),
                FigureStar("delta", "Hyadum II", 4.3822, 17.543, 3.76),
                FigureStar("epsilon", "Ain", 4.4769, 19.180, 3.53),
                FigureStar("beta", "Elnath", 5.4382, 28.608, 1.65),
                FigureStar("theta2", null, 4.4777, 15.871, 3.40),
                FigureStar("alpha", "Aldebaran", 4.5987, 16.509, 0.85),
                FigureStar("zeta", "Tien Kwan", 5.6274, 21.142, 3.00),
            ),
            strokes = listOf(
                listOf("gamma", "delta", "epsilon", "beta"),
                listOf("gamma", "theta2", "alpha", "zeta"),
            ),
        ),
        "Gemini" to Figure(
            stars = listOf(
                FigureStar("alpha", "Castor", 7.5767, 31.888, 1.98),
                FigureStar("beta", "Pollux", 7.7553, 28.026, 1.14),
                FigureStar("tau", null, 7.1857, 30.245, 4.41),
                FigureStar("epsilon", "Mebsuta", 6.7322, 25.131, 2.98),
                FigureStar("mu", "Tejat Posterior", 6.3827, 22.514, 2.88),
                FigureStar("eta", "Propus", 6.2479, 22.507, 3.28),
                FigureStar("delta", "Wasat", 7.3354, 21.982, 3.53),
                FigureStar("zeta", "Mekbuda", 7.0685, 20.570, 3.79),
                FigureStar("gamma", "Alhena", 6.6285, 16.399, 1.93),
            ),
            strokes = listOf(
                listOf("alpha", "beta"),
                listOf("alpha", "tau", "epsilon", "mu", "eta"),
                listOf("beta", "delta", "zeta", "gamma"),
            ),
        ),
        "Canis Major" to Figure(
            stars = listOf(
                FigureStar("beta", "Mirzam", 6.3783, -17.956, 1.98),
                FigureStar("alpha", "Sirius", 6.7525, -16.716, -1.46),
                FigureStar("delta", "Wezen", 7.1399, -26.393, 1.84),
                FigureStar("eta", "Aludra", 7.4016, -29.303, 2.45),
                FigureStar("epsilon", "Adhara", 6.9771, -28.972, 1.50),
                FigureStar("zeta", "Furud", 6.3386, -30.063, 3.02),
            ),
            strokes = listOf(
                listOf("beta", "alpha", "delta", "eta"),
                listOf("delta", "epsilon", "zeta"),
            ),
        ),
        "Canis Minor" to Figure(
            stars = listOf(
                FigureStar("alpha", "Procyon", 7.6550, 5.225, 0.38),
                FigureStar("beta", "Gomeisa", 7.4525, 8.289, 2.90),
            ),
            strokes = listOf(
                listOf("alpha", "beta"),
            ),
        ),
        "Auriga" to Figure(
            stars = listOf(
                FigureStar("alpha", "Capella", 5.2782, 45.998, 0.08),
                FigureStar("beta", "Menkalinan", 5.9921, 44.947, 1.90),
                FigureStar("theta", "Mahasim", 5.9954, 37.213, 2.62),
                FigureStar("beta-tau", "Elnath", 5.4382, 28.608, 1.65, borrowedFrom = "Taurus"),
                FigureStar("iota", "Kabdhilinan", 4.9499, 33.166, 2.69),
            ),
            strokes = listOf(
                listOf("alpha", "beta", "theta", "beta-tau", "iota", "alpha"),
            ),
        ),
        "Leo" to Figure(
            stars = listOf(
                FigureStar("alpha", "Regulus", 10.1395, 11.967, 1.35),
                FigureStar("eta", null, 10.1222, 16.763, 3.52),
                FigureStar("gamma", "Algieba", 10.3329, 19.842, 2.61),
                FigureStar("zeta", "Adhafera", 10.2782, 23.417, 3.44),
                FigureStar("mu", "Rasalas", 9.8794, 26.007, 3.88),
                FigureStar("epsilon", "Ras Elased Australis", 9.7642, 23.774, 2.98),
                FigureStar("delta", "Zosma", 11.2351, 20.524, 2.56),
                FigureStar("beta", "Denebola", 11.8177, 14.572, 2.14),
                FigureStar("theta", "Chertan", 11.2373, 15.429, 3.34),
            ),
            strokes = listOf(
                listOf("alpha", "eta", "gamma", "zeta", "mu", "epsilon"),
                listOf("gamma", "delta", "beta", "theta", "alpha"),
            ),
        ),
        "Bootes" to Figure(
            stars = listOf(
                FigureStar("alpha", "Arcturus", 14.2610, 19.183, -0.04),
                FigureStar("epsilon", "Izar", 14.7498, 27.074, 2.70),
                FigureStar("delta", null, 15.2584, 33.315, 3.47),
                FigureStar("beta", "Nekkar", 15.0324, 40.391, 3.50),
                FigureStar("gamma", "Seginus", 14.5346, 38.308, 3.03),
                FigureStar("rho", null, 14.5305, 30.371, 3.58),
                FigureStar("eta", "Muphrid", 13.9114, 18.398, 2.68),
            ),
            strokes = listOf(
                listOf("alpha", "epsilon", "delta", "beta", "gamma", "rho", "alpha"),
                listOf("alpha", "eta"),
            ),
        ),
        "Virgo" to Figure(
            stars = listOf(
                FigureStar("alpha", "Spica", 13.4199, -11.161, 0.98),
                FigureStar("theta", null, 13.1658, -5.539, 4.38),
                FigureStar("gamma", "Porrima", 12.6943, -1.449, 3.65),
                FigureStar("eta", "Zaniah", 12.3318, -0.667, 3.89),
                FigureStar("beta", "Zavijava", 11.8449, 1.765, 3.61),
                FigureStar("delta", "Auva", 12.9267, 3.397, 3.38),
                FigureStar("epsilon", "Vindemiatrix", 13.0363, 10.959, 2.83),
                FigureStar("zeta", "Heze", 13.5782, -0.596, 3.37),
            ),
            strokes = listOf(
                listOf("alpha", "theta", "gamma", "eta", "beta"),
                listOf("gamma", "delta", "epsilon"),
                listOf("alpha", "zeta", "delta"),
            ),
        ),
        "Perseus" to Figure(
            stars = listOf(
                FigureStar("eta", "Miram", 2.8449, 55.896, 3.76),
                FigureStar("gamma", null, 3.0799, 53.506, 2.93),
                FigureStar("alpha", "Mirfak", 3.4054, 49.861, 1.79),
                FigureStar("delta", null, 3.7154, 47.788, 3.01),
                FigureStar("epsilon", null, 3.9642, 40.010, 2.89),
                FigureStar("zeta", null, 3.9022, 31.884, 2.85),
                FigureStar("kappa", "Misam", 3.1583, 44.857, 3.80),
                FigureStar("beta", "Algol", 3.1361, 40.956, 2.12),
                FigureStar("rho", "Gorgonea Tertia", 3.0863, 38.840, 3.39),
            ),
            strokes = listOf(
                listOf("eta", "gamma", "alpha", "delta", "epsilon", "zeta"),
                listOf("alpha", "kappa", "beta", "rho"),
            ),
        ),
        "Andromeda" to Figure(
            stars = listOf(
                FigureStar("alpha", "Alpheratz", 0.1398, 29.091, 2.06),
                FigureStar("delta", null, 0.6555, 30.861, 3.27),
                FigureStar("beta", "Mirach", 1.1622, 35.621, 2.06),
                FigureStar("gamma", "Almach", 2.0650, 42.330, 2.26),
                FigureStar("mu", null, 0.9459, 38.499, 3.87),
                FigureStar("nu", null, 0.8302, 41.079, 4.53),
            ),
            strokes = listOf(
                listOf("alpha", "delta", "beta", "gamma"),
                listOf("beta", "mu", "nu"),
            ),
        ),
        "Pegasus" to Figure(
            stars = listOf(
                FigureStar("alpha", "Markab", 23.0794, 15.205, 2.49),
                FigureStar("beta", "Scheat", 23.0629, 28.083, 2.42),
                FigureStar("alpha-and", "Alpheratz", 0.1398, 29.091, 2.06, borrowedFrom = "Andromeda"),
                FigureStar("gamma", "Algenib", 0.2206, 15.184, 2.83),
                FigureStar("zeta", "Homam", 22.6910, 10.831, 3.40),
                FigureStar("theta", "Biham", 22.1700, 6.198, 3.53),
                FigureStar("epsilon", "Enif", 21.7364, 9.875, 2.39),
            ),
            strokes = listOf(
                listOf("alpha", "beta", "alpha-and", "gamma", "alpha"),
                listOf("alpha", "zeta", "theta", "epsilon"),
            ),
        ),
        "Ursa Minor" to Figure(
            stars = listOf(
                FigureStar("alpha", "Polaris", 2.5302, 89.264, 2.02),
                FigureStar("delta", "Yildun", 17.5369, 86.586, 4.36),
                FigureStar("epsilon", null, 16.7661, 82.037, 4.23),
                FigureStar("zeta", null, 15.7343, 77.794, 4.32),
                FigureStar("beta", "Kochab", 14.8451, 74.156, 2.08),
                FigureStar("gamma", "Pherkad", 15.3455, 71.834, 3.05),
                FigureStar("eta", null, 16.2918, 75.755, 4.95),
            ),
            strokes = listOf(
                listOf("alpha", "delta", "epsilon", "zeta", "beta", "gamma", "eta", "zeta"),
            ),
        ),
        "Crux" to Figure(
            stars = listOf(
                FigureStar("alpha", "Acrux", 12.4433, -63.099, 0.77),
                FigureStar("gamma", "Gacrux", 12.5194, -57.113, 1.63),
                FigureStar("beta", "Mimosa", 12.7953, -59.689, 1.25),
                FigureStar("delta", null, 12.2524, -58.749, 2.80),
            ),
            strokes = listOf(
                listOf("alpha", "gamma"),
                listOf("beta", "delta"),
            ),
        ),
        "Centaurus" to Figure(
            stars = listOf(
                FigureStar("alpha", "Rigil Kentaurus", 14.6600, -60.835, -0.27),
                FigureStar("beta", "Hadar", 14.0637, -60.373, 0.61),
                FigureStar("epsilon", null, 13.6648, -53.466, 2.30),
                FigureStar("zeta", null, 13.9257, -47.288, 2.55),
                FigureStar("theta", "Menkent", 14.1114, -36.370, 2.06),
                FigureStar("gamma", null, 12.6919, -48.960, 2.17),
            ),
            strokes = listOf(
                listOf("alpha", "beta", "epsilon", "zeta", "theta"),
                listOf("epsilon", "gamma"),
            ),
        ),
    )
}
