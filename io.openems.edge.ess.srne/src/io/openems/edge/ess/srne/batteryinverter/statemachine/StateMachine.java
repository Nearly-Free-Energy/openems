package io.openems.edge.ess.srne.batteryinverter.statemachine;

import io.openems.common.types.OptionsEnum;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.ess.srne.common.enums.MachineState;

/**
 * Maps the SRNE machine state to the OpenEMS grid/off-grid lifecycle.
 *
 * <p>
 * Transition and unknown states intentionally do not claim a stable grid mode.
 * Only state {@code 2} has so far been validated against the live Nansana unit.
 */
public final class StateMachine {

	public enum State implements OptionsEnum {
		UNDEFINED(-1), //
		STARTING(10), //
		ON_GRID(20), //
		OFF_GRID(30), //
		TRANSITIONING(40), //
		STOPPED(50), //
		FAULT(60); //

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

	private StateMachine() {
	}

	/**
	 * Gets the lifecycle state for a raw machine state.
	 *
	 * @param machineState the SRNE machine state
	 * @return the lifecycle state
	 */
	public static State fromMachineState(MachineState machineState) {
		if (machineState == null) {
			return State.UNDEFINED;
		}
		return switch (machineState) {
		case POWER_UP_DELAY, WAITING, SOFT_START, BATTERY_ACTIVATE -> State.STARTING;
		case RUNNING_MAINS_BYPASS, MAINS_POWERED -> State.ON_GRID;
		case INVERTER_POWERED -> State.OFF_GRID;
		case INVERTER_TO_MAINS, MAINS_TO_INVERTER -> State.TRANSITIONING;
		case SHUTDOWN_BY_USER -> State.STOPPED;
		case FAULT -> State.FAULT;
		case UNDEFINED -> State.UNDEFINED;
		};
	}

	/**
	 * Gets the stable OpenEMS grid mode for a lifecycle state.
	 *
	 * @param state the lifecycle state
	 * @return the grid mode
	 */
	public static GridMode toGridMode(State state) {
		if (state == null) {
			return GridMode.UNDEFINED;
		}
		return switch (state) {
		case ON_GRID -> GridMode.ON_GRID;
		case OFF_GRID -> GridMode.OFF_GRID;
		case UNDEFINED, STARTING, TRANSITIONING, STOPPED, FAULT -> GridMode.UNDEFINED;
		};
	}
}
