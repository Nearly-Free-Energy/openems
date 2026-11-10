package io.openems.edge.ess.srne.batteryinverter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Daily output-priority schedule for E204 (0 SOL, 1 UTI, 2 SBU, 3 SUB).
 *
 * <p>The evening priority applies from {@code eveningStart} (inclusive) until
 * {@code daytimeStart} (exclusive), crossing midnight when needed; the daytime
 * priority applies the rest of the day.
 *
 * <p>Writes go through a {@link SafeWriteHandler} (one per write: a handler is
 * one-shot), so each write is read-back verified.
 *
 * <p>Fail-safe:
 * <ul>
 * <li>nothing is written while the SoC, E204 or the local time is unknown; the
 * time counts as known only once it is plausible (see
 * {@link #plausibleTime}),</li>
 * <li>a write is queued only while the machine state is verified (state 2,
 * mains) and the last {@link #MAINS_CONFIRM_READS} fresh reads of it were all
 * verified; a write still queued when mains is lost is withdrawn if the bridge
 * has not taken it yet ({@link GuardedWordElement}), otherwise it stays in
 * flight and counts as an attempt,</li>
 * <li>no write in the first {@link #STARTUP_HOLD_CYCLES} cycles after
 * activation, so a crash/restart loop cannot spend more than one write per
 * hold period,</li>
 * <li>SBU is written only when the caller confirms the SBU preconditions (the
 * same reserve-band / BMS checks as a static outputPriority arm); SUB needs
 * none,</li>
 * <li>a failed write (execute error, wrong or missing read-back) leaves the
 * device as it is; retries back off exponentially and at most
 * {@link #MAX_ATTEMPTS} writes are made per window; the budget is refilled only
 * when the local time crosses a boundary forward in a small step (a clock jump
 * or a target flip does not refill it), and WRITE_FAILED is latched until
 * then,</li>
 * <li>a write that verified but is later overridden (e.g. from the panel) is
 * re-corrected with the same backoff and the same per-window cap,</li>
 * <li>an invalid configuration turns the schedule off.</li>
 * </ul>
 *
 * <p>Thread-safety: {@link #step} and {@link #plausibleTime} run on the Edge
 * cycle thread; {@link #onExecute} and {@link #verify} run on the Modbus bridge
 * thread and only touch the (synchronized) current handler.
 */
final class ModeSchedule {

	static final int SBU = 2;
	static final int SUB = 3;

	/** Writes allowed between two schedule boundaries. */
	static final int MAX_ATTEMPTS = 5;
	/** Consecutive fresh verified machine-state reads needed before a write. */
	static final int MAINS_CONFIRM_READS = 3;
	/** Cycles after activation without any schedule write (about 5 minutes). */
	static final int STARTUP_HOLD_CYCLES = 300;
	/**
	 * Earliest plausible wall-clock time (build-time floor). gw-pi2 has no RTC: a
	 * time before this is an unset clock.
	 */
	static final Instant MIN_PLAUSIBLE_TIME = Instant.parse("2026-11-01T00:00:00Z");
	/**
	 * Cycles the clock must run steadily (no backward step, no forward jump of
	 * more than {@link DischargeGuard#CLOCK_STEP_MINUTES} minutes) after start or
	 * after a step before its time is used. An NTP correction after a reboot is
	 * such a step and restarts the count.
	 */
	static final int CLOCK_SETTLE_CYCLES = 60;

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
			.withResolverStyle(ResolverStyle.STRICT);
	private static final Duration MAX_CLOCK_STEP = Duration.ofMinutes(DischargeGuard.CLOCK_STEP_MINUTES);

	/**
	 * One evaluation result.
	 *
	 * @param state    the schedule state for this cycle
	 * @param target   the target priority for this time of day, or null if unknown
	 * @param write    the value to write to E204 this cycle, or null for no write
	 * @param withdraw true if a queued, not yet executed write must be dropped
	 */
	record Step(ModeScheduleState state, Integer target, Integer write, boolean withdraw) {

		Step(ModeScheduleState state, Integer target, Integer write) {
			this(state, target, write, false);
		}
	}

	private final boolean enabled;
	private final String invalidReason;
	private final int daytimePriority;
	private final int eveningPriority;
	private final LocalTime eveningStart;
	private final LocalTime daytimeStart;

	private volatile SafeWriteHandler handler;
	private Integer handlerTarget;
	private int cyclesSinceDone;
	private final GuardedWordElement writeElement = new GuardedWordElement(0xE204);
	private int attempts;
	private volatile int freshMainsReads;
	private int cycles;
	private LocalTime lastNow;
	private Instant lastInstant;
	private int steadyClockCycles;

	private ModeSchedule(boolean enabled, String invalidReason, int daytimePriority, int eveningPriority,
			LocalTime eveningStart, LocalTime daytimeStart) {
		this.enabled = enabled;
		this.invalidReason = invalidReason;
		this.daytimePriority = daytimePriority;
		this.eveningPriority = eveningPriority;
		this.eveningStart = eveningStart;
		this.daytimeStart = daytimeStart;
	}

	/**
	 * Builds and validates a schedule from configuration values.
	 *
	 * @param enabled              modeScheduleEnabled
	 * @param daytimePriority      E204 value by day (2 or 3)
	 * @param eveningPriority      E204 value in the evening (2 or 3, not the
	 *                             daytime value)
	 * @param eveningStart         "HH:MM" start of the evening priority
	 * @param daytimeStart         "HH:MM" start of the daytime priority
	 * @param staticOutputPriority the static outputPriority target (-1 = unset)
	 * @return the schedule; check {@link #getInvalidReason()}
	 */
	static ModeSchedule fromConfig(boolean enabled, int daytimePriority, int eveningPriority, String eveningStart,
			String daytimeStart, int staticOutputPriority) {
		if (!enabled) {
			return new ModeSchedule(false, null, daytimePriority, eveningPriority, null, null);
		}
		var evening = parseTime(eveningStart);
		var daytime = parseTime(daytimeStart);
		String reason = null;
		if (!isAllowedPriority(daytimePriority) || !isAllowedPriority(eveningPriority)) {
			reason = "daytimePriority [" + daytimePriority + "] and eveningPriority [" + eveningPriority
					+ "] must be 2 (SBU) or 3 (SUB)";
		} else if (daytimePriority == eveningPriority) {
			reason = "daytimePriority and eveningPriority must differ [" + daytimePriority + "]";
		} else if (evening == null || daytime == null) {
			reason = "eveningStart [" + eveningStart + "] and daytimeStart [" + daytimeStart + "] must be HH:MM";
		} else if (evening.equals(daytime)) {
			reason = "eveningStart and daytimeStart must differ";
		} else if (staticOutputPriority != -1) {
			reason = "outputPriority [" + staticOutputPriority
					+ "] must be -1 while the mode schedule is enabled (one owner of E204)";
		}
		return new ModeSchedule(true, reason, daytimePriority, eveningPriority, evening, daytime);
	}

	static boolean isAllowedPriority(int priority) {
		return priority == SBU || priority == SUB;
	}

	static LocalTime parseTime(String value) {
		if (value == null) {
			return null;
		}
		try {
			return LocalTime.parse(value.trim(), TIME_FORMAT);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * Whether the schedule is enabled and valid, i.e. may write.
	 *
	 * @return true if active
	 */
	boolean isActive() {
		return this.enabled && this.invalidReason == null;
	}

	boolean isEnabled() {
		return this.enabled;
	}

	String getInvalidReason() {
		return this.invalidReason;
	}

	/**
	 * The E204 write element of the schedule's own write task.
	 *
	 * @return the element
	 */
	GuardedWordElement writeElement() {
		return this.writeElement;
	}

	/**
	 * Called on every fresh read of the machine state (the channel's
	 * onSetNextValue), from the Modbus bridge thread. Counts consecutive verified
	 * reads, so a cached value can never count twice.
	 *
	 * @param verified whether this read is the verified state 2
	 */
	void onMachineStateRead(boolean verified) {
		this.freshMainsReads = verified ? Math.min(this.freshMainsReads + 1, MAINS_CONFIRM_READS) : 0;
	}

	/**
	 * Whether the local time moved forward across a boundary since the last
	 * cycle, in a step no larger than the clock-step limit.
	 *
	 * @param previous the previous local time, or null
	 * @param now      the current local time
	 * @return true on a forward boundary crossing
	 */
	boolean crossedBoundaryForward(LocalTime previous, LocalTime now) {
		if (previous == null) {
			return false;
		}
		var step = forwardNanos(previous, now);
		if (step == 0 || step > MAX_CLOCK_STEP.toNanos()) {
			return false;
		}
		for (var boundary : new LocalTime[] { this.eveningStart, this.daytimeStart }) {
			var toBoundary = forwardNanos(previous, boundary);
			if (toBoundary > 0 && toBoundary <= step) {
				return true;
			}
		}
		return false;
	}

	private static long forwardNanos(LocalTime from, LocalTime to) {
		var day = Duration.ofDays(1).toNanos();
		return ((to.toNanoOfDay() - from.toNanoOfDay()) % day + day) % day;
	}

	/**
	 * Clock plausibility gate, fed once per cycle. Returns the local time only if
	 * the instant is at or after {@link #MIN_PLAUSIBLE_TIME} and the clock has run
	 * steadily for {@link #CLOCK_SETTLE_CYCLES} cycles; otherwise null (unknown).
	 *
	 * @param instant the edge clock instant, or null if unreadable
	 * @param zone    the schedule time zone
	 * @return the plausible local time, or null
	 */
	LocalTime plausibleTime(Instant instant, ZoneId zone) {
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
		return LocalTime.ofInstant(instant, zone);
	}

	/**
	 * Whether a local time falls in the evening window.
	 *
	 * @param time the local time
	 * @return true from eveningStart (inclusive) to daytimeStart (exclusive)
	 */
	boolean isEvening(LocalTime time) {
		if (this.eveningStart.isBefore(this.daytimeStart)) {
			return !time.isBefore(this.eveningStart) && time.isBefore(this.daytimeStart);
		}
		return !time.isBefore(this.eveningStart) || time.isBefore(this.daytimeStart);
	}

	/**
	 * The target E204 value at a local time.
	 *
	 * @param time the local time, or null if unknown
	 * @return the target priority, or null if inactive or the time is unknown
	 */
	Integer targetAt(LocalTime time) {
		if (!this.isActive() || time == null) {
			return null;
		}
		return this.isEvening(time) ? this.eveningPriority : this.daytimePriority;
	}

	/**
	 * Cycles to wait after the given number of attempts: the base cooldown,
	 * doubled for each further attempt.
	 *
	 * @param attempts writes made in this window (at least 1)
	 * @param cooldown base cooldown in cycles
	 * @return the backoff in cycles
	 */
	static int backoffCycles(int attempts, int cooldown) {
		var shift = Math.min(Math.max(attempts - 1, 0), MAX_ATTEMPTS);
		return cooldown << shift;
	}

	/**
	 * Evaluates one cycle. A write is armed on {@link #writeElement()} here; the
	 * returned write is informational. The outcome of a write reaches this
	 * schedule through {@link #onExecute} and {@link #verify}.
	 *
	 * @param now             plausible local time, or null if unknown
	 * @param soc             battery SoC, or null if unreadable
	 * @param outputPriority  E204 read-back, or null if unreadable
	 * @param controlEnabled  the settings-write safety gate
	 * @param mains           whether the verified machine-state gate holds now;
	 *                        fresh reads are counted by
	 *                        {@link #onMachineStateRead}
	 * @param sbuAllowed      whether the SBU preconditions (reserve band, BMS)
	 *                        are met
	 * @param readbackTimeout cycles allowed for a fresh read-back
	 * @param cooldown        base cycles to wait after a failed or overridden
	 *                        write
	 * @return the result for this cycle
	 */
	Step step(LocalTime now, Integer soc, Integer outputPriority, boolean controlEnabled, boolean mains,
			boolean sbuAllowed, int readbackTimeout, int cooldown) {
		if (!this.enabled) {
			return new Step(ModeScheduleState.DISABLED, null, null);
		}
		if (this.invalidReason != null) {
			return new Step(ModeScheduleState.CONFIG_INVALID, null, null);
		}
		if (this.cycles < STARTUP_HOLD_CYCLES) {
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
		}
		var target = this.targetAt(now);
		if (now != null && this.crossedBoundaryForward(this.lastNow, now)) {
			// A boundary was crossed forward: the new window gets a fresh budget.
			this.attempts = 0;
		}
		this.lastNow = now;
		if (!controlEnabled) {
			return new Step(ModeScheduleState.DISABLED, target, null);
		}
		if (handlerState == SafeWriteHandler.State.QUEUED && !mains && this.writeElement.withdraw()) {
			// Mains lost before the bridge took the write: withdrawn, re-queued once
			// mains is confirmed again. A withdrawn write does not count as an attempt.
			// If the bridge already took it, the write is in flight: the handler is
			// kept and the attempt stays counted.
			this.handler = null;
			this.attempts = Math.max(this.attempts - 1, 0);
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null, true);
		}
		if (handlerState == SafeWriteHandler.State.QUEUED
				|| handlerState == SafeWriteHandler.State.AWAITING_READBACK) {
			return new Step(ModeScheduleState.WRITE_PENDING, target, null);
		}
		if (target == null) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, null, null);
		}
		var sameTarget = target.equals(this.handlerTarget);
		var backingOff = this.attempts > 0 && this.cyclesSinceDone < backoffCycles(this.attempts, cooldown);
		if (handlerState == SafeWriteHandler.State.FAILED && sameTarget && backingOff) {
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		if (outputPriority == null || soc == null || soc < 0 || soc > 100) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, target, null);
		}
		if (outputPriority.intValue() == target.intValue()) {
			// The last handler is kept, so the backoff still limits a re-correction if
			// the device is overridden again right after.
			return new Step(ModeScheduleState.IN_SYNC, target, null);
		}
		if (this.attempts >= MAX_ATTEMPTS) {
			// Budget for this window spent: latched until the next boundary.
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		if (handlerState == SafeWriteHandler.State.VERIFIED && sameTarget && backingOff) {
			// Verified, but the cached read-back is stale or was overridden since:
			// wait out the backoff before correcting, never a burst.
			return new Step(ModeScheduleState.WRITE_PENDING, target, null);
		}
		if (target == SBU && !sbuAllowed) {
			return new Step(ModeScheduleState.BLOCKED_SBU_PRECONDITIONS, target, null);
		}
		if (!mains || this.freshMainsReads < MAINS_CONFIRM_READS) {
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null);
		}
		if (this.cycles < STARTUP_HOLD_CYCLES) {
			return new Step(ModeScheduleState.STARTUP_HOLD, target, null);
		}
		var next = new SafeWriteHandler();
		next.queueIfChanged(outputPriority, target);
		this.handlerTarget = target;
		this.cyclesSinceDone = 0;
		this.attempts++;
		this.handler = next;
		this.writeElement.arm(target);
		return new Step(ModeScheduleState.WRITE_PENDING, target, target);
	}

	/**
	 * Execute callback of the E204 write task.
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
	 * Read-back of E204.
	 *
	 * @param actual the value read, or null
	 */
	void verify(Integer actual) {
		var current = this.handler;
		if (current != null) {
			current.verify(actual);
		}
	}

	/**
	 * The value of the write queued and not yet executed.
	 *
	 * @return the queued target, or null if no write is queued
	 */
	Integer queuedTarget() {
		var current = this.handler;
		return current != null && current.getState() == SafeWriteHandler.State.QUEUED ? this.handlerTarget : null;
	}

	SafeWriteHandler.State handlerState() {
		var current = this.handler;
		return current == null ? null : current.getState();
	}

	int attemptsInWindow() {
		return this.attempts;
	}
}
