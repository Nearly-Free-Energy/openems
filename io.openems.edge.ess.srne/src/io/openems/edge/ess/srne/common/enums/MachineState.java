package io.openems.edge.ess.srne.common.enums;

import io.openems.common.types.OptionsEnum;

public enum MachineState implements OptionsEnum {
	UNDEFINED(-1, "Undefined", false), //
	POWER_UP_DELAY(0, "Power-up delay", false), //
	WAITING(1, "Waiting", false), //
	RUNNING_MAINS_BYPASS(2, "Running (mains/bypass)", true), //
	SOFT_START(3, "Soft start", false), //
	MAINS_POWERED(4, "Mains powered", false), //
	INVERTER_POWERED(5, "Inverter powered", false), //
	INVERTER_TO_MAINS(6, "Inverter to mains", false), //
	MAINS_TO_INVERTER(7, "Mains to inverter", false), //
	BATTERY_ACTIVATE(8, "Battery activate", false), //
	SHUTDOWN_BY_USER(9, "Shutdown by user", false), //
	FAULT(10, "Fault", false); //

	private final int value;
	private final String name;
	private final boolean verified;

	private MachineState(int value, String name, boolean verified) {
		this.value = value;
		this.name = name;
		this.verified = verified;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}

	/**
	 * Returns whether this mapping has been validated against the live inverter.
	 *
	 * @return true for a live-validated mapping
	 */
	public boolean isVerified() {
		return this.verified;
	}
}
