package io.openems.edge.ess.srne.batteryinverter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import io.openems.common.types.OptionsEnum;

/**
 * Ends the timed grid charge of charge window 1 once the battery SoC reaches
 * {@code nightChargeStopSoc}.
 *
 * <p>
 * Field finding (Sezibwa, gw-pi2, 13 Nov 2026): E03B (the timed-charge stop
 * SoC) does not end the charge; with a 00:00-06:00 window and E03B=50 the
 * battery charged from 11 % to 99 % at 120 A. So the driver ends the charge
 * itself by disarming the charge schedule (E02C = 0) through the charge
 * {@link ScheduleWindow}, i.e. the same verified enable path as every other
 * schedule write, and re-arms it once the window is over so the next night
 * charges again.
 *
 * <p>
 * Sequence and the {@link ScheduleWindow} invariant (the window registers are
 * written only while the enable reads 0):
 * <ul>
 * <li>Stop: inside the window, after {@link #CONFIRM_READS} consecutive fresh
 * SoC reads at or above the stop SoC, the enable target becomes 0. The window
 * registers already match, so the window only writes E02C = 0 and verifies it;
 * the window registers are never touched.</li>
 * <li>Hysteresis: the stop is latched for the rest of that window; it is never
 * re-armed inside the same window, whatever the SoC does.</li>
 * <li>Re-arm: {@link #REARM_DELAY} after the window stop time the target
 * returns to 1. The window re-checks start/stop against the configuration
 * before it writes E02C = 1 and verifies it; a mismatch goes through the normal
 * disarmed window rewrite first. Re-arming after the window, not at its stop
 * time, keeps a slightly late inverter clock from resuming the charge for a few
 * minutes.</li>
 * <li>Restart: the latch is not persisted. Inside the window (plus the re-arm
 * delay) a disarmed device is only armed again after {@link #CONFIRM_READS}
 * fresh reads below the stop SoC minus {@link #REARM_HYSTERESIS}, so a restart
 * after a stop does not resume the charge. The same holds for the first
 * {@link #ARM_HOLD_CYCLES} evaluations after activation, so a restart on a
 * stale clock (gw-pi2 has no RTC) cannot arm before the clock is
 * corrected.</li>
 * <li>Clock: the time comes through {@link #plausibleTime} (the mode schedule's
 * plausibility and settle rule). The latch is released at once only when the
 * clock crossed the re-arm time forward in small steps; after a clock step or a
 * gap it needs {@link #REARM_CONFIRM} of steady time past the re-arm time, so
 * an NTP or manual step cannot re-arm inside the real window.</li>
 * </ul>
 *
 * <p>
 * Gates: the caller hands the target to the window only while the machine state
 * is verified (the shared settings-write gate). A failed write is retried after
 * {@link #RETRY_COOLDOWN_CYCLES}, at most {@link #MAX_FAILURES} times per
 * target change and local day, so a failed re-arm is retried again the next
 * day. At most {@link #MAX_DAILY_WRITES} disarm writes per local day are
 * started to end a charge; a re-arm (back to the configured enable) is neither
 * counted nor held by that ceiling, so the next night is not lost. An unknown
 * time holds the last decision for at most {@link #UNKNOWN_TIME_CAP_CYCLES}
 * evaluations, then the configured enable is restored (fail open).
 *
 * <p>
 * Thread-safety: {@link #onSocRead} runs on the Modbus bridge thread,
 * {@link #step} on the Edge cycle thread; the read counters are volatile and
 * only written by the bridge thread.
 */
final class NightChargeStop {

	public enum State implements OptionsEnum {
		UNDEFINED(-1), //
		/** The rule is off (default) or inert. */
		OFF(0), //
		/** Outside the charge window: the configured enable applies. */
		OUTSIDE_WINDOW(1), //
		/** Inside the charge window, below the stop SoC: charging allowed. */
		CHARGING(2), //
		/** The stop SoC was reached: the charge schedule is held disarmed. */
		STOPPED(3), //
		/** The device reads disarmed inside the window: not re-armed. */
		HELD_DISARMED(4), //
		/** The local time is unknown: the last decision is held. */
		WAITING_FOR_DATA(5), //
		/** The machine state is not verified: nothing is written. */
		WAITING_FOR_MAINS(6), //
		/** The daily write ceiling is reached: the charge is not ended. */
		DAILY_WRITE_LIMIT(7), //
		/** The enable write failed. */
		WRITE_FAILED(8);

		private final int value;

		private State(int value) {
			this.value = value;
		}

		@Override
		public int getValue() {
			return this.value;
		}

		@Override
		public String getName() {
			return this.name();
		}

		@Override
		public OptionsEnum getUndefined() {
			return UNDEFINED;
		}
	}

	/**
	 * One evaluation result.
	 *
	 * @param enable  the charge-schedule enable to hand to the window this cycle
	 * @param state   the rule state for this cycle
	 * @param message a one-shot audit message, or null
	 */
	record Step(int enable, State state, String message) {
	}

	/** Consecutive fresh SoC reads needed before a stop or an in-window arm. */
	static final int CONFIRM_READS = 3;
	/** SoC points below the stop SoC needed to arm a disarmed device in-window. */
	static final int REARM_HYSTERESIS = 5;
	/** Delay after the window stop time before the schedule is re-armed. */
	static final Duration REARM_DELAY = Duration.ofMinutes(15);
	/** Disarm writes per local day that may be started to end a charge. */
	static final int MAX_DAILY_WRITES = 4;
	/**
	 * Steady clock time past the re-arm time needed to re-arm when the clock did
	 * not cross the re-arm time in small steps (after a clock step or an outage).
	 */
	static final Duration REARM_CONFIRM = Duration.ofHours(2);
	/** Evaluations with an unknown time after which the rule fails open. */
	static final int UNKNOWN_TIME_CAP_CYCLES = 3600;
	/**
	 * Evaluations after activation during which a device that reads disarmed is not
	 * newly armed (unless the SoC is clearly below the stop), so a restart on a
	 * stale clock cannot resume the charge before the clock is corrected.
	 */
	static final int ARM_HOLD_CYCLES = 1800;
	private static final Duration MAX_CLOCK_STEP = Duration.ofMinutes(DischargeGuard.CLOCK_STEP_MINUTES);
	/** Failed writes allowed per target change and local day. */
	static final int MAX_FAILURES = 5;
	/** Cycles to wait after a failed write before a retry. */
	static final int RETRY_COOLDOWN_CYCLES = 60;

	static final NightChargeStop OFF = new NightChargeStop(-1, null, null);

	private final int stopSoc;
	private final LocalTime windowStart;
	private final LocalTime windowStop;

	private volatile int aboveReads;
	private volatile int belowReads;
	private LocalDate latchedOn;
	private Integer applied;
	private boolean ownWrite;
	private int failures;
	private LocalDate failuresDate;
	private int failedCycles;
	private LocalDate writesDate;
	private int writesToday;
	private LocalDateTime lastNow;
	private LocalDateTime beyondSince;
	private int unknownCycles;
	private int evaluations;
	private boolean latchedThisActivation;
	private Instant lastInstant;
	private int steadyClockCycles;

	private NightChargeStop(int stopSoc, LocalTime windowStart, LocalTime windowStop) {
		this.stopSoc = stopSoc;
		this.windowStart = windowStart;
		this.windowStop = windowStop;
	}

	/**
	 * Validates the configuration.
	 *
	 * @param stopSoc nightChargeStopSoc (-1 = off)
	 * @return null if valid (or off), otherwise the reason
	 */
	static String invalidReason(int stopSoc) {
		if (stopSoc == -1 || stopSoc >= 10 && stopSoc <= 100) {
			return null;
		}
		return "nightChargeStopSoc [" + stopSoc + "] must be 10..100 or -1";
	}

	/**
	 * Why a valid, configured rule cannot act, or null if it can.
	 *
	 * @param controlEnabled       the settings-write safety gate
	 * @param chargeScheduleEnable the configured charge-schedule enable
	 * @param start                the configured charge window start (encoded)
	 * @param stop                 the configured charge window stop (encoded)
	 * @return null if the rule can act, otherwise the reason
	 */
	static String inertReason(boolean controlEnabled, int chargeScheduleEnable, int start, int stop) {
		if (!controlEnabled) {
			return "it needs controlEnabled=true";
		}
		if (chargeScheduleEnable != 1) {
			return "it needs chargeScheduleEnable=1";
		}
		if (!ScheduleWindow.isValidWindow(start, stop)) {
			return "it needs a valid charge window 1 (start before stop)";
		}
		return null;
	}

	/**
	 * Builds the rule from the configuration; off unless it is valid and can act.
	 *
	 * @param stopSoc              nightChargeStopSoc (-1 = off)
	 * @param controlEnabled       the settings-write safety gate
	 * @param chargeScheduleEnable the configured charge-schedule enable
	 * @param start                the configured charge window start (encoded)
	 * @param stop                 the configured charge window stop (encoded)
	 * @return the rule, or {@link #OFF}
	 */
	static NightChargeStop fromConfig(int stopSoc, boolean controlEnabled, int chargeScheduleEnable, int start,
			int stop) {
		if (stopSoc == -1 || invalidReason(stopSoc) != null
				|| inertReason(controlEnabled, chargeScheduleEnable, start, stop) != null) {
			return OFF;
		}
		return new NightChargeStop(stopSoc, toTime(start), toTime(stop));
	}

	private static LocalTime toTime(int encoded) {
		return LocalTime.of(encoded >> 8, encoded & 0xFF);
	}

	boolean isActive() {
		return this.stopSoc >= 0;
	}

	/**
	 * Called on every fresh read of the battery SoC (Modbus bridge thread).
	 *
	 * @param soc the SoC in percent, or null if unreadable
	 */
	void onSocRead(Integer soc) {
		if (!this.isActive()) {
			return;
		}
		var usable = soc != null && soc >= 0 && soc <= 100;
		var above = usable && soc >= this.stopSoc;
		var below = usable && soc < this.stopSoc - REARM_HYSTERESIS;
		this.aboveReads = above ? Math.min(this.aboveReads + 1, CONFIRM_READS) : 0;
		this.belowReads = below ? Math.min(this.belowReads + 1, CONFIRM_READS) : 0;
	}

	boolean isInsideWindow(LocalTime time) {
		return !time.isBefore(this.windowStart) && time.isBefore(this.windowStop);
	}

	// The window plus the re-arm delay, today's or yesterday's (the delay may cross
	// midnight for a window that ends late).
	private boolean isInHoldSpan(LocalDateTime now) {
		for (var date : new LocalDate[] { now.toLocalDate(), now.toLocalDate().minusDays(1) }) {
			if (!now.isBefore(date.atTime(this.windowStart)) && now.isBefore(this.rearmAt(date))) {
				return true;
			}
		}
		return false;
	}

	private LocalDateTime rearmAt(LocalDate date) {
		return date.atTime(this.windowStop).plus(REARM_DELAY);
	}

	/**
	 * Clock plausibility gate, fed once per cycle, the same rule as
	 * {@link ModeSchedule#plausibleTime}: the local time is used only at or after
	 * {@link ModeSchedule#MIN_PLAUSIBLE_TIME} and once the clock has run steadily
	 * (no backward step, no forward jump of more than
	 * {@link DischargeGuard#CLOCK_STEP_MINUTES} minutes) for
	 * {@link ModeSchedule#CLOCK_SETTLE_CYCLES} cycles; otherwise null.
	 *
	 * @param instant the edge clock instant, or null if unreadable
	 * @param zone    the schedule time zone
	 * @return the plausible local date-time, or null
	 */
	LocalDateTime plausibleTime(Instant instant, ZoneId zone) {
		if (instant == null || instant.isBefore(ModeSchedule.MIN_PLAUSIBLE_TIME)) {
			this.lastInstant = null;
			this.steadyClockCycles = 0;
			return null;
		}
		if (this.lastInstant != null) {
			var delta = Duration.between(this.lastInstant, instant);
			if (delta.isNegative() || delta.compareTo(MAX_CLOCK_STEP) > 0) {
				this.steadyClockCycles = 0;
			} else if (this.steadyClockCycles < ModeSchedule.CLOCK_SETTLE_CYCLES) {
				this.steadyClockCycles++;
			}
		}
		this.lastInstant = instant;
		if (this.steadyClockCycles < ModeSchedule.CLOCK_SETTLE_CYCLES) {
			return null;
		}
		return LocalDateTime.ofInstant(instant, zone);
	}

	// Releases the latch once the re-arm time is passed: at once when the clock
	// crossed it forward in small steps, otherwise only after REARM_CONFIRM of
	// steady time past it, so a clock step (NTP, manual) or a stale clock cannot
	// re-arm the charge inside the real window.
	private boolean releaseLatch(LocalDateTime now, boolean continuous) {
		var rearm = this.rearmAt(this.latchedOn);
		if (now.isBefore(rearm)) {
			this.beyondSince = null;
			return false;
		}
		if (continuous && this.lastNow.isBefore(rearm)) {
			return true;
		}
		if (this.beyondSince == null) {
			this.beyondSince = now;
			return false;
		}
		return !now.isBefore(this.beyondSince.plus(REARM_CONFIRM));
	}

	/**
	 * Evaluates one cycle and hands the resulting enable target to the window: a
	 * settled or failed window is re-opened when the target changes, and a failed
	 * write is retried after a cooldown within the failure budget. Call only while
	 * the machine-state gate holds, right before the window is reconciled.
	 *
	 * @param now          the plausible local date-time, or null if unknown
	 * @param deviceEnable the read-back charge-schedule enable, or null
	 * @param window       the charge window
	 * @return the result for this cycle
	 */
	Step step(LocalDateTime now, Integer deviceEnable, ScheduleWindow window) {
		if (!this.isActive()) {
			return new Step(1, State.OFF, null);
		}
		if (this.evaluations < ARM_HOLD_CYCLES) {
			this.evaluations++;
		}
		if (now == null) {
			this.lastNow = null;
			this.beyondSince = null;
			if (this.unknownCycles < UNKNOWN_TIME_CAP_CYCLES) {
				this.unknownCycles++;
				return new Step(this.applied == null ? 1 : this.applied, State.WAITING_FOR_DATA, null);
			}
			// Fail open: an unknown time must not hold the charge off for good.
			String message = Integer.valueOf(0).equals(this.applied)
					? "Night charge stop: time unknown for too long; restoring the configured enable"
					: null;
			var failedState = this.handOver(1, deviceEnable, null, window);
			return new Step(this.applied, failedState != null ? failedState : State.WAITING_FOR_DATA, message);
		}
		this.unknownCycles = 0;
		var continuous = this.lastNow != null && !now.isBefore(this.lastNow)
				&& Duration.between(this.lastNow, now).compareTo(MAX_CLOCK_STEP) <= 0;
		if (!continuous) {
			this.beyondSince = null;
		}
		String message = null;
		if (this.latchedOn != null && this.releaseLatch(now, continuous)) {
			this.latchedOn = null;
			this.beyondSince = null;
			message = "Night charge window over; re-arming the charge schedule";
		}
		this.lastNow = now;
		var inside = this.isInsideWindow(now.toLocalTime());
		var deviceDisarmed = Integer.valueOf(0).equals(deviceEnable);
		var limit = false;
		if (this.latchedOn == null && inside && this.aboveReads >= CONFIRM_READS) {
			if (deviceDisarmed || this.writesOn(now.toLocalDate()) < MAX_DAILY_WRITES) {
				this.latchedOn = now.toLocalDate();
				this.latchedThisActivation = true;
				message = "Night charge stop SoC [" + this.stopSoc + "] reached; ending the charge for this window";
			} else {
				limit = true;
			}
		}
		int target;
		State state;
		if (this.latchedOn != null) {
			target = 0;
			state = State.STOPPED;
		} else if (deviceDisarmed && this.belowReads < CONFIRM_READS
				&& (this.isInHoldSpan(now) || !this.latchedThisActivation && this.evaluations < ARM_HOLD_CYCLES)) {
			// Never re-armed in the window (plus the delay) after a stop this instance did
			// not see, and not right after activation, when the clock may still be stale.
			target = 0;
			state = State.HELD_DISARMED;
		} else {
			target = 1;
			state = inside ? State.CHARGING : State.OUTSIDE_WINDOW;
		}
		if (limit) {
			state = State.DAILY_WRITE_LIMIT;
		}
		var failedState = this.handOver(target, deviceEnable, now.toLocalDate(), window);
		if (failedState != null) {
			state = failedState;
		}
		return new Step(this.applied, state, message);
	}

	// Moves the target to the window, or retries a failed write of this rule.
	// Returns a failure state to report, or null.
	private State handOver(int target, Integer deviceEnable, LocalDate date, ScheduleWindow window) {
		if (this.applied == null) {
			// First evaluation: the window has not run yet and starts from this target.
			this.applied = target;
			this.ownWrite = deviceEnable != null && deviceEnable != target;
			this.countWrite(date, deviceEnable, target);
			return null;
		}
		if (this.applied != target) {
			// A running sequence follows the live target; a settled or failed one is
			// re-opened so the change goes through the verified path.
			var state = window.getState();
			if (state == ScheduleWindow.State.IDLE || window.isSequenceInProgress() || window.reopen()) {
				this.applied = target;
				this.ownWrite = true;
				this.failures = 0;
				this.failedCycles = 0;
				this.countWrite(date, deviceEnable, target);
			}
			return null;
		}
		if (!this.ownWrite || !window.isRetryableFailure()) {
			this.failedCycles = 0;
			return null;
		}
		if (date != null && !date.equals(this.failuresDate)) {
			// A new day refills the budget, so a failed re-arm is retried every day.
			this.failures = 0;
			this.failuresDate = date;
		}
		if (this.failures >= MAX_FAILURES) {
			return State.WRITE_FAILED;
		}
		if (target == 0 && this.writesOn(date) >= MAX_DAILY_WRITES) {
			return State.DAILY_WRITE_LIMIT;
		}
		if (++this.failedCycles >= RETRY_COOLDOWN_CYCLES && window.reopen()) {
			this.failures++;
			this.failedCycles = 0;
			this.countWrite(date, deviceEnable, target);
		}
		return State.WRITE_FAILED;
	}

	// Counts a disarm write against the daily ceiling; a re-arm is never counted.
	private void countWrite(LocalDate date, Integer deviceEnable, int target) {
		if (target != 0 || date == null || deviceEnable != null && deviceEnable == 0) {
			return;
		}
		this.writesToday = this.writesOn(date) + 1;
		this.writesDate = date;
	}

	private int writesOn(LocalDate date) {
		return date.equals(this.writesDate) ? this.writesToday : 0;
	}

	int failuresForTest() {
		return this.failures;
	}

	int writesTodayForTest(LocalDate date) {
		return this.writesOn(date);
	}
}
