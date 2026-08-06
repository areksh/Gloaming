package sh.arek.gloaming.aurora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.AuroraWatchStatus

class LikelihoodTest {

    private fun watch(status: String) = AuroraWatchStatus(status, updated = "u", siteId = "s")

    private fun evaluate(
        latitude: Double,
        longitude: Double,
        ovation: Int,
        aurorawatch: AuroraWatchStatus? = null,
    ) = Likelihood.evaluate(latitude, longitude, ovation, aurorawatch)

    @Test
    fun `uses the global grid outside the UK`() {
        val result = evaluate(latitude = 69.65, longitude = 18.96, ovation = 40, aurorawatch = watch("red"))

        assertEquals(40, result.probability)
        assertEquals(Likelihood.Source.OVATION, result.source)
    }

    @Test
    fun `uses the global grid inside the UK when there is no UK reading`() {
        val result = evaluate(latitude = 55.95, longitude = -3.19, ovation = 40)

        assertEquals(40, result.probability)
        assertEquals(Likelihood.Source.OVATION, result.source)
    }

    /**
     * The quality bump. AuroraWatch runs on real UK magnetometer data rather
     * than a planetary index, and their own position is that Kp is a poor
     * indicator for the UK specifically - so inside the box their reading can
     * raise the answer the global grid gives.
     */
    @Test
    fun `lets a UK reading raise the global grid`() {
        val result = evaluate(55.95, -3.19, ovation = 20, aurorawatch = watch("red"))

        assertEquals(Likelihood.forLevel("red"), result.probability)
        assertEquals(Likelihood.Source.AURORAWATCH, result.source)
    }

    /**
     * But never lower it. AuroraWatch's alerts come from a single alerting site
     * that defaults to the Lancaster magnetometer, so they get steadily more
     * pessimistic further north - and Shetland is where the aurora actually is.
     * A green status must not silence a grid that says otherwise.
     */
    @Test
    fun `never lets a UK reading suppress the global grid`() {
        val result = evaluate(60.15, -1.15, ovation = 65, aurorawatch = watch("green"))

        assertEquals(65, result.probability)
        assertEquals(Likelihood.Source.OVATION, result.source)
    }

    @Test
    fun `ranks the four levels in the order AuroraWatch publishes them`() {
        val levels = listOf("green", "yellow", "amber", "red").map { Likelihood.forLevel(it)!! }

        assertEquals(levels.sorted(), levels)
        assertEquals(levels.distinct(), levels)
    }

    // An unrecognised level must not read as zero, which would be a claim the
    // app has no basis for. It falls through to the grid instead.
    @Test
    fun `ignores a level it does not recognise`() {
        val result = evaluate(55.95, -3.19, ovation = 30, aurorawatch = watch("chartreuse"))

        assertEquals(30, result.probability)
        assertEquals(Likelihood.Source.OVATION, result.source)
    }

    @Test
    fun `covers the whole of the UK`() {
        // Scilly, Shetland, the Outer Hebrides, Lowestoft, Belfast.
        assertTrue(Likelihood.insideUk(49.93, -6.32))
        assertTrue(Likelihood.insideUk(60.85, -0.88))
        assertTrue(Likelihood.insideUk(57.75, -7.15))
        assertTrue(Likelihood.insideUk(52.48, 1.75))
        assertTrue(Likelihood.insideUk(54.60, -5.93))
    }

    @Test
    fun `does not claim anywhere it should not`() {
        assertFalse("Tromso", Likelihood.insideUk(69.65, 18.96))
        assertFalse("Reykjavik", Likelihood.insideUk(64.15, -21.94))
        assertFalse("Paris", Likelihood.insideUk(48.86, 2.35))
        assertFalse("Torshavn", Likelihood.insideUk(62.01, -6.77))
        assertFalse("Hobart", Likelihood.insideUk(-42.88, 147.33))
    }

    /**
     * Before first unlock the position is rounded to a whole degree, so the box
     * has to still contain the places it contains at full precision. Shetland
     * at 60.15 rounds to 60 and Scilly at 49.93 to 50 - both would fall out of
     * a box drawn tightly around the coastline.
     */
    @Test
    fun `still covers the UK once the position is rounded to a whole degree`() {
        assertTrue("Shetland", Likelihood.insideUk(60.0, -1.0))
        assertTrue("Scilly", Likelihood.insideUk(50.0, -6.0))
        assertTrue("Edinburgh", Likelihood.insideUk(56.0, -3.0))
    }
}
