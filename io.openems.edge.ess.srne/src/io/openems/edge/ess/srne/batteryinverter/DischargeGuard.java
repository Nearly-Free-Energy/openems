package io.openems.edge.ess.srne.batteryinverter;

import java.time.LocalTime;

/**
 * Decides, once per cycle, whether tonight's discharge window must be held
 * disabled because of the start threshold or the SoC floor.
 *
 * <p>Decisions are latched for the rest of the window and cleared once the
 * window has ended. The only memory is "suppressed for this window" and "start
 * check passed"; after a restart both are re-derived from the current SoC, so
 * the outcome is idempotent. Windows never cross midnight.
 */
final class DischargeGuard {

	private final int startMinSoc;
	private final int floorSoc;

	private DischargeSuppressionReason reason = DischargeSuppressionReason.NONE;
	private boolean startPassed;

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
	 * Evaluates the rules for the current time and SoC.
	 *
	 * @param now         the local time in the configured time zone
	 * @param startMinute the encoded window start (hour*256+min)
	 * @param stopMinute  the encoded window stop (hour*256+min)
	 * @param soc         the current SoC in percent, or null if unknown
	 * @return the current suppression reason
	 */
	DischargeSuppressionReason evaluate(LocalTime now, int startMinute, int stopMinute, Integer soc) {
		var nowMinutes = now.getHour() * 60 + now.getMinute();
		var start = (startMinute >> 8) * 60 + (startMinute & 0xFF);
		var stop = (stopMinute >> 8) * 60 + (stopMinute & 0xFF);
		if (nowMinutes < start || nowMinutes >= stop) {
			// Outside the window: clear so the next night runs without manual action.
			this.reason = DischargeSuppressionReason.NONE;
			this.startPassed = false;
			return this.reason;
		}
		if (soc == null || this.reason != DischargeSuppressionReason.NONE) {
			return this.reason;
		}
		if (this.floorSoc >= 0 && soc <= this.floorSoc) {
			this.reason = DischargeSuppressionReason.FLOOR_REACHED;
		} else if (this.startMinSoc >= 0 && !this.startPassed) {
			if (soc < this.startMinSoc) {
				this.reason = DischargeSuppressionReason.LOW_START;
			} else {
				this.startPassed = true;
			}
		}
		return this.reason;
	}
}
