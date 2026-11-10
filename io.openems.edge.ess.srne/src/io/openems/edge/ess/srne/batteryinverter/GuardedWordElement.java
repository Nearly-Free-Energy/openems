package io.openems.edge.ess.srne.batteryinverter;

import com.ghgande.j2mod.modbus.procimg.Register;

import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;

/**
 * A single-register write element whose queued value can be withdrawn
 * atomically.
 *
 * <p>The Modbus bridge takes a write value with
 * {@code getNextWriteValueAndReset()}, which converts it through
 * {@link #valueToRaw(Integer)}. That conversion and {@link #withdraw()} are
 * decided under the same lock, so exactly one of them wins: either the frame
 * is built and sent, or the value is withdrawn and the bridge gets nothing
 * (a null from {@code valueToRaw} makes {@code getNextWriteValueAndReset()}
 * return null, which the FC16 merge skips, so the task is a NO_OP). The frame is
 * always built from the value stored under the lock by {@link #arm(int)}, never
 * from the value the bridge read outside it.
 */
final class GuardedWordElement extends UnsignedWordElement {

	private boolean armed;
	private Integer armedValue;

	GuardedWordElement(int address) {
		super(address);
	}

	/**
	 * Queues a value for the next write.
	 *
	 * @param value the register value
	 */
	synchronized void arm(int value) {
		this.armed = true;
		this.armedValue = value;
		this.setNextWriteValue(value);
	}

	/**
	 * Withdraws the queued value if the bridge has not taken it yet.
	 *
	 * @return true if withdrawn, false if it was already taken (or none queued)
	 */
	synchronized boolean withdraw() {
		if (!this.armed) {
			return false;
		}
		this.armed = false;
		this.armedValue = null;
		this.setNextWriteValue(null);
		return true;
	}

	@Override
	protected synchronized Register[] valueToRaw(Integer ignored) {
		if (!this.armed || this.armedValue == null) {
			// Withdrawn after the bridge read the value: build no frame.
			return null;
		}
		var value = this.armedValue;
		this.armed = false;
		this.armedValue = null;
		return super.valueToRaw(value);
	}
}
