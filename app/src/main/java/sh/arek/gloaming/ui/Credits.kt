package sh.arek.gloaming.ui

/** A link a credit is required to carry. */
data class CreditLink(val label: String, val url: String)

/**
 * One credit: who is owed it, what for, and on what terms.
 *
 * @property licence the full licence text, where the licence requires it to
 *   travel with the software rather than merely be named. Null where naming the
 *   terms and the author discharges the obligation.
 */
data class Credit(
    val name: String,
    val body: String,
    val links: List<CreditLink> = emptyList(),
    val licence: String? = null,
)

/**
 * The MIT notice for the vendored astronomy code, verbatim.
 *
 * MIT requires this notice in all copies of the software, and an installed APK
 * is a copy. Keeping it beside the vendored source in the repository does not
 * discharge that - nobody who installs the app ever sees the repository. Held
 * as a string rather than a resource so a unit test can compare it against the
 * vendored file and catch it going stale on an upgrade.
 */
const val ASTRONOMY_ENGINE_LICENCE: String = """MIT License

Copyright (c) 2019-2023 Don Cross <cosinekitty@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

/**
 * Who the app owes credit to right now.
 *
 * @param fromAuroraWatch whether the reading currently on screen came from the
 *   UK magnetometer layer. Their terms tie the credit to their data, so it
 *   appears with their reading and not with a global-grid one.
 */
fun credits(fromAuroraWatch: Boolean): List<Credit> = buildList {
    if (fromAuroraWatch) {
        add(
            Credit(
                name = "AuroraWatch UK",
                // Their sentence, unedited. Not a paraphrase, and never
                // described as official - both are conditions of use.
                body = "AuroraWatch UK alerts data is provided by Lancaster University, " +
                    "UK using data from the SAMNET and/or AuroraWatchNet magnetometer " +
                    "networks.",
                // SAMNET and AuroraWatchNet are named above but not linked: the
                // pages their API still advertises for the two networks are
                // gone, and a link to a 404 credits nobody. Re-checked
                // 2026-08-30; AuroraWatch UK's own site has stopped linking
                // them too, so they are withdrawn rather than moved.
                //
                // The terms get a link because they cannot get a sentence. Every
                // other credit here names its licence in its own words, and this
                // body is theirs unedited, so the one place left to say the data
                // is not for commercial use is a link to where they say it.
                links = listOf(
                    CreditLink("AuroraWatch UK", "https://aurorawatch.lancs.ac.uk/"),
                    CreditLink("Lancaster University", "https://www.lancaster.ac.uk/"),
                    CreditLink(
                        "Conditions of use",
                        "https://aurorawatch.lancs.ac.uk/conditionsofuse/",
                    ),
                ),
            ),
        )
    }

    add(
        Credit(
            name = "NOAA Space Weather Prediction Center",
            body = "The OVATION aurora model behind every reading outside the UK. " +
                "Public domain, and credited because it is the backbone of the app.",
            links = listOf(CreditLink("NOAA SWPC", "https://www.swpc.noaa.gov/")),
        ),
    )

    add(
        Credit(
            name = "Open-Meteo",
            body = "Cloud forecasts, under CC BY 4.0.",
            links = listOf(CreditLink("Open-Meteo.com", "https://open-meteo.com/")),
        ),
    )

    add(
        Credit(
            name = "Minor Planet Center",
            body = "The orbital elements every comet in this app is computed from. The " +
                "IAU's clearing house for small bodies, operating at the Smithsonian " +
                "Astrophysical Observatory and funded by NASA.",
            links = listOf(
                CreditLink("Minor Planet Center", "https://www.minorplanetcenter.net/"),
            ),
        ),
    )

    add(
        Credit(
            name = "COBS Comet Observation Database",
            // Their own suggested wording, and the licence named in full. The
            // brightness this app quotes for a comet is not a calculation - it
            // is thousands of people going outside and looking, which is the
            // one thing here no amount of arithmetic could replace.
            body = "Credit: COBS Comet Observation Database - CC BY-NC-SA 4.0. Run by " +
                "Crni Vrh Observatory in Slovenia since 2010. Every measured comet " +
                "brightness in this app comes from volunteer observers worldwide who " +
                "reported what they saw; where nobody has looked recently, the app says " +
                "it is quoting a prediction instead.",
            links = listOf(
                CreditLink("COBS", "https://cobs.si/"),
                CreditLink("Data usage guidelines", "https://cobs.si/help/data_policy/"),
                CreditLink(
                    "CC BY-NC-SA 4.0",
                    "https://creativecommons.org/licenses/by-nc-sa/4.0/",
                ),
            ),
        ),
    )

    add(
        Credit(
            name = "astronomy-engine",
            body = "Don Cross's astronomy library, which computes the darkness windows " +
                "and the moon phase on this phone. Vendored into the app under the MIT " +
                "licence.",
            links = listOf(
                CreditLink("astronomy-engine", "https://github.com/cosinekitty/astronomy"),
            ),
            licence = ASTRONOMY_ENGINE_LICENCE,
        ),
    )

    add(
        Credit(
            name = "CelesTrak",
            body = "Dr T.S. Kelso's orbital elements, which every satellite pass in this " +
                "app is computed from. A non-profit, funded by nobody and charging " +
                "nothing.",
            links = listOf(CreditLink("CelesTrak", "https://celestrak.org/")),
        ),
    )

    add(
        Credit(
            name = "Look4Sat",
            body = "Arty Bishop's satellite tracker, whose SGP4/SDP4 propagator computes " +
                "every pass this app predicts. Vendored under the GPL-3.0, the same " +
                "licence this app carries.",
            // No licence text here, unlike the MIT credit above: GPL-3 asks for
            // the corresponding source rather than a notice in every copy, and
            // the source link on this screen is what provides it.
            links = listOf(
                CreditLink("Look4Sat", "https://github.com/rt-bishop/Look4Sat"),
            ),
        ),
    )
}
