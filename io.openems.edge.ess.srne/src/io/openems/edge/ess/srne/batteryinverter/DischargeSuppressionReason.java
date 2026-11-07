package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.types.OptionsEnum;

/**
 * Why the discharge schedule is currently held disabled by the driver.
 */
public enum DischargeSuppressionReason implements OptionsEnum {
	UNDEFINED(-1), //
	NONE(0), //
	LOW_START(1), //
	FLOOR_REACHED(2);

	private final int value;

	private DischargeSuppressionReason(int value) {
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
