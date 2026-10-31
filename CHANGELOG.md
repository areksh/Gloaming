# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Tonight opens with one sentence about the night.** Under the date, a
  single line says whether tonight is worth going out for and when - "Cloudy
  until 23:40, then three clear hours. Go out at midnight for Saturn and
  Tiangong." - with a second line under it, and it only names what will
  actually be seen. Above it, one quiet line says when Gloaming last checked
  and when it next will, and names anything that has gone wrong; tapping it
  opens a checks sheet holding Check now, the downloads report, the last
  error, battery saver and the privacy note, which no longer sit among the
  sky. Pull Tonight down to check now. When background checks or
  notifications are not allowed, a banner above that line says so and offers
  the way to allow them. Watch now is a "Watch tonight" button pinned at the foot
  of the screen: it offers the choice of how long in a sheet, and while a
  watch runs it says until when and stops on a tap.
- **A bottom bar.** Tonight, Ahead and Settings now sit on a bar at the foot
  of the screen, within thumb's reach. The calendar is now called Ahead, and
  the guide is no longer a tab of its own: it moves under Settings, as "Guide
  to the words".
- **A new look, in light and dark.** Gloaming has its own palette now: a dark
  slate ground with the pink of the Belt of Venus as its accent by night, and a
  light theme that follows the phone's setting for use in daylight. Everything
  is set in Atkinson Hyperlegible Next, a typeface designed for legibility,
  with figures that line up in columns of times; it is bundled with the app
  under the SIL Open Font Licence, credited in full under Credits and
  licences. Night vision is unchanged: pure red on black. Wallpaper-derived
  colour (Android 12 and later) has been removed to make this possible, since
  the palette is now the app's own.
- **You choose how often the phone's approximate position is refreshed.** A
  detected position used to be read only when asked, so a phone carried to a
  dark site went on being judged at home - darkness, cloud and every pass -
  until somebody pressed the button. The location card now offers each time
  Gloaming opens (the default), every hour, every 6 hours, once a day, any
  whole number of hours from one to forty-eight, or only on Update now, and
  says when the position was last updated. The intervals refresh it on an
  alarm of their own: one coarse fix per wake from the network provider, or
  the platform's fused provider where that is off (Android 12 and later),
  never GPS, never logged and never sent. They need Android's "Allow all the
  time" location grant, which is asked for only when one is picked, and the
  card warns that Android's page will offer precise location too; if it is
  refused, or later taken away, the app goes back to refreshing when it is
  opened and the card says why. A refresh due while the phone is locked after
  a reboot waits for the unlock. ADR-0016 records why the app now declares a
  permission it once promised never to.

- **A clear spell no longer closes on high thin cloud.** Tonight's clear spell
  was judged on total cloud, so 70% cirrus over an otherwise clear sky counted
  as closed to a planet or a pass that shines straight through it. The hourly
  forecast now carries the low, mid and high layers on the same single
  Open-Meteo request, and a spell is judged on the worse of low and mid cloud or
  half the high cloud. An older cached forecast without layers is judged on its
  total, as before.

- **The log can be kept for any number of days up to a year.** The retention
  control offered 1, 3, 7 and 14 days, which suits nobody chasing a fault that
  only shows up once a month. A Custom choice takes any whole number of days
  from 1 to 365 and says so plainly when the number is outside that; a stored
  value beyond a year is held to a year on read.

- **The journal says when a poll was killed, and when a wake arrived late.**
  Two things it could previously only imply. A `goAsync` broadcast is killed at
  sixty seconds without running its `finally`, so a killed poll wrote whichever
  stage lines it had reached and then stopped - which reads exactly like a poll
  that had nothing to say. On 2026-09-21 that hid a real one: a wake logged its
  aurora outcome, neither of the two checks after it, and the near-zenith pass
  it had woken up to announce was never mentioned. Separately, an inexact alarm
  lands late by design and `Delivery` documents the band, but a wake far outside
  it - 167 minutes against a 30 minute request, on 2026-09-16 - was recorded
  nowhere. Each wake now leaves a small device-encrypted record of what it armed
  and whether it finished, and the next wake reads it. Every path that arms an
  alarm writes it - the poll, the button, the boot receiver, the settings
  screen - so a wake is always measured against the request that actually
  produced it. Neither line is written
  when nothing went wrong, so a quiet night costs no extra journal; when one is
  written it carries the app standby bucket and the battery-optimisation
  exemption, which are current state with no history anywhere and so cannot be
  recovered by the following morning.

- **A failed reschedule is said out loud.** Both calls that arm the next wake
  swallowed their failures silently, which is the one fault that ends the
  schedule outright: no alarm pending, no line anywhere, and nothing until a
  reboot or an app open.

### Changed

- **The CelesTrak breaker clears itself after two days.** It used to reset only
  by hand, so an unread alert left elements frozen for as long as nobody looked:
  on 2026-08-25 a 503 went unnoticed for seventeen hours, and a trip before a
  week away would have run to a week. Now, if nobody has cleared it within two
  days of the trip, the next run lets one request through. A 200 clears the flag
  and publishes as normal; anything else trips it afresh for another two days
  and pushes `breaker tripped again`. Every trip alert says when the next
  attempt will be, and clearing it by hand still works at once (ADR-0015).

- **A pass that goes overhead is told as overhead, not given a compass bearing.**
  Azimuth is ill-conditioned near the zenith, where the whole compass converges,
  so the bearing of the highest point is the one number in a pass that a
  fraction of a second of timing can move by degrees. Observed on two phones on
  2026-09-22: the same 89.2 degree pass, re-evaluated six times in the fourteen
  minutes before it was announced, put its peak anywhere between 97.3 and 102.1
  degrees, across a compass sector boundary, while the rise and set azimuths of
  those same evaluations agreed to five decimal places. It was also the wrong
  advice: at ten degrees off the zenith there is no "east" about it, and
  somebody sent to face east looks out rather than up. Above 80 degrees the
  alert now reads "passing overhead" and gives no bearing for the peak. The rise
  and set bearings say where to stand and are unchanged.

### Fixed

- **`./go status` no longer reports a release build as a phone that has never
  polled.** It reads most of its evidence out of the app's own storage through
  `run-as`, which works only on a debuggable build; against a release APK every
  read comes back empty, and empty was reported as "no poll has ever completed
  on this device" - three confident failures about a phone that had been polling
  all week. It now detects the refusal, names it once, and marks the checks it
  could not make as skipped. The checks that come from `dumpsys` rather than
  from the app - the pending alarm, the standby bucket, the exemption - are
  still answered. For a harness that exists to avoid giving a false answer,
  reporting "I could not look" as "there is nothing there" was the one bug it
  must not have.

## [1.0.0-rc2] - 2026-09-16

### Added

- **`doze-check.sh` can borrow a sky worth watching.** The harness measures how
  late Doze delivers a wake, and it can only do that while the app is waking at
  all - which `PollingPolicy` decides from the sky, not from the interval in the
  settings. A phone in Britain at eleven in the morning has its next wake nine
  hours out, so the stage measured nothing and said so. `--observer auto` now
  picks the clearest site that is dark for the whole run out of the new
  `tools/observers.txt`, seeds it through the settings screen's own save path,
  waits for one poll to re-plan from it, and puts the phone's own position back
  on exit - including after an interrupt. `tools/pick-observer.sh` does the
  choosing and is worth running alone to see what it would pick. Selection
  applies the app's own two gates, `Twilight.CIVIL_DEGREES` and
  `PollingPolicy.HEAVY_CLOUD`, and asks Open-Meteo for the cloud, the same API
  the app asks.

### Changed

- **The harnesses no longer pretend to put the phone's settings back.** They
  seed a whole `UserSettings` - the fixture writes the object, so every field it
  is not given takes a default - and reading four of those fields back
  afterwards restored four of them. Position, threshold and interval came back;
  minimum elevation, cloud tolerance, the alert leads and quiet hours were reset
  and left reset. A restore covering a quarter of what it overwrites reads as
  though it covers all of it. `./go release` now seeds a fixed observer, which
  is also reproducible - a gate run no longer behaves differently depending on
  where the phone thought it was - and says plainly that it leaves the device
  seeded. The screen timeout and stay-awake are still put back; those are the
  device's, not the app's.

- **`--expect-within` becomes `--expect-late`, measured against the app's own
  request.** The old flag compared the gap to a number typed on the command
  line, which meant picking one that happened to match whatever interval the
  policy was on - and being wrong whenever it moved. The harness now reads
  `maxWhenElapsed` out of `dumpsys alarm` - the deadline the system publishes
  for each alarm, being the app's request plus the window it reserved for an
  inexact one - and fails when a wake is held more than the allowed slack past
  it. Nothing infers an interval, which is the point: the old flag needed a
  number matching whatever interval the policy was on, and there is no such
  number. The window scales with the interval (24 minutes on a 32 minute request
  against seconds on a two minute one), and the interval itself moves inside a
  single run, because the schedule is the minimum of three pollers and an
  approaching satellite pass drops it from half an hour to nine minutes. What
  the allowance really guards against is app standby holding a wake for days
  while the alarm still lists as pending. The alarm is sampled once per watch
  cycle, so the report says what was seen rather than what happened - an
  overshoot shorter than a cycle can slip between two reads, which is the right
  trade when the tolerance is minutes and the quarry is days. `./go release` step 6 accordingly
  borrows an observer and watches for ninety minutes instead of thirty; the gate
  now takes about two hours rather than one.

### Added

- **`doze-check.sh` reads the app's own journal as a second, separate finding.**
  The deadline check audits the platform - did the system fire the alarm by the
  `maxWhenElapsed` it published. This audits the app: each poll records what it
  asked for next, so the gap that followed is read against it, and any pass
  announced during the run carries the pass it is about, so the notice actually
  delivered is a subtraction against the lead in the settings. The two questions
  can disagree, and the second is the one a user would recognise - a wake asked
  for at 28 minutes and delivered at 54 is inside the window the system
  reserved, and is still an alert giving two minutes of warning where it
  promised thirty. Run against the journal from the night this was found, it
  fails that announcement by 20m37s.

### Fixed

- **The satellite sweep can no longer kill a poll, and mostly no longer needs
  to be a whole sweep.** Predicting passes walks 175 objects across a twelve
  hour horizon at thirty second steps. That measures 7.4 seconds on a woken
  Pixel 8a and over 45 in Doze, where the CPU is throttled - past the sixty a
  broadcast gets. The timeout around it never fired, because a tight loop has no
  suspension point for cancellation to land on, so the broadcast was killed
  along with the reschedule and the bookmark that would have let the next poll
  skip the work. It repeated every wake: twelve ANRs in one night, and not one
  completed pass or calendar check, so no satellite or event alert was possible
  at all.

  Two changes. The sweep now gives up its turn at a deadline checked between
  objects, and a sweep that ran out of time says nothing rather than deciding
  from half a catalogue - announcing from a partial list could speak about a
  later pass while an earlier one sat unread, and the channel's rate limit would
  then hold its tongue about the one that mattered. And the near three hours are
  swept first, the rest only when the planner would not choose anything there -
  not merely when nothing is there, since a pass can be present and count for
  nothing, invisible in daylight or still inside its rate limit. The full
  horizon is always swept before the app commits to a long sleep, so the six
  hour dormant interval is still only ever taken on twelve hours of evidence.
  Measured after: the same cold-start sweep completes in 4.5 seconds in forced
  Doze, and the whole poll in 17.

- **The calendar build is bounded too.** A year of eclipses, elongations,
  conjunctions, showers, oppositions, brightness peaks, constellations, seasons
  and moon phases measures 11.6 seconds on a woken Pixel 8a and 12.5 in Doze,
  inside a broadcast Android kills at sixty. That fits - and fitting by a few
  seconds is not the same as being bounded, which is exactly what the satellite
  sweep proved when a throttled phone pushed it past the limit. `SkyCalendar`
  now takes a predicate, asked between contributors, and refuses with
  `Interrupted` rather than handing back half a calendar: a truncated one would
  announce whatever it reached and leave an eclipse unmentioned because the
  budget ran out before eclipses. The clock stays on the caller's side, so the
  astronomy keeps none of its own, and the default never stops so every existing
  caller is unchanged.

- **A failed check no longer sleeps as though the sky were empty.** A pass
  check that threw came back as `Decision.Nothing` carrying the dormant
  interval, and so did a calendar check - the answer for a sky that really is
  empty. But a throw does not know the sky is empty; it knows it failed to look.
  One transient fault at dusk took six hours of satellite passes with it, and
  half a day of calendar notices, silently and with the schedule looking
  perfectly healthy. Both now come back in fifteen minutes.

- **A partial or failed check is no longer invisible.** `PassPollResult.failure`
  was dropped on the floor, so a sweep that ran out of time read in the journal
  exactly like a quiet sky - in the one record kept for the morning after a
  night that produced nothing.

- **A slow poll no longer kills the app's schedule and then repeats itself.**
  The poll runs inside a `goAsync` broadcast, which Android kills at sixty
  seconds, and the budget never fitted: the aurora fetch alone was allowed
  forty-five, and the pass and calendar checks after it were allowed whatever
  they liked. On a slow network the whole thing overran. The kill is not
  graceful - the coroutine's `finally` never runs, so nothing is recorded and no
  real alarm is scheduled, leaving the provisional one armed at the top of
  `PollReceiver`. That provisional was armed at the poll interval, so a phone on
  a short interval retried within minutes and repeated the work that had just
  been killed. Measured on a Pixel 8a: hours at 95% CPU, eighteen polls in one
  of them, and not a single completed pass or calendar check in any of them - so
  no satellite or event alert could have been raised at all. The refresh now
  divides a forty-second budget between its three stages, so a slow aurora fetch
  cannot spend the pass check's turn, and the provisional wake never comes
  sooner than fifteen minutes.

- **Pass and event alerts arrive with the notice they promise.** A wake meant
  for a particular moment was asked for at that moment, and an inexact alarm
  does not work that way: `setAndAllowWhileIdle` attaches a batching window of
  roughly three quarters of the interval and fires near the end of it. Measured
  over thirteen consecutive overnight wakes on a Pixel 8a, every one landed
  between 1.25 and 1.99 times the interval asked for, median 1.78. So a pass
  wake asking for 28 minutes arrived 54 minutes later, and an alert meant to
  give half an hour's notice of ALOS (DAICHI) arrived two minutes before it
  rose. Deadline-bearing wakes - the alert lead, another channel's alert falling
  due, the end of quiet hours with something still overhead, an event notice -
  are now asked for early enough that the slack still lands them in time. The
  sleep through daylight is deliberately left alone: it is a soft deadline and
  its waits are hours, well outside the range the ratio was measured over.

- **A resting pass check no longer asks to be woken a minute later.**
  `PassPlanner.nextWake` answered "nothing changed" with the current instant,
  which `Refresh.minutesUntil` floors to one minute. Nothing reaches it today,
  because `PassPoller` returns the held instant without calling it, but the
  branch turned a no-op into a one-minute poll loop for whoever reached it next.
  It now hands back the check already planned.

- **`bfu-check.sh` no longer fails a good run because logcat forgot it.** The
  question it exists to answer - did a poll run before first unlock - was read
  from logcat, a ring buffer holding about twelve minutes on this phone. The
  harness reboots, soaks for six, and then waits however long it takes for
  somebody to come and unlock the device; forty minutes of that was enough to
  age every locked poll out of the buffer. It then reported "the poll receiver
  never ran at all" while the very next check found the polls in the app's own
  journal, and `./go release` would have refused to publish on it. The durable
  journal is now the evidence and logcat the corroboration. The journal check
  beside it also counted any line carrying `unlocked=false`, sweeping in the
  boot receiver's re-arm, so a soak with one poll in it reported two.

- **`doze-check.sh` no longer invents a deferral out of a single poll.** It
  divided the length of the watch by the number of polls and called the result
  an average gap. That is not a gap: n polls have n-1 gaps between them, and the
  watch starts and ends mid-interval. One poll in a thirty-minute watch has no
  gap at all, and was reported as a thirty-minute one - which then failed
  `--expect-within 15` and stopped a release on the strength of no measurement.
  The mean is now taken between consecutive polls, and a run with fewer than two
  says so.

- **`--expect-within` no longer blames Doze for the app's own schedule.** The
  poll interval in the settings is only used when the app has no position; with
  one, `PollingPolicy` plans from the sky - sleeping until dark in daylight,
  backing off to `QUIET_MINUTES` on a quiet night. A gate seeded to two minutes
  was measuring wakes the app had deliberately put hours out. The harness now
  reads what the app asked for from `dumpsys alarm`'s `requester=` field and
  skips the comparison when that is already wider than the expectation, while
  still failing a phone that went quiet after one poll where a short wake was
  pending.

- **The Tonight tab no longer goes blank for eight hours a day.** From dawn
  until about midday it listed no passes, no events and no comets, and the
  darkness card described the night that had just ended - which at that hour
  reads exactly like an app with nothing to say. The window came from the night
  *nearest* the moment, which is the right night for dating an eclipse and the
  wrong one for a screen: in the morning the nearest night is behind you, so the
  window's end sat before its start and everything asking "does this fall inside
  tonight" answered no. It now looks to the night ahead, and a night already
  under way keeps its real dusk rather than starting at whenever you opened the
  app.

- **The calendar tab scrolls.** A year ahead is around a hundred entries and
  every one of them was being laid out whether or not it was anywhere near the
  screen. Opening the tab cost a frame of over a second, and every frame of a
  scroll afterwards missed its deadline. Only what is on screen is composed now:
  the worst frame is down from 1300ms to 200ms, and jank from 27% of frames to
  3%. Each tab also remembers its own scroll position instead of sharing one, so
  opening the calendar no longer lands you half way down it.

- **"Nothing crossing tonight" is no longer said when the app simply does not
  know.** With no orbital elements downloaded, the satellites card produced the
  same empty list a genuinely quiet night produces, and said the same sentence
  about it. That is the one thing the card must never get wrong, because its
  whole value is that you believe it and stay indoors. It now says so instead -
  and distinguishes a phone that has not run its first check yet, which has not
  failed at anything and is told so in ordinary type, from a download that
  actually failed, which is marked and says why.

### Changed

- **Anything in the calendar can be starred.** The star used to be offered only
  where it could produce a notification, which ruled out the constellations, the
  moon phases, the solstices, and any eclipse that misses this country. But a
  star is also what puts an event into your own calendar, and that never
  depended on notifications - so the button was being withheld on events where
  it does something real. Which of them are worth marking is your judgement. A
  star still cannot wake you for something invisible from where you are.

### Added

- **Gloaming now says what it has downloaded and what went wrong getting it.**
  It spent a fortnight predicting satellite passes from a two-week-old catalogue
  and never said so: the upstream stopped republishing, every request from the
  phone went on answering "not modified", and nothing failed anywhere you could
  see it. A few lines under the "check now" button now answer both halves for
  every document - how old the copy on this phone is, and what the last attempt
  did: downloaded with its size, already up to date, or failed with the reason.
  Pressing "check now" shows a progress bar and names what is being downloaded.

  It stays out of the way otherwise. Nothing is shown until a check is run,
  except when something is actually wrong - satellite elements more than three
  days old are called out unasked, because a fortnight-old element set puts a
  pass minutes and degrees from where it really is, and a report you have to
  press a button to see would have stayed silent through the whole fortnight.

- **A development build is published from every push.** One release, on a
  rolling `dev` tag that moves with the tip of `main`, carrying the signed APK
  and every commit since the last version. It replaces itself rather than
  accumulating, so the link never goes stale and there is never a choice to make
  about which one is current. It is signed with the same key as a release, so a
  development build and a release install over each other freely - settings, the
  battery-optimisation exemption and the record of what your alerts were worth
  all survive the swap, where an uninstall would take them. The version name is
  the last release's, because that is what a version name is for; the commit,
  shown on the settings screen and in the filename, is what says which build you
  actually have.

## [1.0.0-rc1] - 2026-08-30

Everything below is the first release candidate: the whole of the aurora scope
and the three phases built on top of it. It is a candidate rather than 1.0.0
because what is left before release is the part no suite can do - a season of
real nights, to calibrate the alert thresholds and confirm the app is worth
keeping installed.

### Added

- **The settings screen says which build you are running.** One line at the
  foot of it: the version as a person reads it, the build number Android knows
  it by, and the commit it was built from. The app is installed from an APK
  rather than from a store, so there was previously nowhere at all to read
  that - no listing carries the version, nothing updates in the background, and
  a phone can sit on one build for months without a word about it anywhere. It
  is the first thing worth quoting when an alert did not arrive, and until now
  the only way to find it was a laptop and `adb`. The commit is there because
  the version name does not move between releases: every test build in the month
  before one carries the same name as the last, and only the commit says which
  of them is on the phone. A build made from a tree with uncommitted changes in
  it says so - it corresponds to no commit and nothing can reproduce it - and so
  does a debug build, which is not the release it shares a name with.

- **The settings screen shows what your alerts were actually worth.** Every
  alert has carried two buttons for months, and the app has only ever shown what
  it concluded from the answers: a threshold that moved, and a sentence saying
  why. That is the wrong way round for somebody's own account of their own
  nights. The record now sits directly under the switch it is evidence for -
  what each channel has come to, and the recent answers with what the sky was
  doing at the time, in the words the buttons themselves use. An alert nobody
  answered is counted as its own outcome rather than as a failure, which is the
  whole reason that third answer exists. It can be cleared, because it is
  nobody else's record, and clearing it says plainly what that costs: the
  thresholds go back to their widest, since there is then nothing to narrow them
  with. None of it has ever left the phone and none of it records where you
  were.

- **A Guide tab, and cards that explain their own words.** The app has always
  taught in exactly one place - the sentence on each constellation saying how to
  pick it out - and "a flattened W of five stars, on the far side of Polaris from
  the Plough" is no use whatever to somebody who knows neither, which is
  precisely the reader a star-hop is written for. So there is now a guide, in
  three parts: the landmarks every direction in the app is measured from (the
  Plough, Polaris, the Pointers, the Summer Triangle, the Pleiades, the Square of
  Pegasus, the Milky Way, the Southern Cross), the words the other tabs put on
  screen (magnitude, opposition, occultation, elongation, radiant, perihelion,
  umbra and penumbra, Kp, circumpolar, noctilucent and the rest), and the part no
  amount of arithmetic replaces - dark adaptation, averted vision, red light,
  where to stand, and what an aurora or a comet honestly looks like as against
  what it looks like in a photograph. Every card on Tonight carries an Explain
  button that opens the entries for its own words underneath its own answer, and
  the calendar carries one above its filter; both are closed until asked for, so
  a reader who knows the words never scrolls past a glossary to reach tonight.
  Nothing in it needs a location, a forecast or a network, which makes it the one
  screen that works before anything is set up. A unit test checks that every
  landmark a star-hop sends you to is a landmark the guide explains, so a pattern
  added later cannot quietly reintroduce the problem it was written to fix.

- **The constellations, on a card and in the calendar.** Which patterns are up
  tonight, highest first, with where to look and one sentence on how to pick
  each one out - three stars in a line make Orion's belt, the end of the
  Plough's bowl points at Polaris. Ones that never set from where you are say
  so. The calendar carries the one dated fact about each: the night it stands
  highest at midnight, which is the best it is placed all year. Twenty-two
  patterns, chosen for being ones you could pick out and would name rather than
  by a brightness rule, which admits constellations with bright stars and no
  shape. Never interrupts, like the equinoxes and the moon phases - the date is
  settled by the earth's orbit and falls within a day of the same date every
  year, which is planning rather than news. Individual stars and deep-sky
  objects are still deliberately absent: that is a catalogue, and Stellarium
  does it better.

  The card leads with a handful rather than listing everything up, because
  ranking by height alone puts the patterns that never set at the top on every
  night of the year - from Scotland they transit far higher than anything
  seasonal, so the full list ran to seventeen rows whose first seven were the
  same in January as in December. Those are named in one closing line instead,
  and the places go to what has changed since last month. Whichever is at its
  best tonight comes first however low it is, the rule the space station
  already gets among the passes. A setting turns the full list back on.

- **The planets and comets say which constellation they are sitting in.** A
  comet is a faint smudge at a position nobody has memorised, so "in Andromeda"
  is worth more than the altitude and the bearing put together - and a planet
  sitting in a pattern the card above has just told you how to find is a planet
  you can find.

- **What is happening right now is marked as such.** On the calendar it takes a
  border and the words "Happening now"; on the Tonight tab, the same words above
  the entry. In words as well as in colour, so it survives the night-vision
  theme - which is one red on black by design, and would tell nothing apart by
  background alone. Only for things with a beginning and an end: an eclipse
  between its contacts, an occultation, a shower on its night. A planet at
  opposition is at its best for six weeks, and a card claiming to be happening
  now for six weeks would say nothing at all.

- **Lunar occultations.** When the moon passes in *front* of a bright planet
  rather than merely near it, the calendar now says so, with the minute the
  planet disappears behind the moon's edge and the minute it comes back. It goes
  out in a second or two with no fading, which is one of the very few naked-eye
  events that looks like something happening rather than something being. These
  are strictly local - the moon's parallax is about two of its own diameters, so
  two observers a few hundred miles apart genuinely disagree about whether one
  happens - and it replaces the conjunction entry rather than adding a second
  one, on the same notification channel.

- **Gloaming can set its own alert thresholds.** A switch - off unless you turn
  it on - hands the alert threshold, the cloud tolerance, the pass horizon and
  how often each channel may interrupt over to the app, which works them out from
  your own verdicts. The rule is deliberately simple enough to state back to you:
  the widest setting at which most alerts still turned out to be worth going out
  for. Until there is enough to go on it opens every gate as wide as it goes -
  missing a real aurora is the failure that matters, one look out of the window is
  not. Your own choices are never overwritten, so switching it off brings them
  straight back. And with it off the app still says what it would have chosen
  underneath each setting, with its reasoning, so you can take its word or leave
  it. All of it stays on the phone.

- **"Was it worth it?"** Every aurora, satellite and event alert now carries two
  buttons, and pressing one records what the sky was actually doing at the time -
  the probability, where the reading came from, Kp, Bz, all four cloud layers and
  the threshold that let the alert through. An alert nobody answered raises a
  card on the Tonight tab the next time you open it, which adds the third honest
  answer: you did not look. Nothing about any of this leaves the phone, and no
  new notification was added to collect it. The app has always shipped guessed
  alert thresholds and said so; this is what it takes to stop guessing.

- **Starred events can go straight into your calendar.** Three answers rather
  than a switch, because "put it in my calendar" means two different things to
  two reasonable people: a calendar Gloaming creates on the phone with no account
  behind it, so nothing syncs it anywhere, or your default calendar, wherever
  that already syncs to - which the settings screen says plainly rather than
  leaving you to find out. It is off until you pick one, the calendar permission
  is asked for at that moment and never on first run, and taking a star off takes
  the entry back out. Only entries Gloaming wrote are ever touched, by the row
  ids it recorded itself; nothing searches your calendar for anything.

- **Send an event to somebody, or export it as a calendar file.** Every entry
  in the calendar, and every dated event on Tonight, now has a Share button and
  an Export button. Share sends the event as plain text, so it arrives as a
  message somebody will actually read rather than an attachment they will not
  open; Export writes a standard `.ics` that any calendar app will import. The
  caveat travels with the message where there is one - "it only reaches 4
  degrees, low in the south" is the difference between a wasted evening and a
  good one. Neither button asks for any permission at all.

- **The planets up tonight**, with how high each gets, which way to look, when
  it is highest, how bright it is, and when it rises or sets if it does either
  inside the night. The calendar has always dated the one night a year each
  planet is at opposition; this is the other three hundred and sixty four.
  Mercury is held to a higher bar than the rest, because it is never far from
  the sun and would otherwise print a line most nights that nobody could act on.

- **Three things Gloaming already knew and never said.** The Tonight tab now
  shows the cloud overhead - which every alert has been gated on since the first
  version, while only ever appearing inside a notification - alongside the gap in
  the forecast worth waiting for. It shows the moon: its phase, how much of it is
  lit, when it rises or sets, and how much of tonight's darkness it actually
  spoils, which is the figure the comet gate has always used and is not the same
  as how bright it is. And it distinguishes the two kinds of dark: the twilight an
  aurora shows through, and proper astronomical darkness, which between May and
  August at these latitudes does not arrive at all - the reason a summer meteor
  shower disappoints.

- **The gap in the cloud.** Gloaming already knew the sky was 80% covered; it now
  knows that 80% is overcast until midnight and then clear for three hours, which
  is the thing that actually decides whether a marginal night is worth staying up
  for. The hours ahead ride the same once-an-hour request the app was already
  making - the same endpoint, the same licence, a few hundred more bytes, no new
  identifier, and nothing revealed that the request did not already carry.

- **Comets.** The app now tracks every comet worth knowing about, working out
  where each one will be from its orbit and how bright it actually is from what
  observers have reported. Each gets one calendar entry for the weeks it is
  worth planning around, and appears on the Tonight card whenever it is up,
  with how high it gets and which way to look.
- Four settings for comets, because all four are questions about you rather
  than about the sky: how faint is still worth telling you about, how high it
  has to get, how much moonlight you will put up with, and how old a
  brightness report may be before the app stops believing it. Moonlight matters
  more for a comet than for anything else the app covers - a comet is a smudge
  spread across a patch of sky rather than a point, so a bright moon washes it
  out where it would barely dim a planet.
- The brightness the app quotes for a comet comes from people who have looked at
  it, not from a formula, whenever anybody has looked recently - and it says
  which it is showing. The formula is routinely wrong by several magnitudes in
  either direction: on 25 August 2026 it put one comet six magnitudes too faint
  and another eight too bright, on the same day.
- One quiet notice when a comet you were told about has faded, and then silence.
  Comets disappointing is the normal case rather than the exception, and an app
  that only announces arrivals teaches you its comets never amount to anything.
- A separate notification channel for comets, so you can silence them alone or
  keep only them.
- COBS and the Minor Planet Center are credited on the Credits screen, with a
  link to the licence the comet brightnesses are published under. Every measured
  comet brightness in the app comes from volunteer observers who went outside
  and looked, which is the one thing here no amount of arithmetic could replace.
  Observers are counted rather than named, so that somebody who asks COBS to
  delete their data is not left with their name cached on strangers' phones.
- A comet's brightness now tracks what it is doing rather than what it averaged
  over the past fortnight, and the app says how many people saw it over how many
  days. A comet that outbursts can brighten several magnitudes in a day, and the
  old fortnight average turned one such outburst into a rounding error.
- Your position can now be detected by the phone as well as typed in, with a
  switch between the two you can change at any time. It asks for coarse
  location only, only while the app is open, and never in the background: it
  reads the last position the phone already had rather than turning the GPS on.
  Typing it in still works with location denied forever, and is still the only
  way to ask about somewhere you are not.
- A "tonight" card at the top of the Tonight tab: when it gets dark, which
  satellites cross and how high they get, any calendar event falling tonight,
  and the noctilucent prompt in season. Until now passes could only reach you
  as a notification, so standing outside wondering what was crossing meant
  waiting to be interrupted. It leads with the best few rather than the
  earliest, and always finds room for the space station.
- A filter on the calendar, one chip per kind with a count, hiding what you do
  not care about from the list and from the alerts alike. The moon's quarters
  are hidden by default: there are as many of them as there are new and full
  moons, they matter far less, and together the four filled over half the year.
- More in the calendar: Mars, Jupiter and Saturn at opposition - the one night
  of the year each is up all night and at its closest - Venus at its brightest,
  the equinoxes and solstices, and a note on the full moon when it falls at
  perigee. Nothing that needs a telescope: the app covers what you can see with
  your eyes or a pair of binoculars, and that is now written down as the rule.
- Starring a marginal event now works, and means "tell me anyway". Those are
  exactly the events worth an explicit opt-in - a near-total eclipse low in a
  brightening sky - and the star had no effect on them, which made it a control
  that did nothing on the events you would most want it for. A star still
  cannot lift something above the horizon.
- Events in the calendar can be starred. There is a "tell me about all of
  them" switch in Settings, on by default, so the calendar is useful before
  you have curated anything; turn it off and only your starred events will
  interrupt. Starring one while the switch is still on says so, rather than
  leaving you to wonder whether the star did anything.
- A sky calendar, on a tab of its own. Solar and lunar eclipses, meteor
  showers, close pairings of planets, greatest elongations of Mercury and
  Venus, and the moon's phases - all worked out on the phone from a vendored
  ephemeris, with no network and no downloaded list. Everything is judged
  against where you are standing: an eclipse that happens with the sun already
  below your horizon says so rather than being listed as though you could watch
  it.
- Alerts for the calendar, on three channels - `eclipses`, `meteor-showers` and
  `conjunctions` - so any of them can be silenced from the system settings.
  Eclipses and shower peaks get two notices, one a few days ahead so you can
  plan around it and one on the day; everything else gets the one on the day.
  Only events actually visible from where you are will interrupt.
- Meteor showers with the two things most forecasts leave out: how high the
  radiant gets where you are, and what the moon is doing. A rate of 150 an hour
  with the radiant at 15 degrees under a gibbous moon is a rate of about five,
  and the app says so instead of quoting the headline number.
- A countdown for eclipses. Press "Follow it" on the notification and the app
  counts to each contact in turn - first contact, totality, maximum, last
  contact - and says where in the sky to look. It needs no signal, works for
  partial and lunar eclipses as well as total ones, and stops on its own.
- A prompt for noctilucent clouds on summer nights, when the sun sits at the
  right depth for high ice clouds to still be lit. It is a prompt rather than a
  forecast and says so: there has been no way to predict these since the AIM
  satellite ended in 2023.
- Three tabs - Tonight, Calendar and Settings - where the app was one long
  scrolling page.


- Satellite passes. The app predicts when the ISS and other bright satellites
  cross the sky where you are, and tells you half an hour before one is
  highest: which way to look, how high it gets, and whether it will go into the
  Earth's shadow part-way across. Two notification channels, `iss-passes` and
  `bright-satellites`, so the station can be kept and the rest silenced.
- Orbital elements published as `sats.json`, refreshed two-hourly by the Worker
  from CelesTrak's `stations` and `visual` groups, and the supplemental Starlink
  list. **OMM, never TLE**: the
  public catalogue passed 100000 in July 2026 and GP data is not published in
  TLE format for those objects, which include the brightest satellites now
  going up.
- A circuit breaker on the CelesTrak source. Any non-200 response stops all
  further querying until a human clears it by hand, as their usage policy
  requires; `sats.json` keeps serving its last-good copy meanwhile, and the
  maintainer gets one push saying what the response was.
- A standalone elements fetcher (`fetcher/`) that runs on a host outside
  Cloudflare, because Cloudflare's egress cannot reach CelesTrak - three
  attempts, three 522s, against a domestic connection getting 200 in under two
  seconds at the same moment. It bundles to a single file, keeps no local
  state, and writes to the same bucket, so nothing about the read path or the
  app changes. See ADR-0008 and `fetcher/README.md`.
- The `sats` endpoint in the signed manifest, so the elements can move without
  stranding installs.
- How often Gloaming may interrupt you is now yours to set, per channel:
  aurora, space station passes, other bright satellites and Starlink trains each
  have their own minimum interval, starting at six hours. The interval is also the window the
  best pass is chosen from, so a longer setting means better alerts rather than
  just fewer of them. A clear night has upwards of forty satellite passes worth
  looking at, and without this the app would have mentioned most of them.
- Almost every timing in the app is now yours to set, rather than a constant
  nobody could see. How long a watch runs before it stops itself, including
  "until it gets light"; how much warning you get before a pass is highest; how
  low a pass may be and still be worth mentioning; and how early the live
  tracker starts. A few numbers stay fixed on purpose - the tracker's tick, how
  far a pass may drift and still be the same pass, and the ceiling on a watch -
  because those are not preferences, and a wrong value breaks the app rather
  than annoying you.
- Gloaming keeps its own log of what it did and why, on the phone. Android's own
  log is a buffer the system recycles, and overnight - the only time this app
  works - it has usually rolled past whatever you wanted to see. Choose how many
  days to keep, export it, or clear it. It records decisions and never where you
  are.
- An option to store your exact location for before-first-unlock alerting,
  rather than the rounded one. Worth it only for satellite passes: the aurora
  grid is read at whole degrees anyway, but 111 km moves a pass by tens of
  seconds and several degrees. Off by default, and the setting says plainly what
  it changes about what a locked phone will hand over.
- Live pass mode. Gloaming can follow a pass across the sky while it happens,
  counting down to the rise and then saying which way to look and how far up,
  second by second, until it sets. It needs no signal - the elements are already
  on the phone - and it says when the satellite goes into the Earth's shadow,
  which is the difference between "it has gone" and "I must be looking in the
  wrong place". Offered as a "Follow it" action on every pass alert, and as a
  setting for the space station on its own. Never automatic for the rest of the
  bright catalogue, which goes over constantly.
- Starlink trains. For the few nights after a launch, while a batch is still
  strung out in a line, Gloaming tells you when the front of it rises and when
  the last one will be clear. It has its own notification channel and its own
  interval, because a launch is a train for a few nights and then never again,
  and it should not lose its place to a rocket body that comes over every night.
  Detected by the fetcher from CelesTrak's supplemental Starlink data - which is
  built from the operator's own ephemerides, and so far better than the general
  catalogue for satellites still raising their orbit.
- An explanation where the space station never rises. Above about 60 degrees
  north or south its orbit does not reach, and the app now says so and says why
  rather than simply never mentioning it. Other bright satellites are
  unaffected; many are in polar orbits.
- Look4Sat's SGP4/SDP4 propagator, vendored under the GPL-3.0 and credited in
  the app. Its agreement with Vallado's verification vectors is now a unit test
  and a release gate.

- Architecture documentation (`docs/architecture.md`), decision records under
  `docs/adr/`, and the pre-development checklist (`docs/prerequisites.md`).
- Jetpack Compose application scaffold (replacing the initial Views/AppCompat
  project): `minSdk 24`, `compileSdk`/`targetSdk 37`, JDK 21, Kotlin 2.4.0 via
  AGP 9 built-in Kotlin, Compose BOM 2026.06.00, Material 3. A `MainActivity`
  and `GloamingTheme` render a placeholder screen; the debug APK builds green.
- GPL-3.0 licence and a README, including the endpoint-use note for forks.
- Cloudflare cron Worker publishing the Phase 1 data files: the packed OVATION
  aurora grid as `oval.bin`, and `status.json` composed from the SWPC and
  AuroraWatch UK sources on their separate schedules, with the documented
  `Cache-Control` set at PUT time.
- Signed manifest: `index.json` plus an Ed25519 detached signature at
  `index.json.sig`, republished only when its contents change. This is the only
  URL the app will bake in, so every other endpoint can move without stranding
  installs that never update.
- Health tracking and maintainer alerting: `health.json` records per-source
  state, last success and last error; a maintainer-only ntfy topic receives one
  push when a source starts failing and one when it recovers.
- Ed25519 signature verification for the app (`Ed25519Verifier`), so the signed
  manifest can be checked on Android 7.0 where the platform has no Ed25519.
  Verify-only, no dependency, validated against the RFC 8032 vectors.
- Manifest fetching for the app: verifies the signature over the exact bytes
  received, caches the verified copy, and falls back to the last known-good
  manifest - re-checking its signature too - whenever the network copy cannot be
  trusted.
- Aurora grid handling on the device: inflates the published grid and looks up
  probability for an observer position, wrapping longitudes and clamping at the
  poles. Both hemispheres, so the aurora australis works without extra code.
- Status fetching with conditional GET, and two-tier grid fetching that pulls
  `oval.bin` only when its ETag changes. Failures degrade to the cached copy
  rather than leaving the app with nothing to say.
- Scheduled background polling, with every wake jittered and the next always
  queued even after a failure.
- Alert decisions: per-user threshold, hysteresis so a value hovering at the
  threshold produces one notification rather than a stream, and a six-hour
  minimum between alerts that a substantially larger event can still escape.
- A screen: manual location entry, threshold, prominent data age, and prompts
  for the battery-optimisation exemption and notification permission.
- Optional before-unlock alerting, off by default, for devices that reboot on a
  timer. Keeps alerts working while the phone sits locked after a reboot, at the
  cost of copying the rounded grid cell - never the precise position - into
  device-protected storage.
- A home-screen widget showing the local probability and its age, so "is it
  worth checking?" can be answered at a glance without an interruption. Plain
  `RemoteViews`, no Glance: it is three pieces of text, and a Compose runtime in
  the launcher's process would be a poor trade. It never refreshes on a schedule
  of its own - each poll redraws it, so it is exactly as fresh as the data
  behind it and costs no wakes - and a reading older than twelve hours is not
  shown at all rather than being presented as current.
- Red night-vision mode: pure red on unlit black, with the screen dimmed. Not a
  red-tinted dark theme - the green and blue channels are zero, because a dimmed
  white screen still emits across the spectrum and costs the twenty minutes of
  dark adaptation it looks like it is protecting.
- The AuroraWatch UK enhancement layer. Inside a UK bounding box a real
  magnetometer reading can raise what the global model says, feeding one
  internal likelihood so nothing downstream branches on which source is in play.
  It can only raise: AuroraWatch's alerts come from a single site that defaults
  to the Lancaster magnetometer and get steadily more pessimistic further north,
  and a green status silencing a grid that says 65% over Shetland would be the
  app failing at its one job. Notifications say when a reading came from
  AuroraWatch, which their terms require and which the user should be able to
  tell anyway.
- Storm mode: while a large display is actually overhead on a dark, clear night,
  the app follows it every couple of minutes behind a `specialUse` foreground
  service, so someone standing outside sees it strengthen or fade. It costs
  nothing the rest of the time because it does not run, and it stops as soon as
  the display fades, the sky closes in or it gets light - with a four-hour cap
  as a safety net, since it holds a wake lock to keep the cadence honest.
- Adaptive polling. The app sleeps until dusk rather than checking through the
  day, goes dormant where the sun does not set, slows to hourly under heavy
  cloud, and escalates to five minutes when the solar wind turns southward or Kp
  rises - which, because Bz is measured an hour upstream at L1, is warning of a
  substorm that has not started yet. The schedule reads cloud from the cache
  rather than fetching it: spending a radio wake to decide how often to wake the
  radio would undo the saving.
- The darkness and cloud gates, quiet hours, and an opt-in override that lets an
  exceptional display through them. The gates run cheapest first - threshold,
  darkness, hysteresis, quiet hours and rate limiting all cost nothing - so only
  a poll that has already decided to alert pays for a cloud forecast. An
  unreachable forecast alerts anyway and says the cloud is unknown, rather than
  going quiet whenever a third party is down.
- Darkness windows and moon phase, computed on the device with no network at
  all. The polar cases are answered rather than guarded against: Shetland's
  simmer dim reports that no astronomical darkness arrives, and the polar night
  reports darkness with no dawn to find.
- Cloud cover from Open-Meteo, fetched by the device itself rather than through
  the project's Worker, because Open-Meteo's rate limits are per-IP. The
  position sent is rounded to about a kilometre - the resolution of the finest
  model Open-Meteo runs, so the forecast loses nothing - and readings are cached
  for an hour, which is also the cap their terms set.
- Test harnesses for the two things only a real phone can answer:
  `tools/bfu-check.sh` reboots a device and reports whether alerting survived
  the locked state, and `tools/doze-check.sh` forces deep Doze and measures how
  far apart the polls actually land. Both turn an overnight wait into minutes.
- A "watch now" switch, which starts the same foreground service storm mode
  uses and keeps it running until the user stops it or its four-hour cap does.
  Everything else in the app is scheduled through `AlarmManager`, which the
  system is free to defer; a foreground service runs its own clock and is the
  only thing Android will not delay. It is a button rather than a default
  because it costs a wake lock and a notification that will not go away.
- The status card now says why the app is not checking - "sun is up, nothing to
  see until it gets dark, around 22:40", or that this time of year has no
  darkness in it at all. The adaptive schedule sleeps for up to twelve hours at
  a stretch, and without this a correct long gap is indistinguishable from a
  background service the system has killed.
- A notice while battery saver is on, next to the watch switch that is immune
  to it. Raised only while the saver is actually on, so it does not become
  another standing warning to scroll past.
- Credits and licences on the screen itself. AuroraWatch UK's conditions
  require their attribution beside their data, Open-Meteo publish under CC BY
  4.0, and the vendored astronomy code is MIT - which requires its notice in
  every copy of the software, and an installed APK is a copy. Until now that
  notice existed only in the repository, which nobody who installs the app ever
  sees. The AuroraWatch credit follows their reading rather than standing
  permanently: crediting them for a global-grid number would misattribute one
  they had no part in. A unit test compares the shipped MIT notice against the
  vendored licence file, so an upgrade cannot quietly leave a stale one behind.
- The screen says when a reading came from AuroraWatch rather than the global
  grid. The notification already did, but most polls are quiet and post
  nothing, and the number they produced stays on the screen until the next one.
- `./go` at the root of the repository, one way in to building, testing and
  installing. The commands underneath are unchanged and still work by hand;
  what the wrapper adds is the part that is easy to forget and expensive to
  get wrong on a device. It holds the screen awake for an instrumented run -
  waking it on a timer, which keeps working after the charge limit has made
  `stay_on_while_plugged_in` meaningless - refuses to start against a locked
  phone rather than failing test by test with a message that blames the code,
  leaves the app installed afterwards, and puts back everything it changed,
  including after an interrupt.
- The vendored source is checked against the checksums the vendor README
  records, in both directions, by a unit test. "Nothing here is edited" is what
  makes an upgrade a replacement rather than a merge, and it was a sentence in a
  README with nothing behind it; an accidental edit would have surfaced years
  later as a conflict, which is the worst moment to find out that the file in
  the tree is not the file upstream published. The astronomy-engine licence file
  had no checksum recorded at all, which is how the test first earned its keep.
- `./go status`, which says whether a phone is actually set up to alert: opened
  and not stopped, exempt from battery optimisation, in a standby bucket that
  does not defer, holding a pending alarm, and with a poll recorded recently.
  Every one of those checks already existed, welded inside the before-first-unlock
  harness behind a reboot and a six-minute soak - so the question worth asking
  before a night, and again the morning after one that produced nothing, cost
  more to ask than most nights are worth. It is that harness's own `--check-only`
  mode rather than a second copy of the checks, and it changes nothing.
- `./go log`, which pulls the app's own log off the phone. It has kept one since
  logcat's twelve-minute ring buffer was found to be useless for a fault at three
  in the morning, and until now reading it meant the share sheet on the device or
  a hand-written `run-as` incantation. `--live` follows logcat instead, filtered
  by the app's uid as well as its tags: the tags are not ours alone - the system
  logs under `BootReceiver` too - and a uid holds across the short-lived
  processes each poll wakes up in, which a pid would not.
- `./go setup`, which installs the two Node projects from their lockfiles. On a
  fresh clone `check` failed at `worker`, was fixed by hand, and then failed the
  same way at `fetcher`; the message it fails with now names the one command
  that fixes both. It says what it does not install, too - the JDK and the
  Android SDK are still yours to provide.
- `./go testall`, for the question "does all of it still pass" rather than "does
  this change". It is `check`, and then the whole instrumented suite on the
  phone - the catalogue of nights with it - if one is plugged in. If none is, it
  says at the end which seams went uncovered rather than reporting a pass for
  having run less. The screen is taken in hand before the JVM suites start, so a
  locked phone, the one thing no script can get past, is refused in the first
  seconds instead of after everything that needed no phone has had its minutes.
  The overnight harnesses stay outside it: they reboot the phone or sit through
  real Doze windows, and are nobody's idea of a step in a test run.

### Changed

- **`./go results` reads the test results out of the XML, so nobody has to.**
  Gradle reports a failure as a name, the word FAILED and a link to an HTML
  page, which leaves the assertion message - the only thing anybody wants - to
  be dug out of `build/test-results` by hand and unescaped there. This reads
  that XML for both suites and groups failures by what actually went wrong
  rather than listing the same defect once per test that hit it. Three hundred
  and forty-three failures on one missing class is one line, not three hundred
  pages.

- **`./go verdicts` brings the "was it worth it?" answers off the phone.** They
  are what a season of real nights exists to produce, and until now nothing
  could see them: `verdicts.json` sits in device-encrypted storage, which no
  file manager shows and `adb pull` cannot reach. It prints what was answered
  and what the sky was doing at the time, with a tally per channel, or the file
  itself with `--json`.

- **`./go release` says more than where the APK landed.** Its size and how many
  dex files it needed, which is the regression a new dependency causes and
  nothing else would report; which key signed it; and which commit it came from,
  or that it came from none - an APK built over uncommitted changes cannot be
  rebuilt by anybody, including whoever built it.

- **The release build is a fifth of the size it was.** R8 shrinks it: 8.9 MB of
  APK down to 1.8, and 23.6 MB of dex in two files down to 2.9 in one, which is
  a cold background wake with a fraction of the class loading to do - on the
  code path the whole app exists for. It had been off since the Compose scaffold
  left it off, which is not the same as anybody having decided anything.

  It keeps this app's own classes and shrinks everything around them. That costs
  about 90 KB against shrinking the lot, and buys two things worth a great deal
  more than 90 KB. The instrumented suite is white-box - it reaches into device-encrypted
  storage and the journal directly, because that is where the questions worth
  asking about this app live - so a fully shrunk build collapsed after 55
  tests with 50 of them failed, on classes R8 was entirely right to remove, and
  the shipped APK would have been the one build nothing had ever run. And nothing is reported off a user's phone
  by design, so the only stack trace this project will ever see is one somebody
  copies out by hand; against an obfuscated build that is a page of single
  letters, useless without the mapping file for that exact release.

- **A release is signed with the project's key, and a build that cannot be is a
  build that fails.** The keystore has existed since August and its fingerprint
  has been in the README since, but nothing connected the two: `./go release`
  emitted an unsigned APK and said so. An APK signed with the debug key, or with
  none, is worse than one that fails to build - it installs, it runs, it looks
  entirely well, and then Android refuses every future update from the real key,
  so each copy has to be uninstalled by hand. Off-Play there is no Play App
  Signing to notice on the project's behalf. `./go release` now also compares
  the certificate it has just produced against the fingerprint the README
  publishes, and says which key signed it, because the mistake this guards
  against cannot be undone after anyone has downloaded the result.

- The AuroraWatch UK credit links their conditions of use. Every other source on
  that screen names its licence in its own sentence, and this one cannot: the
  body is their attribution word for word, which is a condition of using the
  data. A link is the one place left to say the data is not for commercial use.

- `./go check` also compiles the app's instrumented sources and builds the
  fetcher's bundle. Compiling needs no phone, and a rename in `main` that breaks
  the instrumented suite otherwise stays invisible until the next time one is
  plugged in - the most expensive place to find out, and the least likely moment
  to want to be fixing a compile error. Building leaves what would be copied to
  the server identical to what was just tested.

- A failing test says what it asserted. Gradle's default is the test's name, the
  word FAILED and a link to an HTML report, which leaves the one fact anybody
  wants off the screen and sends whoever needs it digging through the XML under
  `build/test-results`.

- The signed manifest carries `issued_at`, and the app refuses any manifest
  older than the one it already holds. A signature does not expire, so
  verification alone left a replay open: whoever could answer for the domain
  could serve a correctly signed older copy and move the endpoints back, or
  restore a `message` that had been withdrawn. A wall clock rather than a
  counter, so deleting the object cannot restart the sequence and lock every
  install out of every future manifest.
- Signing the manifest is tracked as a source in `health.json` and can fail on
  its own. It used to run before and outside all of them, so a bad key aborted
  the whole invocation before anything was recorded - the one failure most
  likely to go unnoticed, because the channel built to report it never ran. A
  failure is now recorded, pushed to the maintainer, and does not stop the data
  refresh.
- The battery-optimisation exemption is stated as a requirement rather than a
  refinement. The screen said alerts would be "delayed" without it, which
  undersold it: an aurora app that cannot check overnight is not doing
  anything, and no other setting works around it. Running without the exemption
  is out of scope, and the non-exempt schedule is deliberately neither measured
  nor accommodated.
- astronomy-engine is vendored (MIT, tag `v2.1.19`) rather than resolved as a
  dependency. It is not published to Maven Central at all - only to JitPack,
  which builds it on demand - and a third-party build service in the supply
  chain is a poor trade for one dependency-free file. See ADR-0006.
- Scheduling uses AlarmManager with Direct Boot aware receivers rather than
  WorkManager, which cannot run before first unlock - precisely the state a
  scheduled reboot leaves the device in.

- Base application theme no longer depends on the Material Components view
  library; a pure-Compose app themes at runtime, so the platform theme only
  supplies a no-action-bar window.
- Corrected the recorded SWPC solar wind endpoints: the paths under
  `/products/solar-wind/` now return 404, and the summary products are used
  instead.

### Fixed

- **An update no longer leaves the app silent until somebody opens it.** An
  update cancels every alarm an app holds, exactly as a reboot does, and only
  the reboot was being listened for. Gloaming is installed from F-Droid and
  Obtainium, which update in the background, so the app would go quiet at the
  update and give no sign of it: the battery-optimisation exemption still
  granted, the app not in the stopped state, an untroubled-looking settings
  screen, and simply no alarm behind any of it. For an app whose whole job is
  to wake up overnight without being asked, that is the worst shape a fault can
  take. The boot receiver now listens for its own package being replaced as
  well as for the two boot broadcasts, and re-arms the schedule the same way.

- **"Last check failed" now says what failed.** The reason shown on Tonight, and
  written to the journal, was whatever the exception said on its own - which for
  a name that will not resolve is the hostname and nothing else, for a call that
  ran out of time is the word `timeout`, and for a manifest that could not be
  had was "no usable cached copy exists", the one part of the story that says
  nothing. A request that cannot be sent now names the URL and the kind of fault,
  a failure carries the reason underneath it rather than only its top, and where
  both a fetch and an empty cache failed it is the fetch that is blamed.

- **A failed fetch now says what actually failed.** Both runtimes describe every
  network-level failure with one generic string - `fetch failed` in Node, with
  the real reason nested underneath, and `Network connection lost.` in the
  Worker - and that was all that was being passed on. When CelesTrak stopped
  accepting connections, the journal, the health record and the alert on the
  maintainer's phone all said `fetch failed` and nothing else: not which of the
  three URLs, not whether it was CelesTrak or R2 that could not be reached, not
  whether the name failed to resolve or the connection was refused. A Worker
  source that could not be reached said less still. Each of them now names what
  was being reached for and the reason underneath it.

- **An eclipse stopped disappearing from the calendar at the moment it began.**
  The list kept an entry while its *peak* was still ahead, and it is worked out
  from the moment you open the app - so an event dropped off at the instant it
  became worth looking at. The Quadrantids reach their maximum before dawn and
  vanished for the evening people were opening the app to check them; Jupiter's
  opposition says it is worth looking on any clear night for a few weeks either
  side and then left the list on the day. An entry now stays while any part of
  it is still to come.

- **A conjunction in the next six hours is no longer invisible.** Close
  approaches are found by sampling every six hours and picking out the moments
  lower than the samples either side, and the first sample had nothing before it
  to be compared against. Since the calendar is worked out from the moment you
  open the app, the six hours it could not see were always the next six - so
  opening the app at nine to ask about an occultation at eleven showed nothing.

- **A meteor shower no longer disappears from the calendar on the night it
  peaks.** The peaks are stored as a position in the Earth's orbit rather than a
  date, and the search for when the Earth next reaches one was given a window of
  366 days - a whole year of a quantity that repeats yearly, which the search
  underneath cannot solve. Asked the day before a shower it found nothing at all;
  asked on the day it found the following year's. So the two days either side of
  every peak, which are the only two days anybody asks, showed no shower.

- **Changing any setting no longer makes the app forget what it had already
  said.** The settings and the alert state share one preferences file, and saving
  settings cleared the whole file whenever before-unlock alerting was off - which
  is the default. That took the hysteresis latch, the time of the last check,
  which events had already been announced, which comets had already said goodbye,
  and the next scheduled check times with it. Turning on night vision at two in
  the morning could make the app announce tonight's eclipse a second time.

- Starred events are no longer deleted from your calendar and written back on
  every launch. The list of events is empty for a moment while the year is worked
  out again, and that was being read as "every event has gone" - so each start
  removed every entry and recreated it, losing any reminder you had set on one.
  What is in your calendar now follows your stars alone.

- Turning calendar sync off now removes the calendar Gloaming made, rather than
  leaving an empty one named after the app sitting in your calendar list. Only
  ever its own local one - your own calendars are never touched.

- Unstarring an event removes its calendar entry again. Two copies of the same
  preferences file were being kept in memory at once, so the row Gloaming
  recorded writing was invisible to the code that later had to delete it.

- Calendar files exported for an event that happens at an instant - a new moon,
  a greatest elongation - no longer carry an end time equal to their start, which
  the iCalendar standard forbids and stricter calendar apps reject outright.

- The gap in the cloud actually appears now. The forecast was only ever fetched
  once an aurora probability had already cleared the alert threshold, which on a
  quiet night never happens - so nothing was held, and the card that shows when
  the sky clears never showed at all. Opening the Tonight tab now fetches it,
  which costs no more than the poller did: anything under an hour old is served
  from the cache, the position is still rounded to about a kilometre with no
  identifier, and with the cloud gate switched off nothing is requested at all.

- The Tonight tab also stopped quoting a cloud tolerance it had not used. Change
  the tolerance and the gap is worked out again, rather than the new number being
  printed beside an answer from the old one.

- A moon a few hours short of full is no longer described as "waxing gibbous,
  100% lit", which is a sentence that argues with itself. The name now comes from
  the same figure printed beside it.

- The Tonight tab now carries the caveat that goes with an event, not just the
  headline. A partial eclipse whose maximum finds the moon 8 degrees up in a sky
  that never gets fully dark was promised on the home screen and explained only
  in the calendar - which is the wrong way round, since the home screen is where
  somebody decides whether to put a coat on. Keeping those events rather than
  hiding them was always deliberate; carrying the reason with them is what makes
  that the right call.

- Every part of the Tonight tab now says what it is about. The aurora reading
  was the largest thing on the screen and carried no label at all, so it read as
  the app's headline rather than as one of several things the sky is doing; and
  the card under it, called "Tonight", was mostly satellite passes with the
  darkness, the events and the comets run together above them in one unlabelled
  block. It is now one titled card per question - Aurora, Darkness, Events
  tonight, Comets tonight, Satellites - and a card with nothing to say is absent
  rather than empty.

- The last button in a row of choices is reachable again. "Dawn" on the Tonight
  tab was rendering one letter to a line, and the widest aurora threshold and
  cloud tolerance were laid out past the right edge of the screen where they
  could not be pressed at all. All three rows now wrap, which also means a large
  system font size costs a line of height rather than the last option.

- Quiet hours no longer swallow a notice about something that is still
  happening when they end. A satellite pass or an eclipse whose alert fell due
  in the last half-hour of the silence was held back and then never mentioned:
  the app went back to sleep until the pass was over, or for twelve hours,
  while the thing itself was still going on outside. It now comes back when the
  silence lifts, and says so then if there is still something to see. Quiet
  hours are a request not to be woken at 06:30, not a request never to be told
  about an eclipse that runs until half past nine.

- The manifest now costs one request a day, as designed, instead of two
  requests on every poll. Nothing else changes: a verified copy less than a
  day old is simply the answer, and a moved endpoint still reaches every
  device within a day.

- A poll interrupted at the wrong moment can no longer end the overnight
  schedule. The next alarm used to be armed only after a poll finished, so a
  process killed - or stalled past its wake lock - between the alarm firing
  and that reschedule left nothing pending until the next reboot or app open.
  The wake is now armed before the poll starts and replaced with the computed
  one when it completes.

- The app's own log now records polls made before the first unlock after a
  reboot. It was silently empty for exactly that window: the setting that says
  whether to keep a log was read from storage that is locked until the user
  unlocks, the failure was swallowed, and every line from a locked poll
  reached only logcat - whose buffer has rolled over by morning. The journal
  was built for those nights, and now it covers them.

- One observer filing many reports can no longer set a comet's published
  brightness. The magnitude is now the median over observers - one voice
  each, the median of that person's own reports - rather than over the
  reports themselves, where seven wrong numbers from one person outvoted one
  right number from another. This closes the remaining half of the wild
  NEOWISE-reports case that motivated requiring two observers in the first
  place.

- The calendar's kind filter is now remembered. Hiding meteor showers, or
  unfolding the moon's quarters, held until the app was next started and then
  quietly reverted - and hiding a kind also silences its alerts, so the effect
  was not merely cosmetic.

- "Check now" refreshes everything, not only the aurora. It now runs the
  satellite passes and the sky calendar too, which is what a button called
  that should always have done - and it schedules the next background wake for
  whichever of the three is due first. Before, it used the aurora interval
  alone, so pressing it on a quiet overcast night could push the next wake out
  past a pass alert due in half an hour and deliver it after the pass.
- A pass on one channel could be missed because the app was waiting out a pass
  on another. Found on the first untouched overnight run: a rocket body was
  announced at 03:00 and the space station's own alert, due at 03:18, was slept
  straight through. The channels have separate budgets so that one does not
  crowd out the other, and this was exactly that.
- The backend could not tell the difference between a source that was working
  and a source nobody was asking. One of them stopped for two and a half hours
  while the health record reported it healthy, because the record only ever
  described attempts. It now reports a source as stale when its last success is
  too old to believe, and says so on the alerting channel.


- The cached cloud reading no longer sits in device-encrypted storage
  unconditionally. It records where the user was at about a kilometre - the
  rounded position the request carried - which is far finer than the whole
  degree the settings mirror is reduced to, and device-encrypted storage is
  readable before anyone has unlocked the phone. It now follows the settings it
  was taken from, and is deleted from the weaker store when the user turns
  before-unlock alerting off.
- Backup is switched off, and device-to-device transfer is excluded separately,
  because `allowBackup` does not cover it. Both rule files were still the
  unedited template, which means include-everything - so the precise position
  the user typed was eligible to be copied off the phone by the backup system,
  going around the whole reason it is kept in credential-encrypted storage. On
  GrapheneOS that is a live path rather than a theoretical one, because
  Seedvault honours these declarations.
- Jitter is spread either side of the requested interval rather than added to
  it. It exists to stop every install waking on the same boundary, but as
  written it also made every wake up to a quarter later than asked for, on top
  of the batching window the system already attaches to an inexact alarm - in
  an app whose one recurring complaint is that checks arrive late.
- The aurora grid stops inflating past the size a grid can be. Capping the
  download does not bound this - compression is exactly where the two sizes are
  unrelated, and a couple of kilobytes of gzipped zeros expand to gigabytes -
  and the size check ran only after the whole thing was in memory.
- Responses are refused past a size cap, and the HTTP client has a whole-call
  deadline. The manifest is verified only once its bytes are in memory, so the
  signature is no defence against a body that never ends; the poll that would
  have run out of memory is a background one at three in the morning.
- The screen keeps its own clock while it is in front. The age of the last
  check and the sentence explaining a long gap were both fixed at the moment
  the screen opened, so "nothing to see until it gets dark, around 22:40" would
  still be there at midnight - and dusk is the transition that sentence exists
  to describe.
- Storm mode stands down with the start id it was given, so an automatic watch
  ending at the moment the user asks for one of their own no longer takes their
  request with it.
- "Check now" now actually checks, and the screen shows that it did. It went
  through `AlarmManager`, which is inexact - a "one second" alarm can be batched
  up to two minutes out - and the screen only re-read the poll result when the
  app was resumed, so the button appeared to do nothing at all. A check the user
  asked for is run directly, reports itself as busy while it runs, and re-arms
  the schedule from the plan it produces. That last part matters after a
  location change: the pending wake was worked out for the old position, and
  dusk somewhere else is at a different time.
- Tapping an aurora alert opens the app. It carried no content intent, so the
  tap did nothing whatever - the notification did not even dismiss, because
  `setAutoCancel` only takes effect once there is somewhere to go. Storm mode's
  notification had one from the start; the alert that actually wakes people did
  not.

### Notes

*Not one of Keep a Changelog's types, and deliberately so. What is recorded here
is how a change was verified rather than what changed - which belongs with the
release it applies to, and would be a lie under any of the headings above.*

- The suite runs against the shrunk build, not only the debug one.
  `./go device-test --minified` builds a third build type - the release build
  under the debug key - and runs all 609 instrumented tests against it. Compose's
  test rule needs an activity that only its own manifest declares, so whichever
  build the suite runs against has to carry a dependency no released build may;
  hence a third build type rather than testing `release` directly. What it costs
  is a list of frameworks kept whole for the harness rather than for the app -
  Compose, lifecycle, coroutines, the Kotlin standard library, androidx.tracing.
  An instrumentation APK borrows its libraries from the app it tests instead of
  carrying its own, and androidx.test and compose-ui-test call a great deal this
  app never does, so shrinking the app takes the harness apart from underneath.

- A catalogue of nights in `app/src/scenarios/` now drives both suites, so the
  rare states can be looked at without waiting for the sky: eclipses, an
  occultation, a Starlink train, a great comet, the polar day and the polar
  night, a gap in the cloud at one in the morning. Nothing in it is fake
  astronomy - a scenario supplies the place, the instant and the documents a
  device would hold, and the shipped code does the rest, so the dates are real
  and the suite fails if the ephemeris ever disagrees with them. 51 nights,
  each drawn on a Pixel 8a through the real Tonight tab and each posting the
  notification it would produce. Two engine defects listed above were found
  this way and were reachable no other way.
- The worked example for provoking a real alert was stale in two ways: it gave
  a longitude in the grid's 0-359 form rather than the -180 to 180 the app
  takes, and it predated the darkness and cloud gates, so following it produced
  no alert and looked like a broken harness. It now says to pick a cell on the
  night side and why.
- The instrumented suite needs the device awake and unlocked. Behind the
  keyguard an activity resumes and pauses without drawing, and every Compose
  assertion fails with "No compose hierarchies found in the app".
  `stay_on_while_plugged_in` is not enough on its own for a long run: a phone
  that has reached its charge limit has stopped charging and is no longer
  plugged in as far as that setting is concerned, so the ordinary screen
  timeout takes over part-way through and everything from that point fails with
  the same message. It reads as a real failure in whichever tests were running
  at the time.
- Instrumented tests verified on a Pixel 8a running GrapheneOS: storage
  partitioning, permission declarations, scheduling and notifications.
- Before-unlock alerting verified end to end on a Pixel 8a running GrapheneOS:
  after a reboot the boot receiver re-armed the alarm 50 seconds in, and two
  polls fetched, verified and cached the manifest, status and grid while the
  device was still locked. A subsequent run carried it through to a real
  notification - `Notified(probability=21)` from the live grid, posted on a
  phone that had not been unlocked since boot.
- Alerting verified in forced deep Doze on the same device, with the
  battery-optimisation exemption granted: three polls in twelve minutes, an
  alert raised and its notification posted from deep idle. The exemption does
  what it claims - the alarm carries `ALLOW_WHILE_IDLE_UNRESTRICTED` rather
  than the throttled compat form, and neither Doze nor app standby constrains
  it. Hysteresis held on real data across the run: the probability stayed at
  21% and the following polls returned `NotReArmed` rather than notifying
  again.
- Measured the cost of inexact alarms: a two-minute interval is delivered at
  roughly four, both in Doze and before unlock. `setAndAllowWhileIdle` is
  inexact by design and the system batches it, so the configured interval is a
  floor rather than a period. The overhead looks like a roughly fixed cost
  rather than a multiplier - a batching window just under two minutes was
  observed in the alarm dump, on top of the jitter the scheduler adds itself -
  which would put a five-minute request at about eight and a thirty-minute one
  at about thirty-nine. Adaptive polling is built on that reading and therefore
  does not compensate, since asking for half of what is wanted would double the
  cost on any device that delivers punctually. Inferred from one measurement at
  one interval, so it wants confirming with `tools/doze-check.sh` at five and
  thirty minutes.
- Three states stop alerting silently, and all are now checked before a test
  run: an app in the stopped state receives no broadcasts at all and sits there
  from installation until first opened; a never-opened app is in the `NEVER`
  standby bucket; and without the battery-optimisation exemption app standby
  defers alarms anyway. A deferred alarm still lists as pending.
- App standby, not Doze, is what stops overnight wakes. `setAndAllowWhileIdle`
  defeats Doze as documented, but app standby deferred the same alarm by 365
  days while it still appeared pending and healthy. The causes are an app in
  the `NEVER` bucket - where it sits until the user first opens it - and a
  missing battery-optimisation exemption.
- GrapheneOS defaults the USB-C port to charging-only while locked, so adb is
  unreachable for the whole before-unlock window. The harness gathers its
  evidence afterwards instead - from `last_poll_at` in device-encrypted storage,
  the cache file timestamps, and the `unlocked=false` the app logs at the moment
  of each poll - rather than requiring a live connection that cannot exist.

- Measured the packed aurora grid against live data: 1.6 KB gzipped in quiet
  conditions and about 2.5 KB under a simulated severe storm, comfortably inside
  the budgeted 5-10 KB. This closes an open question in the sourcing document.
- Flagged a free-plan risk: Cloudflare allows 10 ms CPU per cron invocation, and
  the OVATION refresh straddles it. Needs measuring on the deployed Worker.
- Battery saver measured on a Pixel 10a, and it costs nothing detectable. In
  forced deep Doze with the exemption granted, a five-minute interval was
  delivered every 9-10 minutes with the saver on and every 9-10 minutes with it
  off. That confirms the fixed-overhead reading taken on the 8a, which
  predicted about eight minutes for a five-minute request. The delay is the
  batching window rather than Doze: `device_idle` was recorded as not applying
  on every sample, and the alarm runs past its requested time to fire at
  `maxWhenElapsed`. Networking was never restricted either, even in deep Doze
  with the saver on - `blocked=BATTERY_SAVER|DOZE|APP_BACKGROUND`,
  `allowed=POWER_SAVE_ALLOWLIST`, `effective=NONE`.
- The restricted standby bucket cannot be reached while the exemption is
  granted: `am set-standby-bucket restricted` reports success and the bucket
  stays ACTIVE. The worst case an exempt app can land in is better than
  assumed, and worth remembering before designing around a bucket it cannot
  occupy.
- One caution about how the above was measured. An earlier run reported a
  sevenfold deferral that was not happening, because `tools/doze-check.sh`
  anchored on a marker line in `logcat` and the ring buffer - about twelve
  minutes, 84,351 lines, on this phone - wrapped past it mid-run. The harness
  then matched nothing and reported the single poll it had seen before going
  blind. Both the alarm dump and `logcat` contradicted it at the time. A
  measurement tool that invents a failure is worse than one that measures
  nothing, so the tool now records each poll as it sees it and selects by
  device-clock epoch instead.
