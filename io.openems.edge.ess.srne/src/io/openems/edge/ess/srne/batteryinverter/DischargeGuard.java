package io.openems.edge.ess.srne.batteryinverter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Decides, once per cycle, whether tonight's discharge window must be held
 * disabled because of the start threshold or the SoC floor.
 *
 * <p>The bias is fail-open: a wrong "allow" is recoverable, a silent and
 * permanent "suppress" is not. Therefore
 * <ul>
 * <li>the start rule only applies during the first
 * {@link #START_RULE_MINUTES} minutes of the window; later (e.g. after a restart
 * or a configuration change) only the floor rule applies,</li>
 * <li>a SoC outside 0..100 or unknown never changes a decision,</li>
 * <li>a suppression is latched only after the same low reading was seen on
 * {@link #CONFIRMATIONS} consecutive evaluations (a failed BMS read can show as
 * 0),</li>
 * <li>a discontinuous local-time step of more than {@link #CLOCK_STEP_MINUTES}
 * minutes between two evaluations holds the last decision for that
 * evaluation,</li>
 * <li>arming is held back while no usable SoC has been read in the window, but
 * for at most {@link #HOLD_CAP_MINUTES} minutes (fail open: a night must not be
 * skipped silently because the SoC is unreadable),</li>
 * <li>the hold is bounded by the time left in the window, and a window that ends
 * without a usable SoC is reported,</li>
 * <li>a SoC first readable after the hold cap fired still gets the start rule
 * once,</li>
 * <li>after a restart in the middle of the window the start rule is applied once on
 * the first usable SoC, but only while the device reads enable=0 (we would be arming
 * it now); a device that already reads enable=1 is a discharge legitimately running,
 * which only the floor rule may stop,</li>
 * <li>per local date the start rule can suppress at most once and never again
 * after the window was restored; the floor rule is always allowed.</li>
 * </ul>
 *
 * <p>Decisions are latched for the rest of the window and cleared once the
 * window has ended. Windows never cross midnight.
 */
final class DischargeGuard {

	static final int START_RULE_MINUTES = 10;
	static final int CLOCK_STEP_MINUTES = 5;
	static final int CONFIRMATIONS = 2;
	static final int HOLD_CAP_MINUTES = 10;

	private final int startMinSoc;
	private final int floorSoc;

	private DischargeSuppressionReason reason = DischargeSuppressionReason.NONE;
	private DischargeSuppressionReason pending = DischargeSuppressionReason.NONE;
	private int pendingCount;
	private boolean startPassed;
	private boolean insideWindow;
	private LocalDateTime lastNow;
	private LocalDate dwellDate;
	private boolean lowStartUsed;
	private boolean restoredThisDate;
	private boolean usableSocSeen;
	private boolean unusableSocSeen;
	private boolean unusableSocWarned;
	private boolean clockStepPending;
	private long noSocSeconds;
	private boolean holdCapWarnPending;
	private long holdCapSeconds = HOLD_CAP_MINUTES * 60L;
	private boolean windowEndWarnPending;
	private boolean lateStartRule;
	private boolean restartStartRulePending;
	private boolean armedFailOpen;

	DischargeGuard(int startMinSoc, int floorSoc) {
		this.startMinSoc = startMinSoc;
		this.floorSoc = floorSoc;
	}

	boolean isActive() {
		return this.startMinSoc >= 0 || this.floorSoc >= 0;
	}

	DischargeSuppressionReason evaluateCurrent() {
		return this.reason;
	}

	/**
	 * Whether the last evaluation was inside the window.
	 *
	 * @return true if inside the window
	 */
	boolean isInsideWindow() {
		return this.insideWindow;
	}

	/**
	 * Whether the schedule must not be newly armed right now: inside the window,
	 * nothing latched yet, and either a candidate is awaiting its confirmation or no
	 * usable SoC has been read yet in this window. The wait for a first usable SoC
	 * is capped at {@link #HOLD_CAP_MINUTES} minutes of window time, after which
	 * the hold is released.
	 *
	 * @return true if arming must be held back
	 */
	boolean isHoldingArm() {
		// restartStartRulePending needs no term here: after a late restart with an unknown
		// enable nothing is queued (the window waits for a known enable), and with a known
		// disarmed device the no-usable-SoC hold above already applies until the first SoC.
		return this.isActive() && this.insideWindow && this.reason == DischargeSuppressionReason.NONE
				&& (this.pending != DischargeSuppressionReason.NONE || !this.usableSocSeen && !this.isHoldCapReached());
	}

	private boolean isHoldCapReached() {
		return this.noSocSeconds >= this.holdCapSeconds;
	}

	/**
	 * One-shot: true once per window when the hold for a first usable SoC ran out.
	 *
	 * @return true if a warning is due
	 */
	boolean pollHoldCapWarning() {
		var due = this.holdCapWarnPending;
		this.holdCapWarnPending = false;
		return due;
	}

	/**
	 * One-shot: true when a window ended without a usable SoC ever being read and
	 * the hold cap had not fired (a window shorter than the cap, or a restart late in
	 * the window), so the night ended disarmed without any other warning.
	 *
	 * @return true if a warning is due
	 */
	boolean pollWindowEndWithoutSocWarning() {
		var due = this.windowEndWarnPending;
		this.windowEndWarnPending = false;
		return due;
	}

	/**
	 * One-shot: true once per window after a null or out-of-range SoC was seen.
	 *
	 * @return true if a warning is due
	 */
	boolean pollUnusableSocWarning() {
		if (this.unusableSocSeen && !this.unusableSocWarned) {
			this.unusableSocWarned = true;
			return true;
		}
		return false;
	}

	/**
	 * One-shot: true after a discontinuous clock step was detected.
	 *
	 * @return true if a warning is due
	 */
	boolean pollClockStepWarning() {
		var due = this.clockStepPending;
		this.clockStepPending = false;
		return due;
	}

	/**
	 * Evaluates the rules for the current time and SoC.
	 *
	 * @param now         the local date-time in the configured time zone
	 * @param startMinute the encoded window start (hour*256+min)
	 * @param stopMinute  the encoded window stop (hour*256+min)
	 * @param soc         the current SoC in percent, or null if unknown
	 * @return the current suppression reason
	 */
	DischargeSuppressionReason evaluate(LocalDateTime now, int startMinute, int stopMinute, Integer soc) {
		return this.evaluate(now, startMinute, stopMinute, soc, null);
	}

	/**
	 * Evaluates the rules for the current time, SoC and device enable state.
	 *
	 * @param now          the local date-time in the configured time zone
	 * @param startMinute  the encoded window start (hour*256+min)
	 * @param stopMinute   the encoded window stop (hour*256+min)
	 * @param soc          the current SoC in percent, or null if unknown
	 * @param deviceEnable the enable register as read from the device, or null if
	 *                     unknown; decides whether the start rule applies once after
	 *                     a restart in the middle of the window
	 * @return the current suppression reason
	 */
	DischargeSuppressionReason evaluate(LocalDateTime now, int startMinute, int stopMinute, Integer soc,
			Integer deviceEnable) {
		var previous = this.lastNow;
		this.lastNow = now;
		if (previous != null
				&& Math.abs(Duration.between(previous, now).toSeconds()) > CLOCK_STEP_MINUTES * 60L) {
			this.clockStepPending = true;
			return this.reason;
		}
		if (!now.toLocalDate().equals(this.dwellDate)) {
			this.dwellDate = now.toLocalDate();
			this.lowStartUsed = false;
			this.restoredThisDate = false;
		}
		var nowMinutes = now.getHour() * 60 + now.getMinute();
		var start = (startMinute >> 8) * 60 + (startMinute & 0xFF);
		var stop = (stopMinute >> 8) * 60 + (stopMinute & 0xFF);
		if (nowMinutes < start || nowMinutes >= stop) {
			// Outside the window: clear so the next night runs without manual action.
			if (this.reason != DischargeSuppressionReason.NONE) {
				this.restoredThisDate = true;
			}
			if (this.insideWindow && !this.usableSocSeen && !this.isHoldCapReached()) {
				this.windowEndWarnPending = true;
			}
			this.insideWindow = false;
			this.reason = DischargeSuppressionReason.NONE;
			this.pending = DischargeSuppressionReason.NONE;
			this.pendingCount = 0;
			this.startPassed = false;
			this.usableSocSeen = false;
			this.unusableSocSeen = false;
			this.unusableSocWarned = false;
			this.noSocSeconds = 0;
			this.holdCapWarnPending = false;
			this.holdCapSeconds = HOLD_CAP_MINUTES * 60L;
			this.lateStartRule = false;
			this.restartStartRulePending = false;
			this.armedFailOpen = false;
			return this.reason;
		}
		var wasInside = this.insideWindow;
		this.insideWindow = true;
		if (!wasInside) {
			// The hold is bounded by the time left in the window, counted from the later of
			// the window start and this first evaluation (e.g. after a restart).
			var remaining = (stop - nowMinutes) * 60L - now.getSecond();
			this.holdCapSeconds = Math.min(HOLD_CAP_MINUTES * 60L, remaining);
			this.restartStartRulePending = nowMinutes >= start + START_RULE_MINUTES;
		}
		// Wall-clock time inside the window without a usable SoC. A clock step returned
		// above, so the step itself is never counted.
		if (!this.usableSocSeen && wasInside && previous != null) {
			var before = this.isHoldCapReached();
			this.noSocSeconds += Math.max(0, Duration.between(previous, now).toSeconds());
			this.holdCapWarnPending |= !before && this.isHoldCapReached();
		}
		// A disarmed device with the hold released is armed by this component now, so a
		// later enable=1 reading is not evidence of a discharge that was already running.
		this.armedFailOpen |= !this.usableSocSeen && this.isHoldCapReached() && Integer.valueOf(0).equals(deviceEnable);
		if (soc == null || soc < 0 || soc > 100) {
			this.unusableSocSeen = true;
			this.pending = DischargeSuppressionReason.NONE;
			this.pendingCount = 0;
			return this.reason;
		}
		this.lateStartRule |= !this.usableSocSeen && this.isHoldCapReached();
		this.usableSocSeen = true;
		if (this.reason != DischargeSuppressionReason.NONE) {
			return this.reason;
		}
		if (this.restartStartRulePending && deviceEnable != null) {
			this.restartStartRulePending = false;
			if (deviceEnable == 0 || this.armedFailOpen) {
				this.lateStartRule = true;
			} else {
				this.startPassed = true;
			}
		}
		// A SoC first readable after the hold cap fired still gets the start rule once,
		// so a low start is not armed just because the SoC was late.
		var startRuleOpen = this.startMinSoc >= 0 && !this.startPassed
				&& (nowMinutes < start + START_RULE_MINUTES || this.lateStartRule);
		var candidate = DischargeSuppressionReason.NONE;
		if (this.floorSoc >= 0 && soc <= this.floorSoc) {
			candidate = DischargeSuppressionReason.FLOOR_REACHED;
		} else if (startRuleOpen) {
			if (soc < this.startMinSoc) {
				if (!this.lowStartUsed && !this.restoredThisDate) {
					candidate = DischargeSuppressionReason.LOW_START;
				}
			} else {
				this.startPassed = true;
			}
		}
		if (candidate == DischargeSuppressionReason.NONE) {
			this.pending = candidate;
			this.pendingCount = 0;
			this.lateStartRule = false;
			return this.reason;
		}
		this.pendingCount = candidate == this.pending ? this.pendingCount + 1 : 1;
		this.pending = candidate;
		if (this.pendingCount >= CONFIRMATIONS) {
			this.reason = candidate;
			this.lowStartUsed |= candidate == DischargeSuppressionReason.LOW_START;
			this.lateStartRule = false;
		}
		return this.reason;
	}
}
