package io.openems.edge.ess.srne.batteryinverter;

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
 * <li>nothing is written while E20F or the local time is unknown; the time
 * counts only once plausible ({@link ClockGate}),</li>
 * <li>a write is queued only while the machine state is verified (state 2,
 * mains), confirmed by the last {@link ModeSchedule#MAINS_CONFIRM_READS} fresh
 * reads; a write still queued when mains is lost is withdrawn if the bridge has
 * not taken it yet ({@link GuardedWordElement}),</li>
 * <li>no write in the first {@link ModeSchedule#STARTUP_HOLD_CYCLES} cycles
 * after activation,</li>
 * <li>a failed write (execute error, wrong or missing read-back) leaves the
 * device as it is; retries back off exponentially, at most
 * {@link ModeSchedule#MAX_ATTEMPTS} failed writes per window, refilled only
 * when the local time crosses a boundary forward in a small step,</li>
 * <li>at most {@link #MAX_DAILY_WRITES} successful writes per local day,</li>
 * <li>an overridden value (panel, app) is re-corrected with backoff, at most
 * {@link #MAX_OVERRIDES_PER_WINDOW} times per window and
 * {@link #MAX_OVERRIDES_PER_DAY} times per day,</li>
 * <li>an invalid configuration turns the schedule off.</li>
 * </ul>
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

	/** Successful E20F writes allowed per local day (two scheduled + overrides). */
	static final int MAX_DAILY_WRITES = 6;
	/** Re-corrections of an overridden E20F allowed per window. */
	static final int MAX_OVERRIDES_PER_WINDOW = 2;
	/** Re-corrections of an overridden E20F allowed per local day. */
	static final int MAX_OVERRIDES_PER_DAY = 3;

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
	 * thread. Counts consecutive reads of state 2 (mains).
	 *
	 * @param state the raw machine state, or null if unreadable
	 */
	void onMachineStateRead(Integer state) {
		var mainsRead = state != null && state == 2;
		this.freshMainsReads = mainsRead ? Math.min(this.freshMainsReads + 1, ModeSchedule.MAINS_CONFIRM_READS) : 0;
	}

	boolean crossedBoundaryForward(LocalTime previous, LocalTime now) {
		return ClockGate.crossedForward(previous, now, this.nightFrom, this.nightTo);
	}

	/**
	 * Evaluates one cycle. A write is armed on {@link #writeElement()} here; the
	 * outcome reaches this schedule through {@link #onExecute} and {@link #verify}.
	 *
	 * @param now             plausible local date-time, or null if unknown
	 * @param chargePriority  E20F read-back, or null if unreadable
	 * @param controlEnabled  the settings-write safety gate
	 * @param mains           whether the verified machine-state gate holds now
	 * @param readbackTimeout cycles allowed for a fresh read-back
	 * @param cooldown        base cycles to wait after a failed or overridden write
	 * @return the result for this cycle
	 */
	Step step(LocalDateTime now, Integer chargePriority, boolean controlEnabled, boolean mains, int readbackTimeout,
			int cooldown) {
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
		var target = this.targetAt(now == null ? null : now.toLocalTime());
		if (target != null && !target.equals(this.overrideTarget)) {
			this.overrideTarget = target;
			this.overrides = 0;
		}
		if (now != null && this.crossedBoundaryForward(this.lastNow, now.toLocalTime())) {
			// A boundary was crossed forward: the new window gets fresh budgets.
			this.failures = 0;
			this.overrides = 0;
		}
		this.lastNow = now == null ? null : now.toLocalTime();
		if (!controlEnabled) {
			return new Step(ModeScheduleState.DISABLED, target, null);
		}
		if (handlerState == SafeWriteHandler.State.QUEUED && !mains && this.writeElement.withdraw()) {
			// Mains lost before the bridge took the write: withdrawn, re-queued once
			// mains is confirmed again.
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
		var sameTarget = target.equals(this.handlerTarget);
		if (handlerState == SafeWriteHandler.State.FAILED && sameTarget && this.failures > 0
				&& this.cyclesSinceDone < ModeSchedule.backoffCycles(this.failures, cooldown)) {
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		if (chargePriority == null) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, target, null);
		}
		if (chargePriority.intValue() == target.intValue()) {
			// Write only on change.
			return new Step(ModeScheduleState.IN_SYNC, target, null);
		}
		if (this.failures >= ModeSchedule.MAX_ATTEMPTS) {
			// Failure budget for this window spent: latched until the next boundary.
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		var reCorrection = handlerState == SafeWriteHandler.State.VERIFIED && sameTarget;
		if (reCorrection) {
			if (this.overrides >= MAX_OVERRIDES_PER_WINDOW
					|| this.overridesOn(now.toLocalDate()) >= MAX_OVERRIDES_PER_DAY) {
				return new Step(ModeScheduleState.OVERRIDDEN, target, null);
			}
			if (this.cyclesSinceDone < ModeSchedule.backoffCycles(this.overrides + 1, cooldown)) {
				return new Step(ModeScheduleState.WRITE_PENDING, target, null);
			}
		}
		if (this.writesOn(now.toLocalDate()) >= MAX_DAILY_WRITES) {
			return new Step(ModeScheduleState.DAILY_WRITE_LIMIT, target, null);
		}
		if (!mains || this.freshMainsReads < ModeSchedule.MAINS_CONFIRM_READS) {
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null);
		}
		if (this.cycles < ModeSchedule.STARTUP_HOLD_CYCLES) {
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
			this.overridesToday = this.overridesOn(now.toLocalDate()) + 1;
			this.overridesDate = now.toLocalDate();
		}
		this.handler = next;
		this.writeElement.arm(target);
		return new Step(ModeScheduleState.WRITE_PENDING, target, target);
	}

	// Counts each write's outcome once: a failure against the window's failure
	// budget, a success against the daily ceiling.
	private void countOutcome(SafeWriteHandler.State state, LocalDateTime now) {
		if (this.outcomeCounted) {
			return;
		}
		if (state == SafeWriteHandler.State.FAILED) {
			this.failures++;
			this.outcomeCounted = true;
		} else if (state == SafeWriteHandler.State.VERIFIED) {
			this.outcomeCounted = true;
			// While the clock is gated, count against the time the write was queued.
			var at = now != null ? now : this.queuedAt;
			if (at != null) {
				var date = at.toLocalDate();
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
}
