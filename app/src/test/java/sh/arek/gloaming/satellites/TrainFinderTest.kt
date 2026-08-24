package sh.arek.gloaming.satellites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A train as one thing to look at rather than two satellites.
 *
 * The document publishes only the two ends of a launch, and this is where they
 * become a single pass: it begins when the front of the train clears the
 * horizon and it is not over until the back of it has gone. Everything after
 * this - the rate limiting, the choosing, the notification - treats it as any
 * other pass, which is the point of building it into one.
 */
class TrainFinderTest {

    /** Two satellites a few minutes apart along one orbit, over Edinburgh. */
    private val lead = SatelliteElements(
        name = "STARLINK-101000",
        catalogue = 101000,
        epoch = 21320.51955234,
        epochMillis = 1637065689322L,
        meanMotion = 15.48582035,
        eccentricity = 0.0004694,
        inclination = 51.6447,
        raan = 309.4881,
        argPerigee = 203.6966,
        meanAnomaly = 299.8876,
        bstar = 0.000031985,
        meanMotionDot = 0.00001288,
    )

    /** Two degrees of mean anomaly behind, which is half a minute of orbit. */
    private val tail = lead.copy(
        name = "STARLINK-101007",
        catalogue = 101007,
        meanAnomaly = 297.8876,
    )

    private val latitude = 55.9533
    private val longitude = -3.1883
    private val from = 1637065689322L
    private val until = from + 24 * 60 * 60 * 1000L

    private val document = SatellitesDocument(
        schema = 1,
        updated = "2026-08-22T12:00:00.000Z",
        satellites = listOf(lead, tail),
        trains = listOf(StarlinkTrain(launch = "2026-123", members = 22, lead = 101000, tail = 101007)),
    )

    private fun found(source: SatellitesDocument = document) =
        TrainFinder.passes(source, latitude, longitude, from, until)

    @Test
    fun `runs from the front of the train rising to the back of it setting`() {
        val train = found().first()
        val leadPass = PassFinder.passes(lead, latitude, longitude, from, until).first()
        val tailPass = PassFinder.passes(tail, latitude, longitude, from, until).first()

        assertEquals(leadPass.risesAt, train.risesAt)
        assertEquals(tailPass.setsAt, train.setsAt)
        assertTrue(train.setsAt > leadPass.setsAt)
    }

    @Test
    fun `announces a train on its own channel and says how many`() {
        val train = found().first()

        assertEquals(PassChannel.Trains, train.channel)
        assertEquals(22, train.members)
        assertEquals(101000, train.catalogue)
    }

    /**
     * The geometry is the lead's. Every member crosses the same piece of sky a
     * few seconds apart, so how high the train gets is how high the front of it
     * gets, and the description can be built from that without qualification.
     */
    @Test
    fun `takes its height and direction from the front of the train`() {
        val train = found().first()
        val leadPass = PassFinder.passes(lead, latitude, longitude, from, until).first()

        assertEquals(leadPass.peakElevation, train.peakElevation, 1e-9)
        assertEquals(leadPass.peakAzimuth, train.peakAzimuth, 1e-9)
        assertEquals(leadPass.riseAzimuth, train.riseAzimuth, 1e-9)
    }

    @Test
    fun `finds nothing for a document with no trains in it`() {
        assertEquals(emptyList<SatellitePass>(), found(document.copy(trains = emptyList())))
    }

    /**
     * A train whose ends are not both in the document cannot be described - the
     * sentence is precisely "rising then, the last clear by then" - and saying
     * half of it would be worse than saying nothing.
     */
    @Test
    fun `says nothing about a train whose ends are not both published`() {
        val missing = document.copy(satellites = listOf(lead))

        assertEquals(emptyList<SatellitePass>(), found(missing))
    }
}
