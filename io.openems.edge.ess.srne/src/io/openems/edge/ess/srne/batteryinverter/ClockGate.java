package io.openems.edge.ess.srne.batteryinverter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Clock plausibility gate shared by the daily schedules. gw-pi2 has no RTC, so
 * after a reboot the edge clock can be wrong until NTP syncs: a time counts as
 * known only once it is at or after {@link #MIN_PLAUSIBLE_TIME} and has run
 * steadily for {@link #CLOCK_SETTLE_CYCLES} cycles.
 *
 * <p>
 * Fed once per cycle from the Edge cycle thread; one instance per schedule.
 */
final class ClockGate {

	/**
	 * Earliest plausible wall-clock time (build-time floor). gw-pi2 has no RTC: a
	 * time before this is an unset clock.
	 */
	static final Instant MIN_PLAUSIBLE_TIME = Instant.parse("2026-11-01T00:00:00Z");
	/**
	 * Cycles the clock must run steadily (no backward step, no forward jump of more
	 * than {@link DischargeGuard#CLOCK_STEP_MINUTES} minutes) after start or after
	 * a step before its time is used. An NTP correction after a reboot is such a
	 * step and restarts the count.
	 */
	static final int CLOCK_SETTLE_CYCLES = 60;

	private static final Duration MAX_CLOCK_STEP = Duration.ofMinutes(DischargeGuard.CLOCK_STEP_MINUTES);

	private Instant lastInstant;
	private int steadyClockCycles;

	/**
	 * Feeds one cycle. Returns the local time only if the instant is plausible and
	 * the clock has run steadily for {@link #CLOCK_SETTLE_CYCLES} cycles; otherwise
	 * null (unknown).
	 *
	 * @param instant the edge clock instant, or null if unreadable
	 * @param zone    the schedule time zone
	 * @return the plausible local date-time, or null
	 */
	LocalDateTime plausibleTime(Instant instant, ZoneId zone) {
		if (instant == null || instant.isBefore(MIN_PLAUSIBLE_TIME)) {
			this.lastInstant = null;
			this.steadyClockCycles = 0;
			return null;
		}
		if (this.lastInstant != null) {
			var delta = Duration.between(this.lastInstant, instant);
			if (delta.isNegative() || delta.compareTo(MAX_CLOCK_STEP) > 0) {
				this.steadyClockCycles = 0;
			} else if (this.steadyClockCycles < CLOCK_SETTLE_CYCLES) {
				this.steadyClockCycles++;
			}
		}
		this.lastInstant = instant;
		if (this.steadyClockCycles < CLOCK_SETTLE_CYCLES) {
			return null;
		}
		return LocalDateTime.ofInstant(instant, zone);
	}

	/**
	 * Whether the local time moved forward from {@code previous} to {@code now}
	 * across one of the boundaries, in a step no larger than the clock-step limit.
	 * A clock jump never counts as a crossing.
	 *
	 * @param previous   the previous local time, or null
	 * @param now        the current local time
	 * @param boundaries the window boundaries
	 * @return true on a forward boundary crossing
	 */
	static boolean crossedForward(LocalTime previous, LocalTime now, LocalTime... boundaries) {
		if (previous == null) {
			return false;
		}
		var step = forwardNanos(previous, now);
		if (step == 0 || step > MAX_CLOCK_STEP.toNanos()) {
			return false;
		}
		for (var boundary : boundaries) {
			var toBoundary = forwardNanos(previous, boundary);
			if (toBoundary > 0 && toBoundary <= step) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Forward distance on the 24h clock face.
	 *
	 * @param from the start time
	 * @param to   the end time
	 * @return nanoseconds from {@code from} forward to {@code to}, 0..&lt;1 day
	 */
	static long forwardNanos(LocalTime from, LocalTime to) {
		var day = Duration.ofDays(1).toNanos();
		return ((to.toNanoOfDay() - from.toNanoOfDay()) % day + day) % day;
	}
}
