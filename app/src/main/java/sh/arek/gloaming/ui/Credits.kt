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
 * The SIL Open Font Licence for the bundled typeface, verbatim from the
 * upstream release (`app/src/main/fonts/OFL.txt` holds the same file).
 *
 * The OFL requires its full text and the copyright line to accompany the font
 * wherever it is redistributed, and an installed APK is a redistribution.
 * Held as a string for the same reason as the MIT notice above.
 */
const val ATKINSON_LICENCE: String = """Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors (https://github.com/googlefonts/atkinson-hyperlegible-next)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://openfontlicense.org


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE."""

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
            name = "Atkinson Hyperlegible Next",
            body = "The typeface every word in this app is set in, drawn by the Braille " +
                "Institute for readers with low vision. Bundled under the SIL Open " +
                "Font Licence 1.1.",
            links = listOf(
                CreditLink(
                    "Atkinson Hyperlegible Next",
                    "https://github.com/googlefonts/atkinson-hyperlegible-next",
                ),
            ),
            licence = ATKINSON_LICENCE,
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
