package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.types.OptionsEnum;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Coordinates one idempotent settings write and its read-back verification.
 * A failed or mismatched write is never retried automatically.
 *
 * <p>Thread-safety: {@code queueIfChanged} is called from the Edge cycle thread
 * (via {@code run()}), while {@code onExecute} and {@code verify} are called from
 * the Modbus bridge worker thread (task execute callback and read-back channel
 * callback). The mutable {@code state}/{@code target} are therefore guarded by
 * intrinsic locking so transitions are atomic and visible across those threads.
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
	private int awaitingReadbackCycles;

	public synchronized boolean queueIfChanged(Integer actual, int target) {
		if (this.state != State.IDLE || actual == null || actual == target) {
			return false;
		}
		this.target = target;
		this.state = State.QUEUED;
		return true;
	}

	public synchronized void onExecute(ExecuteState executeState) {
		if (this.state != State.QUEUED || executeState == ExecuteState.NO_OP) {
			return;
		}
		this.awaitingReadbackCycles = 0;
		this.state = executeState == ExecuteState.OK ? State.AWAITING_READBACK : State.FAILED;
	}

	/**
	 * Advances the bounded readback wait without ever retrying the write.
	 *
	 * @param timeoutCycles number of Edge cycles allowed for a fresh readback
	 */
	public synchronized void onCycle(int timeoutCycles) {
		if (this.state != State.AWAITING_READBACK) {
			return;
		}
		if (++this.awaitingReadbackCycles >= timeoutCycles) {
			this.state = State.FAILED;
		}
	}

	/**
	 * Rejects an invalid setting before any Modbus write is queued.
	 *
	 * @return true only for the first rejection, for one-shot audit logging
	 */
	public synchronized boolean reject() {
		if (this.state != State.IDLE) {
			return false;
		}
		this.state = State.FAILED;
		return true;
	}

	public synchronized void verify(Integer actual) {
		if (this.state != State.AWAITING_READBACK || actual == null) {
			return;
		}
		this.state = actual.equals(this.target) ? State.VERIFIED : State.FAILED;
	}

	public synchronized State getState() {
		return this.state;
	}
}
