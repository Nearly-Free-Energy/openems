package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.types.OptionsEnum;

/**
 * Why the daily output-priority schedule ({@link ModeSchedule}) wants its
 * current target.
 */
public enum ModeScheduleReason implements OptionsEnum {
	UNDEFINED(-1), //
	/** The time window alone (evening, or daytime without the SoC rule). */
	WINDOW(0), //
	/** Daytime, SoC rule active, battery high: SBU so PV is not curtailed. */
	HIGH_SOC(1), //
	/** Daytime, SoC rule active, battery not high: the daytime priority. */
	LOW_SOC(2), //
	/**
	 * Daytime, between eveningReserveFrom and eveningStart, SoC below
	 * eveningReserveSoc: the daytime priority so the battery charges for the peak.
	 */
	EVENING_RESERVE(3), //
	/**
	 * Daytime, eveningReserveHold on, the reserve was reached in the reserve
	 * window: the daytime priority is held until eveningStart.
	 */
	EVENING_RESERVE_HOLD(4);

	private final int value;

	private ModeScheduleReason(int value) {
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
