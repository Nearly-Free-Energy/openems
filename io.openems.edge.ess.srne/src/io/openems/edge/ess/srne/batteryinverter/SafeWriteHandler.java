package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.types.OptionsEnum;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Coordinates one idempotent settings write and its read-back verification.
 * A failed or mismatched write is never retried automatically.
 */
final class SafeWriteHandler {

	public enum State implements OptionsEnum {
		UNDEFINED(-1), IDLE(0), QUEUED(1), AWAITING_READBACK(2), VERIFIED(3), FAILED(4);

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

	private State state = State.IDLE;
	private Integer target;

	public boolean queueIfChanged(Integer actual, int target) {
		if (this.state != State.IDLE || actual == null || actual == target) {
			return false;
		}
		this.target = target;
		this.state = State.QUEUED;
		return true;
	}

	public void onExecute(ExecuteState executeState) {
		if (this.state != State.QUEUED || executeState == ExecuteState.NO_OP) {
			return;
		}
		this.state = executeState == ExecuteState.OK ? State.AWAITING_READBACK : State.FAILED;
	}

	public void verify(Integer actual) {
		if (this.state != State.AWAITING_READBACK || actual == null) {
			return;
		}
		this.state = actual.equals(this.target) ? State.VERIFIED : State.FAILED;
	}

	public State getState() {
		return this.state;
	}
}
