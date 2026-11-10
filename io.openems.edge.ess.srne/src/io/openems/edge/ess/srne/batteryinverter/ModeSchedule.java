package io.openems.edge.ess.srne.batteryinverter;

import java.time.LocalTime;
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
 * one-shot), so each write is read-back verified. The caller applies the
 * machine-state gate by passing {@code mains}; nothing is queued while the
 * inverter is not on mains.
 *
 * <p>Fail-safe:
 * <ul>
 * <li>nothing is written while the SoC, E204 or the local time is
 * unreadable,</li>
 * <li>a failed write (execute error, wrong or missing read-back) leaves the
 * device as it is and is retried only after a cooldown,</li>
 * <li>a write that verified but is later overridden (e.g. from the panel) is
 * re-corrected at the same cooldown, never in a burst,</li>
 * <li>an invalid configuration turns the schedule off.</li>
 * </ul>
 *
 * <p>Thread-safety: {@link #step} runs on the Edge cycle thread;
 * {@link #onExecute} and {@link #verify} run on the Modbus bridge thread and
 * only touch the (synchronized) current handler.
 */
final class ModeSchedule {

	static final int SBU = 2;
	static final int SUB = 3;

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
			.withResolverStyle(ResolverStyle.STRICT);

	/**
	 * One evaluation result.
	 *
	 * @param state  the schedule state for this cycle
	 * @param target the target priority for this time of day, or null if unknown
	 * @param write  the value to write to E204 this cycle, or null for no write
	 */
	record Step(ModeScheduleState state, Integer target, Integer write) {
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
	 * @param eveningPriority      E204 value in the evening (2 or 3)
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
	 * Evaluates one cycle. The returned write (if any) must be put on the E204
	 * write element by the caller; its outcome reaches this schedule through
	 * {@link #onExecute} and {@link #verify}.
	 *
	 * @param now             local time, or null if the clock is unreadable
	 * @param soc             battery SoC, or null if unreadable
	 * @param outputPriority  E204 read-back, or null if unreadable
	 * @param controlEnabled  the settings-write safety gate
	 * @param mains           whether the machine state is 2 (mains)
	 * @param readbackTimeout cycles allowed for a fresh read-back
	 * @param cooldown        cycles to wait after a failed or overridden write
	 * @return the result for this cycle
	 */
	Step step(LocalTime now, Integer soc, Integer outputPriority, boolean controlEnabled, boolean mains,
			int readbackTimeout, int cooldown) {
		if (!this.enabled) {
			return new Step(ModeScheduleState.DISABLED, null, null);
		}
		if (this.invalidReason != null) {
			return new Step(ModeScheduleState.CONFIG_INVALID, null, null);
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
		if (!controlEnabled) {
			return new Step(ModeScheduleState.DISABLED, target, null);
		}
		if (handlerState == SafeWriteHandler.State.QUEUED
				|| handlerState == SafeWriteHandler.State.AWAITING_READBACK) {
			return new Step(ModeScheduleState.WRITE_PENDING, target, null);
		}
		var coolingDown = this.cyclesSinceDone < cooldown;
		if (handlerState == SafeWriteHandler.State.FAILED && coolingDown) {
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		if (target == null || outputPriority == null || soc == null || soc < 0 || soc > 100) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, target, null);
		}
		if (outputPriority.intValue() == target.intValue()) {
			// The last handler is kept, so its cooldown still limits a re-correction if
			// the device is overridden again right after.
			return new Step(ModeScheduleState.IN_SYNC, target, null);
		}
		if (handlerState == SafeWriteHandler.State.VERIFIED && target.equals(this.handlerTarget) && coolingDown) {
			// Verified, but the cached read-back is stale or was overridden since:
			// wait out the cooldown before correcting, never a burst.
			return new Step(ModeScheduleState.WRITE_PENDING, target, null);
		}
		if (!mains) {
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null);
		}
		var next = new SafeWriteHandler();
		next.queueIfChanged(outputPriority, target);
		this.handlerTarget = target;
		this.cyclesSinceDone = 0;
		this.handler = next;
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
}
