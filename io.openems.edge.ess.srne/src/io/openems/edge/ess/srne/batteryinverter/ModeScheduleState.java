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
	/**
	 * The last write failed or read back wrong; retried with backoff, latched after
	 * the per-window attempt cap until the next boundary.
	 */
	WRITE_FAILED(4), //
	/** Invalid schedule configuration; the schedule stays off. */
	CONFIG_INVALID(5), //
	/** SoC or E204 is unreadable, or the clock is not yet plausible; nothing is written. */
	WAITING_FOR_DATA(6), //
	/** SBU is due, but its reserve-band / BMS preconditions are not met. */
	BLOCKED_SBU_PRECONDITIONS(7), //
	/** First minutes after activation: no schedule write yet (caps a restart loop). */
	STARTUP_HOLD(8);

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
