package io.openems.edge.ess.srne.batteryinverter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Daily charge-priority schedule for E20F (0 PV preferred, 1 mains preferred, 2
 * hybrid, 3 PV only).
 *
 * <p>
 * Decision D138: the grid may charge the battery only inside the night window.
 * The night value (default 2, hybrid: the night-charge timer can charge from
 * mains) applies from {@code nightFrom} (inclusive) until {@code nightTo}
 * (exclusive), crossing midnight when needed; the day value (default 3, PV
 * only: mains never charges) applies the rest of the day. The switch to the
 * night value is due exactly at {@code nightFrom} and the switch back exactly
 * at {@code nightTo}, so the grid never charges outside the window because of
 * this schedule. Two writes a day while in sync.
 *
 * <p>
 * Writes go through a {@link SafeWriteHandler} (one per write) and the same
 * gates as {@link ModeSchedule}:
 * <ul>
 * <li>nothing is written while E20F is unknown, or while the local time is
 * unknown (it counts only once plausible, {@link ClockGate}) unless the
 * day-value fallback below applies,</li>
 * <li>the night value is queued only while the machine state is verified (state
 * 2, mains), confirmed by the last {@link ModeSchedule#MAINS_CONFIRM_READS}
 * fresh reads; a write still queued when its gate closes is withdrawn if the
 * bridge has not taken it yet ({@link GuardedWordElement}),</li>
 * <li>no night write in the first {@link ModeSchedule#STARTUP_HOLD_CYCLES}
 * cycles after activation,</li>
 * <li>a failed night write (execute error, wrong or missing read-back) leaves
 * the device as it is; retries back off exponentially, at most
 * {@link ModeSchedule#MAX_ATTEMPTS} failed writes per window, refilled only
 * when the local time crosses a boundary forward in a small step,</li>
 * <li>at most {@link #MAX_DAILY_WRITES} successful night writes per local
 * day,</li>
 * <li>an overridden night value is re-corrected with backoff, at most
 * {@link #MAX_OVERRIDES_PER_WINDOW} times per window and
 * {@link #MAX_OVERRIDES_PER_DAY} times per day,</li>
 * <li>an invalid configuration turns the schedule off.</li>
 * </ul>
 *
 * <p>
 * The day value is the safe direction (mains does not charge), so every gate
 * that holds it must not leave the night value on by day. Its write is
 * asymmetric, like the discharge-schedule disarm: it is also allowed while
 * running on battery ({@link ModeSchedule#ON_BATTERY_STATE}), so it is in place
 * before a transfer to the line; failures and overrides never latch (retries
 * back off up to {@link #DAY_MAX_BACKOFF_ATTEMPTS}); it has its own daily
 * budget {@link #MAX_DAILY_DAY_WRITES} and a short startup hold
 * {@link #DAY_STARTUP_HOLD_CYCLES}; and it becomes the target when the clock
 * stays unknown for {@link #UNKNOWN_CLOCK_FALLBACK_CYCLES} cycles.
 *
 * <p>
 * Thread-safety: {@link #step} and {@link #plausibleTime} run on the Edge cycle
 * thread; {@link #onExecute}, {@link #verify} and {@link #onMachineStateRead}
 * run on the Modbus bridge thread and only touch the (synchronized) current
 * handler or volatile counters.
 */
final class ChargePrioritySchedule {

	static final int PV_PREFERRED = 0;
	static final int MAINS_PREFERRED = 1;
	static final int HYBRID = 2;
	static final int PV_ONLY = 3;

	static final int DEFAULT_NIGHT_PRIORITY = HYBRID;
	static final int DEFAULT_DAY_PRIORITY = PV_ONLY;
	static final String DEFAULT_NIGHT_FROM = "00:00";
	static final String DEFAULT_NIGHT_TO = "06:00";

	/** Successful night-value writes allowed per local day. */
	static final int MAX_DAILY_WRITES = 6;
	/** Re-corrections of an overridden night value allowed per window. */
	static final int MAX_OVERRIDES_PER_WINDOW = 2;
	/** Re-corrections of an overridden night value allowed per local day. */
	static final int MAX_OVERRIDES_PER_DAY = 3;
	/**
	 * Successful day-value writes allowed per local day: its own, larger budget, so
	 * overrides of the safe direction keep being corrected while the settings
	 * memory is still protected.
	 */
	static final int MAX_DAILY_DAY_WRITES = 24;
	/**
	 * Cap on the exponent of the day-value retry backoff: with the 60-cycle base
	 * cooldown a failed or overridden day value is retried at least every 480
	 * cycles (about 8 minutes) and never latched.
	 */
	static final int DAY_MAX_BACKOFF_ATTEMPTS = 4;
	/** Startup hold for the day-value write (about 30 seconds). */
	static final int DAY_STARTUP_HOLD_CYCLES = 30;
	/**
	 * Consecutive cycles without a plausible clock after which the target becomes
	 * the day value (about 5 minutes; the clock gate itself settles in 60 cycles).
	 */
	static final int UNKNOWN_CLOCK_FALLBACK_CYCLES = 300;
	/** Minutes in the night window without the night value until it is late. */
	static final int NIGHT_LATE_MINUTES = 10;
	/**
	 * Minimum margin from nightFrom to the charge-timer start, so the night value
	 * is in place before the timer starts.
	 */
	static final int TIMER_START_MARGIN_MINUTES = 5;

	/**
	 * One evaluation result.
	 *
	 * @param state    the schedule state for this cycle
	 * @param target   the target E20F value for this time of day, or null if
	 *                 unknown
	 * @param write    the value queued for E20F this cycle, or null for no write
	 * @param withdraw true if a queued, not yet executed write was dropped
	 */
	record Step(ModeScheduleState state, Integer target, Integer write, boolean withdraw) {

		Step(ModeScheduleState state, Integer target, Integer write) {
			this(state, target, write, false);
		}
	}

	private final boolean enabled;
	private final String invalidReason;
	private final int nightPriority;
	private final int dayPriority;
	private final LocalTime nightFrom;
	private final LocalTime nightTo;

	private final GuardedWordElement writeElement = new GuardedWordElement(0xE20F);
	private final ClockGate clockGate = new ClockGate();
	private volatile SafeWriteHandler handler;
	private Integer handlerTarget;
	private int cyclesSinceDone;
	private int failures;
	private boolean outcomeCounted;
	private LocalDateTime queuedAt;
	private LocalDate writesDate;
	private int writesToday;
	private Integer overrideTarget;
	private int overrides;
	private LocalDate overridesDate;
	private int overridesToday;
	private volatile int freshMainsReads;
	private volatile int freshRunningReads;
	private LocalDate dayWritesDate;
	private int dayWritesToday;
	private LocalDate lastDate = LocalDate.MIN;
	private int unknownClockCycles;
	private LocalDateTime nightMismatchSince;
	private boolean nightLate;
	private int cycles;
	private LocalTime lastNow;

	private ChargePrioritySchedule(boolean enabled, String invalidReason, int nightPriority, int dayPriority,
			LocalTime nightFrom, LocalTime nightTo) {
		this.enabled = enabled;
		this.invalidReason = invalidReason;
		this.nightPriority = nightPriority;
		this.dayPriority = dayPriority;
		this.nightFrom = nightFrom;
		this.nightTo = nightTo;
	}

	/**
	 * Builds and validates a schedule from configuration values.
	 *
	 * @param enabled       chargePrioritySchedule
	 * @param nightPriority E20F value in the night window (0..3)
	 * @param dayPriority   E20F value outside it (0..3, not the night value)
	 * @param nightFrom     "HH:MM" local start of the night window
	 * @param nightTo       "HH:MM" local end of the night window
	 * @return the schedule; check {@link #getInvalidReason()}
	 */
	static ChargePrioritySchedule fromConfig(boolean enabled, int nightPriority, int dayPriority, String nightFrom,
			String nightTo) {
		if (!enabled) {
			return new ChargePrioritySchedule(false, null, nightPriority, dayPriority, null, null);
		}
		var from = ModeSchedule.parseTime(nightFrom);
		var to = ModeSchedule.parseTime(nightTo);
		String reason = null;
		if (!isValidPriority(nightPriority) || !isValidPriority(dayPriority)) {
			reason = "nightChargePriority [" + nightPriority + "] and dayChargePriority [" + dayPriority
					+ "] must be 0..3";
		} else if (nightPriority == dayPriority) {
			reason = "nightChargePriority and dayChargePriority must differ [" + nightPriority + "]";
		} else if (from == null || to == null) {
			reason = "nightChargeFrom [" + nightFrom + "] and nightChargeTo [" + nightTo + "] must be HH:MM";
		} else if (from.equals(to)) {
			reason = "nightChargeFrom and nightChargeTo must differ";
		}
		return new ChargePrioritySchedule(true, reason, nightPriority, dayPriority, from, to);
	}

	private static boolean isValidPriority(int priority) {
		return priority >= PV_PREFERRED && priority <= PV_ONLY;
	}

	boolean isActive() {
		return this.enabled && this.invalidReason == null;
	}

	boolean isEnabled() {
		return this.enabled;
	}

	String getInvalidReason() {
		return this.invalidReason;
	}

	LocalTime nightFrom() {
		return this.nightFrom;
	}

	LocalTime nightTo() {
		return this.nightTo;
	}

	/**
	 * The E20F write element of the schedule's own write task.
	 *
	 * @return the element
	 */
	GuardedWordElement writeElement() {
		return this.writeElement;
	}

	/**
	 * Whether a local time falls in the night window.
	 *
	 * @param time the local time
	 * @return true from nightFrom (inclusive) to nightTo (exclusive)
	 */
	boolean isNight(LocalTime time) {
		if (this.nightFrom.isBefore(this.nightTo)) {
			return !time.isBefore(this.nightFrom) && time.isBefore(this.nightTo);
		}
		return !time.isBefore(this.nightFrom) || time.isBefore(this.nightTo);
	}

	/**
	 * Whether a charge-timer window lies completely inside the night window, so the
	 * timer never charges from mains while the day value is set and always has the
	 * night value while it runs.
	 *
	 * @param start the timer start (local)
	 * @param stop  the timer stop (local)
	 * @return true if [start, stop) is inside [nightFrom, nightTo)
	 */
	boolean coversTimer(LocalTime start, LocalTime stop) {
		var night = ClockGate.forwardNanos(this.nightFrom, this.nightTo);
		var toStart = ClockGate.forwardNanos(this.nightFrom, start);
		var toStop = ClockGate.forwardNanos(this.nightFrom, stop);
		if (toStop == 0) {
			// A timer that stops at nightFrom ends a full day later on the clock face.
			return false;
		}
		return toStart < night && toStop <= night && toStart < toStop;
	}

	/**
	 * Whether a charge-timer start leaves at least
	 * {@link #TIMER_START_MARGIN_MINUTES} after nightFrom. Whether the SRNE
	 * re-evaluates E20F during a running timer, or only at its start, is not
	 * live-verified; with the margin the night value is written before the timer
	 * starts.
	 *
	 * @param start the timer start (local)
	 * @return true if the margin is kept
	 */
	boolean timerStartHasMargin(LocalTime start) {
		return ClockGate.forwardNanos(this.nightFrom, start) >= Duration.ofMinutes(TIMER_START_MARGIN_MINUTES)
				.toNanos();
	}

	/**
	 * The target E20F value at a local time.
	 *
	 * @param time the local time, or null if unknown
	 * @return the target, or null if inactive or the time is unknown
	 */
	Integer targetAt(LocalTime time) {
		if (!this.isActive() || time == null) {
			return null;
		}
		return this.isNight(time) ? this.nightPriority : this.dayPriority;
	}

	/**
	 * Clock plausibility gate, fed once per cycle (see {@link ClockGate}).
	 *
	 * @param instant the edge clock instant, or null if unreadable
	 * @param zone    the schedule time zone
	 * @return the plausible local date-time, or null
	 */
	LocalDateTime plausibleTime(Instant instant, ZoneId zone) {
		return this.clockGate.plausibleTime(instant, zone);
	}

	/**
	 * Called on every fresh read of the machine state, from the Modbus bridge
	 * thread. Counts consecutive reads of state 2 (mains, for the night value) and
	 * of state 2 or {@link ModeSchedule#ON_BATTERY_STATE} (running, for the day
	 * value).
	 *
	 * @param state the raw machine state, or null if unreadable
	 */
	void onMachineStateRead(Integer state) {
		var confirm = ModeSchedule.MAINS_CONFIRM_READS;
		var mainsRead = state != null && state == 2;
		var runningRead = mainsRead || state != null && state == ModeSchedule.ON_BATTERY_STATE;
		this.freshMainsReads = mainsRead ? Math.min(this.freshMainsReads + 1, confirm) : 0;
		this.freshRunningReads = runningRead ? Math.min(this.freshRunningReads + 1, confirm) : 0;
	}

	/**
	 * Whether a raw machine state allows the day-value write: state 2 (mains) or
	 * {@link ModeSchedule#ON_BATTERY_STATE} (running on battery).
	 *
	 * @param state the raw machine state, or null
	 * @return true if running
	 */
	static boolean isRunningState(Integer state) {
		return state != null && (state == 2 || state == ModeSchedule.ON_BATTERY_STATE);
	}

	boolean crossedBoundaryForward(LocalTime previous, LocalTime now) {
		return ClockGate.crossedForward(previous, now, this.nightFrom, this.nightTo);
	}

	/**
	 * Whether the night value is late: in the night window, the device has not read
	 * the night value for at least {@link #NIGHT_LATE_MINUTES} minutes.
	 *
	 * @return true if the night charge is being lost
	 */
	boolean isNightLate() {
		return this.nightLate;
	}

	/**
	 * Evaluates one cycle. A write is armed on {@link #writeElement()} here; the
	 * outcome reaches this schedule through {@link #onExecute} and {@link #verify}.
	 *
	 * <p>
	 * The day value is the safe direction (mains does not charge), so its write is
	 * asymmetric: it is also allowed on battery (state 3), it never latches on
	 * failures or overrides (retries back off up to
	 * {@link #DAY_MAX_BACKOFF_ATTEMPTS}), it has its own daily budget
	 * {@link #MAX_DAILY_DAY_WRITES}, a short startup hold
	 * {@link #DAY_STARTUP_HOLD_CYCLES}, and it becomes the target when the clock
	 * stays unknown for {@link #UNKNOWN_CLOCK_FALLBACK_CYCLES} cycles.
	 *
	 * @param now             plausible local date-time, or null if unknown
	 * @param chargePriority  E20F read-back, or null if unreadable
	 * @param controlEnabled  the settings-write safety gate
	 * @param mains           whether the verified machine-state gate (state 2)
	 *                        holds now
	 * @param running         whether the machine state is 2 or 3 now
	 * @param readbackTimeout cycles allowed for a fresh read-back
	 * @param cooldown        base cycles to wait after a failed or overridden write
	 * @return the result for this cycle
	 */
	Step step(LocalDateTime now, Integer chargePriority, boolean controlEnabled, boolean mains, boolean running,
			int readbackTimeout, int cooldown) {
		if (!this.enabled) {
			return new Step(ModeScheduleState.DISABLED, null, null);
		}
		if (this.invalidReason != null) {
			return new Step(ModeScheduleState.CONFIG_INVALID, null, null);
		}
		if (this.cycles < ModeSchedule.STARTUP_HOLD_CYCLES) {
			this.cycles++;
		}
		var current = this.handler;
		SafeWriteHandler.State handlerState = null;
		if (current != null) {
			current.onCycle(readbackTimeout);
			handlerState = current.getState();
			if ((handlerState == SafeWriteHandler.State.VERIFIED || handlerState == SafeWriteHandler.State.FAILED)
					&& this.cyclesSinceDone < Integer.MAX_VALUE) {
				this.cyclesSinceDone++;
			}
			this.countOutcome(handlerState, now);
		}
		if (now != null) {
			this.lastDate = now.toLocalDate();
			this.unknownClockCycles = 0;
		} else if (this.unknownClockCycles < UNKNOWN_CLOCK_FALLBACK_CYCLES) {
			this.unknownClockCycles++;
		}
		var target = this.targetAt(now == null ? null : now.toLocalTime());
		if (target == null && this.unknownClockCycles >= UNKNOWN_CLOCK_FALLBACK_CYCLES) {
			// The clock stays unknown (e.g. no NTP after a reboot): fall back to the day
			// value, so the grid cannot charge at an unknown time of day.
			target = this.dayPriority;
		}
		this.updateNightLate(now, chargePriority);
		var crossed = now != null && this.crossedBoundaryForward(this.lastNow, now.toLocalTime());
		if (crossed || target != null && !target.equals(this.overrideTarget)) {
			// A new window or target gets a fresh override budget; a forward crossing also
			// refills the failure budget (a clock jump or target flip does not).
			this.overrideTarget = target;
			this.overrides = 0;
			if (crossed) {
				this.failures = 0;
			}
		}
		this.lastNow = now == null ? null : now.toLocalTime();
		if (!controlEnabled) {
			return new Step(ModeScheduleState.DISABLED, target, null);
		}
		var queuedDay = Integer.valueOf(this.dayPriority).equals(this.handlerTarget);
		var gateStillOpen = queuedDay ? running : mains;
		if (handlerState == SafeWriteHandler.State.QUEUED && !gateStillOpen && this.writeElement.withdraw()) {
			// The gate it was queued under is gone before the bridge took the write:
			// withdrawn, re-queued once the gate is confirmed again.
			this.handler = null;
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null, true);
		}
		if (handlerState == SafeWriteHandler.State.QUEUED && (target == null || !target.equals(this.handlerTarget))
				&& this.writeElement.withdraw()) {
			// The time became unknown or the window changed before the bridge took the
			// write: withdraw it and evaluate the current target in this cycle.
			this.handler = null;
			handlerState = null;
		}
		if (handlerState == SafeWriteHandler.State.QUEUED || handlerState == SafeWriteHandler.State.AWAITING_READBACK) {
			return new Step(ModeScheduleState.WRITE_PENDING, target, null);
		}
		if (target == null) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, null, null);
		}
		var day = target == this.dayPriority;
		final var date = now != null ? now.toLocalDate() : this.lastDate;
		var sameTarget = target.equals(this.handlerTarget);
		var failureBackoff = ModeSchedule
				.backoffCycles(day ? Math.min(this.failures, DAY_MAX_BACKOFF_ATTEMPTS) : this.failures, cooldown);
		if (handlerState == SafeWriteHandler.State.FAILED && sameTarget && this.failures > 0
				&& this.cyclesSinceDone < failureBackoff) {
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		if (chargePriority == null) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, target, null);
		}
		if (chargePriority.intValue() == target.intValue()) {
			// Write only on change.
			return new Step(ModeScheduleState.IN_SYNC, target, null);
		}
		if (!day && this.failures >= ModeSchedule.MAX_ATTEMPTS) {
			// Night failure budget for this window spent: latched until the next boundary.
			// The day value never latches.
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		var reCorrection = handlerState == SafeWriteHandler.State.VERIFIED && sameTarget;
		if (reCorrection) {
			if (!day && (this.overrides >= MAX_OVERRIDES_PER_WINDOW
					|| this.overridesOn(date) >= MAX_OVERRIDES_PER_DAY)) {
				return new Step(ModeScheduleState.OVERRIDDEN, target, null);
			}
			var attempts = day ? Math.min(this.overrides + 1, DAY_MAX_BACKOFF_ATTEMPTS) : this.overrides + 1;
			if (this.cyclesSinceDone < ModeSchedule.backoffCycles(attempts, cooldown)) {
				return new Step(ModeScheduleState.WRITE_PENDING, target, null);
			}
		}
		if (day ? this.dayWritesOn(date) >= MAX_DAILY_DAY_WRITES : this.writesOn(date) >= MAX_DAILY_WRITES) {
			return new Step(ModeScheduleState.DAILY_WRITE_LIMIT, target, null);
		}
		var gateConfirmed = day ? running && this.freshRunningReads >= ModeSchedule.MAINS_CONFIRM_READS
				: mains && this.freshMainsReads >= ModeSchedule.MAINS_CONFIRM_READS;
		if (!gateConfirmed) {
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null);
		}
		if (this.cycles < (day ? DAY_STARTUP_HOLD_CYCLES : ModeSchedule.STARTUP_HOLD_CYCLES)) {
			return new Step(ModeScheduleState.STARTUP_HOLD, target, null);
		}
		var next = new SafeWriteHandler();
		next.queueIfChanged(chargePriority, target);
		this.handlerTarget = target;
		this.cyclesSinceDone = 0;
		this.outcomeCounted = false;
		this.queuedAt = now;
		if (reCorrection) {
			this.overrides++;
			this.overridesToday = this.overridesOn(date) + 1;
			this.overridesDate = date;
		}
		this.handler = next;
		this.writeElement.arm(target);
		return new Step(ModeScheduleState.WRITE_PENDING, target, target);
	}

	private void updateNightLate(LocalDateTime now, Integer chargePriority) {
		if (now == null || !this.isNight(now.toLocalTime()) || chargePriority == null
				|| chargePriority == this.nightPriority) {
			this.nightMismatchSince = null;
			this.nightLate = false;
			return;
		}
		if (this.nightMismatchSince == null || now.isBefore(this.nightMismatchSince)) {
			this.nightMismatchSince = now;
		}
		this.nightLate = !now.isBefore(this.nightMismatchSince.plusMinutes(NIGHT_LATE_MINUTES));
	}

	// Counts each write's outcome once: a failure against the window's failure
	// budget, a success against the daily ceiling of its direction.
	private void countOutcome(SafeWriteHandler.State state, LocalDateTime now) {
		if (this.outcomeCounted) {
			return;
		}
		if (state == SafeWriteHandler.State.FAILED) {
			this.failures++;
			this.outcomeCounted = true;
		} else if (state == SafeWriteHandler.State.VERIFIED) {
			this.outcomeCounted = true;
			// While the clock is gated, count against the time the write was queued, or the
			// last known date.
			var at = now != null ? now : this.queuedAt;
			var date = at != null ? at.toLocalDate() : this.lastDate;
			if (Integer.valueOf(this.dayPriority).equals(this.handlerTarget)) {
				this.dayWritesToday = this.dayWritesOn(date) + 1;
				this.dayWritesDate = date;
			} else {
				this.writesToday = this.writesOn(date) + 1;
				this.writesDate = date;
			}
		}
	}

	private int overridesOn(LocalDate date) {
		return date.equals(this.overridesDate) ? this.overridesToday : 0;
	}

	private int writesOn(LocalDate date) {
		return date.equals(this.writesDate) ? this.writesToday : 0;
	}

	private int dayWritesOn(LocalDate date) {
		return date.equals(this.dayWritesDate) ? this.dayWritesToday : 0;
	}

	/**
	 * Execute callback of the E20F write task.
	 *
	 * @param executeState the outcome of the write
	 */
	void onExecute(ExecuteState executeState) {
		var current = this.handler;
		if (current != null) {
			current.onExecute(executeState);
		}
	}

	/**
	 * Read-back of E20F.
	 *
	 * @param actual the value read, or null
	 */
	void verify(Integer actual) {
		var current = this.handler;
		if (current != null) {
			current.verify(actual);
		}
	}

	SafeWriteHandler.State handlerState() {
		var current = this.handler;
		return current == null ? null : current.getState();
	}

	int failuresInWindow() {
		return this.failures;
	}

	int writesTodayForTest(LocalDate date) {
		return this.writesOn(date);
	}

	int dayWritesTodayForTest(LocalDate date) {
		return this.dayWritesOn(date);
	}
}
