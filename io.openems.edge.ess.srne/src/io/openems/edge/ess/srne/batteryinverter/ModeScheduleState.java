package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.types.OptionsEnum;

/**
 * What the daily output-priority schedule ({@link ModeSchedule}) is doing.
 */
public enum ModeScheduleState implements OptionsEnum {
	UNDEFINED(-1), //
	/** Schedule off, or settings writes disabled (controlEnabled=false). */
	DISABLED(0), //
	/** E204 reads the target priority for this time of day. */
	IN_SYNC(1), //
	/** A write is queued or waiting for its read-back. */
	WRITE_PENDING(2), //
	/** E204 differs from the target, but the inverter is not on mains (state 2). */
	WAITING_FOR_MAINS(3), //
	/** The last write failed or read back wrong; retried after a cooldown. */
	WRITE_FAILED(4), //
	/** Invalid schedule configuration; the schedule stays off. */
	CONFIG_INVALID(5), //
	/** SoC, E204 or the clock is unreadable; nothing is written. */
	WAITING_FOR_DATA(6);

	private final int value;

	private ModeScheduleState(int value) {
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
