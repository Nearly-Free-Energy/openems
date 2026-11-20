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
 * stale clock (gw-pi2 has no RTC) cannot arm before the clock is corrected.
 * Before the first decision (the time not known yet) the window gets
 * {@link #NO_TARGET} and is left as the device has it, so a restart after a
 * stop, on battery or on mains, neither arms the timer nor lets the
 * charge-priority schedule raise its night value before the rule has decided;
 * only a clearly low SoC resumes the charge afterwards.</li>
 * <li>Clock: the time comes through {@link #plausibleTime} (the mode schedule's
 * plausibility and settle rule). The latch is released at once only when the
 * clock crossed the re-arm time forward in small steps; after a clock step or a
 * gap it needs {@link #REARM_CONFIRM} of steady time past the re-arm time, so
 * an NTP or manual step cannot re-arm inside the real window.</li>
 * <li>Charge priority: with E20F at the night value (hybrid) the unit charges
 * from mains at any hour on the line, timer or not, so disarming E02C alone
 * does not end the charge. {@link #hasEndedCharge} tells the
 * {@link ChargePrioritySchedule} to end its night value for the rest of that
 * night, and {@link #isNightChargePending} holds the night value back until
 * this rule has decided after an activation.</li>
 * <li>One-night override ({@link #writeOverride}, ADR-017): the stop SoC of a
 * window is chosen once, at the first known time inside that window or its
 * re-arm delay. An override written after the previous window ended (re-arm
 * time) and before this window started is used if it is within
 * {@link #OVERRIDE_MIN_SOC}..{@link #OVERRIDE_MAX_SOC}; otherwise the
 * configured stop SoC applies, and an out-of-range override is rejected for
 * that window. The choice holds until the re-arm time of its window, even if
 * the latch is not released yet (an outage or a clock step), and is made again
 * for every new window, so a stuck latch never carries an override into the
 * next night; then the override is cleared. An override whose write time lies
 * after the current time (written on a clock that ran ahead) is dropped. The
 * choice depends only on the override and the time it was written, both
 * persisted by the caller, so a restart inside the window re-derives the same
 * value, and a restart after the window never applies an override that was
 * already cleared. A write inside the window changes nothing and is ignored.
 * The override changes only the SoC at which the stop latches; it writes no
 * register.</li>
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
		WRITE_FAILED(8), //
		/**
		 * Held disarmed inside the window for {@link #HELD_CONFIRM_CYCLES} evaluations
		 * with the SoC unknown: the night charge is blocked (E02C held at 0, the night
		 * charge priority held back) until the SoC reads again or the re-arm time.
		 */
		HELD_SOC_UNKNOWN(9);

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

	/** Where the stop SoC in effect comes from. */
	public enum SocSource implements OptionsEnum {
		UNDEFINED(-1), //
		/** The configured nightChargeStopSoc. */
		CONFIG(0), //
		/** The one-night override (NightChargeStopSocTonight). */
		OVERRIDE(1);

		private final int value;

		private SocSource(int value) {
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
	 * @param enable  the charge-schedule enable to hand to the window this cycle,
	 *                or {@link #NO_TARGET} to leave the window untouched
	 * @param state   the rule state for this cycle
	 * @param message a one-shot audit message, or null
	 * @param warning a one-shot warning (a rejected override), or null
	 */
	record Step(int enable, State state, String message, String warning) {

		Step(int enable, State state, String message) {
			this(enable, state, message, null);
		}
	}

	/**
	 * A one-night override of the stop SoC.
	 *
	 * @param soc       the written stop SoC in percent
	 * @param writtenAt the local time it was written (schedule time zone)
	 */
	record SocOverride(int soc, LocalDateTime writtenAt) {
	}

	/**
	 * {@link Step#enable} before the rule has decided after an activation: the
	 * charge window is not reconciled at all, so it neither arms nor disarms the
	 * device.
	 */
	static final int NO_TARGET = -1;
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
	/**
	 * Evaluations a device must be held disarmed inside the window, with the SoC
	 * not clearly below the stop, before the hold counts as an ended charge.
	 */
	static final int HELD_CONFIRM_CYCLES = 30;
	/** Failed writes allowed per target change and local day. */
	static final int MAX_FAILURES = 5;
	/** Cycles to wait after a failed write before a retry. */
	static final int RETRY_COOLDOWN_CYCLES = 60;
	/** Lowest accepted one-night override of the stop SoC. */
	static final int OVERRIDE_MIN_SOC = 30;
	/** Highest accepted one-night override of the stop SoC. */
	static final int OVERRIDE_MAX_SOC = 90;

	static final NightChargeStop OFF = new NightChargeStop(-1, null, null);

	private final int configStopSoc;
	private final LocalTime windowStart;
	private final LocalTime windowStop;

	// The stop SoC in effect: written by the cycle thread, read by the bridge
	// thread. The read counters are kept for countedFor only; a change of the stop
	// SoC restarts them (bridge thread) and makes them count as zero until then.
	private volatile int stopSoc;
	private volatile int countedFor;
	private volatile int aboveReads;
	private volatile int belowReads;
	private volatile int bandReads;
	private volatile boolean socKnown;
	// The latch and the hold are keyed to the local date. That is the date the
	// window opened because the charge window never crosses midnight
	// (ScheduleWindow.isValidWindow rejects start >= stop, see inertReason).
	private LocalDate latchedOn;
	private LocalDate heldOn;
	private int heldCycles;
	private boolean decided;
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
	private LocalDate socUnknownReportedOn;
	private String pendingMessage;
	private Instant lastInstant;
	private int steadyClockCycles;
	private SocOverride override;
	// The window whose stop SoC is chosen, keyed to its start date like the latch.
	private LocalDate socWindow;
	private SocSource socSource = SocSource.CONFIG;
	private boolean overrideRejected;
	private String pendingWarning;
	private boolean overrideCleared;

	private NightChargeStop(int stopSoc, LocalTime windowStart, LocalTime windowStop) {
		this.configStopSoc = stopSoc;
		this.stopSoc = stopSoc;
		this.countedFor = stopSoc;
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
		return this.configStopSoc >= 0;
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
		var stop = this.stopSoc;
		if (stop != this.countedFor) {
			this.aboveReads = 0;
			this.belowReads = 0;
			this.bandReads = 0;
			this.countedFor = stop;
		}
		var usable = soc != null && soc >= 0 && soc <= 100;
		this.socKnown = usable;
		var above = usable && soc >= stop;
		var below = usable && soc < stop - REARM_HYSTERESIS;
		this.aboveReads = above ? Math.min(this.aboveReads + 1, CONFIRM_READS) : 0;
		this.belowReads = below ? Math.min(this.belowReads + 1, CONFIRM_READS) : 0;
		var inBand = usable && soc >= stop - REARM_HYSTERESIS;
		this.bandReads = inBand ? Math.min(this.bandReads + 1, CONFIRM_READS) : 0;
	}

	// The read counters, zero while they were counted for another stop SoC.
	private int aboveReads() {
		return this.countedFor == this.stopSoc ? this.aboveReads : 0;
	}

	private int belowReads() {
		return this.countedFor == this.stopSoc ? this.belowReads : 0;
	}

	private int bandReads() {
		return this.countedFor == this.stopSoc ? this.bandReads : 0;
	}

	boolean isInsideWindow(LocalTime time) {
		return !time.isBefore(this.windowStart) && time.isBefore(this.windowStop);
	}

	// The window plus the re-arm delay, today's or yesterday's (the delay may cross
	// midnight for a window that ends late).
	private boolean isInHoldSpan(LocalDateTime now) {
		return this.holdSpanDate(now) != null;
	}

	// The start date of the window whose hold span contains the time, or null.
	private LocalDate holdSpanDate(LocalDateTime now) {
		for (var date : new LocalDate[] { now.toLocalDate(), now.toLocalDate().minusDays(1) }) {
			if (!now.isBefore(date.atTime(this.windowStart)) && now.isBefore(this.rearmAt(date))) {
				return date;
			}
		}
		return null;
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
		var now = this.settledTime(instant, zone);
		// The time is observed here every cycle, also while the machine-state gate
		// keeps step() from running, so an outage spanning the re-arm time is not
		// taken for a clock step.
		if (now == null) {
			this.lastNow = null;
			this.beyondSince = null;
		} else {
			var message = this.observe(now);
			if (message != null) {
				this.pendingMessage = message;
			}
		}
		return now;
	}

	private LocalDateTime settledTime(Instant instant, ZoneId zone) {
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

	// Tracks clock continuity and releases the latch once the re-arm time is
	// passed. Idempotent for one time: the gate and step() both call it.
	private String observe(LocalDateTime now) {
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
		return message;
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
				// Before the first decision the window gets no target at all, so a restart
				// after a stop cannot arm a disarmed device before the rule has seen the
				// time; later the last decision is held.
				return new Step(this.decided ? this.applied : NO_TARGET, State.WAITING_FOR_DATA, null);
			}
			// Fail open: an unknown time must not hold the charge off for good.
			String message = Integer.valueOf(0).equals(this.applied)
					? "Night charge stop: time unknown for too long; restoring the configured enable"
					: null;
			var failedState = this.handOver(1, deviceEnable, null, window);
			return new Step(this.applied, failedState != null ? failedState : State.WAITING_FOR_DATA, message);
		}
		this.unknownCycles = 0;
		this.decided = true;
		var message = this.observe(now);
		if (message == null) {
			message = this.pendingMessage;
		}
		this.pendingMessage = null;
		message = join(message, this.updateWindowSoc(now));
		final var warning = this.pendingWarning;
		this.pendingWarning = null;
		var inside = this.isInsideWindow(now.toLocalTime());
		var deviceDisarmed = Integer.valueOf(0).equals(deviceEnable);
		var limit = false;
		var latchedBefore = this.latchedOn != null && this.latchedOn.isBefore(now.toLocalDate());
		if ((this.latchedOn == null || latchedBefore) && inside && this.aboveReads() >= CONFIRM_READS) {
			// A latch of an earlier window that is not released yet (its re-arm delayed by
			// an outage or a clock step) moves to this window once the stop SoC is
			// confirmed here, so this night's charge is ended too.
			if (deviceDisarmed || this.writesOn(now.toLocalDate()) < MAX_DAILY_WRITES) {
				this.latchedOn = now.toLocalDate();
				this.latchedThisActivation = true;
				message = "Night charge stop SoC [" + this.stopSoc + "] (" + this.socSource
						+ ") reached; ending the charge for this window";
			} else {
				limit = true;
			}
		}
		int target;
		State state;
		var heldInside = false;
		if (this.latchedOn != null) {
			target = 0;
			state = State.STOPPED;
		} else if (deviceDisarmed && this.belowReads() < CONFIRM_READS
				&& (this.isInHoldSpan(now) || !this.latchedThisActivation && this.evaluations < ARM_HOLD_CYCLES)) {
			// Never re-armed in the window (plus the delay) after a stop this instance did
			// not see, and not right after activation, when the clock may still be stale.
			target = 0;
			state = State.HELD_DISARMED;
			heldInside = inside;
		} else {
			target = 1;
			state = inside ? State.CHARGING : State.OUTSIDE_WINDOW;
		}
		if (heldInside && now.toLocalDate().equals(this.heldOn)) {
			this.heldCycles = Math.min(this.heldCycles + 1, HELD_CONFIRM_CYCLES);
		} else {
			this.heldOn = heldInside ? now.toLocalDate() : null;
			this.heldCycles = heldInside ? 1 : 0;
		}
		var socUnknownHeld = heldInside && !this.socKnown && this.heldCycles >= HELD_CONFIRM_CYCLES;
		if (this.heldOn == null || !this.heldOn.equals(this.socUnknownReportedOn)) {
			// Reported once per held window and date: a flaky SoC register that comes and
			// goes while the hold lasts does not log again.
			this.socUnknownReportedOn = null;
		}
		if (socUnknownHeld) {
			state = State.HELD_SOC_UNKNOWN;
			if (this.socUnknownReportedOn == null && message == null) {
				message = "Night charge stop: the device reads disarmed in the window and the SoC is unknown;"
						+ " the night charge is blocked until the SoC reads again or the re-arm time";
				this.socUnknownReportedOn = this.heldOn;
			}
		}
		if (limit) {
			state = State.DAILY_WRITE_LIMIT;
		}
		var failedState = this.handOver(target, deviceEnable, now.toLocalDate(), window);
		if (failedState != null) {
			state = failedState;
		}
		return new Step(this.applied, state, message, warning);
	}

	/**
	 * Writes the one-night override of the stop SoC (Edge cycle thread). It is
	 * taken by the next window; inside a window (or its re-arm delay) it is
	 * ignored, because it would neither change the running window nor outlive it. A
	 * latch that is not released yet after the window does not block it.
	 *
	 * @param soc the stop SoC in percent, or null (or negative) to cancel
	 * @param now the plausible local date-time of the write, not null
	 * @return null if taken (the caller persists it with {@code now}), otherwise
	 *         why it was ignored
	 */
	String writeOverride(Integer soc, LocalDateTime now) {
		if (!this.isActive()) {
			return "the night charge stop is off";
		}
		if (this.socWindow != null || this.isInHoldSpan(now)) {
			return "written during the night charge window; write it after the window is over";
		}
		this.override = soc == null || soc < 0 ? null : new SocOverride(soc, now);
		return null;
	}

	/**
	 * Restores a persisted override after an activation, before the first
	 * evaluation. One that already expired is cleared at the first known time and
	 * never applied.
	 *
	 * @param soc       the persisted stop SoC, or negative for none
	 * @param writtenAt the local time it was written, or null for none
	 */
	void restoreOverride(int soc, LocalDateTime writtenAt) {
		if (this.isActive() && soc >= 0 && writtenAt != null) {
			this.override = new SocOverride(soc, writtenAt);
		}
	}

	// Ends the stop SoC choice of a window once its re-arm time has passed or the
	// time is in another window, whether or not the latch is released (a stuck
	// latch moves to the new window only at the new window's stop SoC), clears an
	// override whose night is over or whose write time lies in the future, and
	// chooses the stop SoC for the window that holds the time. Runs before the
	// latch check.
	private String updateWindowSoc(LocalDateTime now) {
		String message = null;
		var holdDate = this.holdSpanDate(now);
		if (this.socWindow != null && (!now.isBefore(this.rearmAt(this.socWindow))
				|| holdDate != null && !holdDate.equals(this.socWindow))) {
			this.socWindow = null;
			this.stopSoc = this.configStopSoc;
			this.socSource = SocSource.CONFIG;
			this.overrideRejected = false;
		}
		if (this.socWindow == null && this.override != null) {
			if (this.override.writtenAt().isAfter(now)) {
				this.pendingWarning = "Night charge stop: the one-night stop SoC override [" + this.override.soc()
						+ "] was written at " + this.override.writtenAt()
						+ ", after the current time (clock ahead at the write); dropped";
				this.override = null;
				this.overrideCleared = true;
			} else if (!now.isBefore(this.expiry(this.override))) {
				this.override = null;
				this.overrideCleared = true;
				message = "Night charge stop: the one-night stop SoC override is cleared";
			}
		}
		var date = this.socWindow == null ? holdDate : null;
		if (date != null) {
			message = join(message, this.chooseWindowSoc(date));
		}
		return message;
	}

	// Returns an audit message for an override taken, or null.
	private String chooseWindowSoc(LocalDate date) {
		this.socWindow = date;
		var candidate = this.override != null && date.equals(this.targetDate(this.override.writtenAt()))
				? this.override.soc()
				: null;
		if (candidate != null && candidate >= OVERRIDE_MIN_SOC && candidate <= OVERRIDE_MAX_SOC) {
			this.stopSoc = candidate;
			this.socSource = SocSource.OVERRIDE;
			this.overrideRejected = false;
			return "Night charge stop: using the one-night stop SoC override [" + candidate
					+ "] for this window (config [" + this.configStopSoc + "])";
		}
		this.stopSoc = this.configStopSoc;
		this.socSource = SocSource.CONFIG;
		this.overrideRejected = candidate != null;
		if (candidate != null) {
			this.pendingWarning = "Night charge stop: one-night stop SoC override [" + candidate + "] is outside "
					+ OVERRIDE_MIN_SOC + ".." + OVERRIDE_MAX_SOC + "; using the configured [" + this.configStopSoc
					+ "] for this window";
		}
		return null;
	}

	// The start date of the window an override written at this time is for: the
	// first window that starts after it. Null if it was written inside a window
	// (or its re-arm delay): that window was already chosen and it ends the
	// override.
	private LocalDate targetDate(LocalDateTime writtenAt) {
		if (this.holdSpanDate(writtenAt) != null) {
			return null;
		}
		var date = writtenAt.toLocalDate();
		return writtenAt.toLocalTime().isBefore(this.windowStart) ? date : date.plusDays(1);
	}

	// When the override's night is over: the re-arm time of its window, or of the
	// window it was written in.
	private LocalDateTime expiry(SocOverride override) {
		var date = this.targetDate(override.writtenAt());
		return this.rearmAt(date != null ? date : this.holdSpanDate(override.writtenAt()));
	}

	private static String join(String first, String second) {
		if (first == null) {
			return second;
		}
		return second == null ? first : first + "; " + second;
	}

	/**
	 * The stop SoC in effect: the one chosen for the current window, otherwise the
	 * configured one. Edge cycle thread only.
	 *
	 * @return the stop SoC in percent, or -1 if the rule is off
	 */
	int getAppliedStopSoc() {
		return this.isActive() ? this.stopSoc : -1;
	}

	/**
	 * Where {@link #getAppliedStopSoc} comes from. Edge cycle thread only.
	 *
	 * @return the source
	 */
	SocSource getStopSocSource() {
		return this.socSource;
	}

	/**
	 * Whether the override was rejected (out of range) for the current window. Edge
	 * cycle thread only.
	 *
	 * @return true while the window runs on the configured stop SoC instead
	 */
	boolean isOverrideRejected() {
		return this.overrideRejected;
	}

	/**
	 * Whether the override was cleared (its night over, or dropped) since the last
	 * call, so the caller deletes its persisted copy. Edge cycle thread only.
	 *
	 * @return true once per clear
	 */
	boolean takeOverrideCleared() {
		var cleared = this.overrideCleared;
		this.overrideCleared = false;
		return cleared;
	}

	/**
	 * Whether a write of this value would change nothing: the same stop SoC as the
	 * pending or running override, or a cancel without one. A repeated API write is
	 * skipped, so its write time does not move. Edge cycle thread only.
	 *
	 * @param soc the written stop SoC, or null (or negative) to cancel
	 * @return true if the write changes nothing
	 */
	boolean isUnchangedOverride(Integer soc) {
		if (soc == null || soc < 0) {
			return this.override == null;
		}
		return this.override != null && this.override.soc() == soc;
	}

	/**
	 * The pending or running one-night override. Edge cycle thread only.
	 *
	 * @return the override, or null if none (or cleared)
	 */
	SocOverride getOverride() {
		return this.override;
	}

	/**
	 * Whether this rule has ended the night charge of the current window: inside
	 * the charge window, the stop latched in this window ({@link State#STOPPED}),
	 * or a device that reads disarmed has been held disarmed in this window
	 * ({@link State#HELD_DISARMED}, a stop seen before a restart) for
	 * {@link #HELD_CONFIRM_CYCLES} evaluations with the SoC not clearly below the
	 * stop. A latch left over from an earlier window does not count. The
	 * charge-priority schedule then ends its night value too, so the unit does not
	 * charge from mains outside the disarmed timer. Edge cycle thread only.
	 *
	 * @param now the caller's plausible local date-time (the charge-priority
	 *            schedule's), or null if unknown
	 * @return true while the night charge is ended
	 */
	boolean hasEndedCharge(LocalDateTime now) {
		if (!this.isActive() || now == null || !this.isInsideWindow(now.toLocalTime())) {
			return false;
		}
		var date = now.toLocalDate();
		return date.equals(this.latchedOn) || this.isHeldConfirmed(date);
	}

	/**
	 * Whether the charge-priority schedule must not raise its night value yet: the
	 * rule is active but has not evaluated a known time since activation, or it
	 * holds a disarmed device inside the window that does not yet count as an ended
	 * charge ({@link #hasEndedCharge}). Edge cycle thread only.
	 *
	 * @param now the caller's plausible local date-time (the charge-priority
	 *            schedule's), or null if unknown
	 * @return true while the night value is held back
	 */
	boolean isNightChargePending(LocalDateTime now) {
		if (!this.isActive()) {
			return false;
		}
		if (!this.decided) {
			return true;
		}
		if (now == null || !this.isInsideWindow(now.toLocalTime())) {
			return false;
		}
		var date = now.toLocalDate();
		return date.equals(this.heldOn) && !date.equals(this.latchedOn) && !this.isHeldConfirmed(date);
	}

	private boolean isHeldConfirmed(LocalDate date) {
		return date.equals(this.heldOn) && this.heldCycles >= HELD_CONFIRM_CYCLES && this.bandReads() >= CONFIRM_READS;
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
