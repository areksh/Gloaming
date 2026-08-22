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
                // gone, and a link to a 404 credits nobody.
                links = listOf(
                    CreditLink("AuroraWatch UK", "https://aurorawatch.lancs.ac.uk/"),
                    CreditLink("Lancaster University", "https://www.lancaster.ac.uk/"),
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
}
