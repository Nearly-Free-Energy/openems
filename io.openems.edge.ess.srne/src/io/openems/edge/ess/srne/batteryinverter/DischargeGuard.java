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
		return this.isActive() && this.insideWindow && this.reason == DischargeSuppressionReason.NONE
				&& (this.pending != DischargeSuppressionReason.NONE || !this.usableSocSeen && !this.isHoldCapReached());
	}

	private boolean isHoldCapReached() {
		return this.noSocSeconds >= HOLD_CAP_MINUTES * 60L;
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
			return this.reason;
		}
		var wasInside = this.insideWindow;
		this.insideWindow = true;
		// Wall-clock time inside the window without a usable SoC. A clock step returned
		// above, so the step itself is never counted.
		if (!this.usableSocSeen && wasInside && previous != null) {
			var before = this.isHoldCapReached();
			this.noSocSeconds += Math.max(0, Duration.between(previous, now).toSeconds());
			this.holdCapWarnPending |= !before && this.isHoldCapReached();
		}
		if (soc == null || soc < 0 || soc > 100) {
			this.unusableSocSeen = true;
			this.pending = DischargeSuppressionReason.NONE;
			this.pendingCount = 0;
			return this.reason;
		}
		this.usableSocSeen = true;
		if (this.reason != DischargeSuppressionReason.NONE) {
			return this.reason;
		}
		var startRuleOpen = this.startMinSoc >= 0 && !this.startPassed && nowMinutes < start + START_RULE_MINUTES;
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
			return this.reason;
		}
		this.pendingCount = candidate == this.pending ? this.pendingCount + 1 : 1;
		this.pending = candidate;
		if (this.pendingCount >= CONFIRMATIONS) {
			this.reason = candidate;
			this.lowStartUsed |= candidate == DischargeSuppressionReason.LOW_START;
		}
		return this.reason;
	}
}
