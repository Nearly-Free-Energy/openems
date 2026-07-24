package io.openems.edge.ess.srne.batteryinverter;

import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.ess.srne.common.Srne;
import io.openems.edge.ess.srne.common.enums.MachineState;

public interface SrneBatteryInverter extends Srne, OffGridBatteryInverter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		MACHINE_STATE(Doc.of(MachineState.values()) //
				.text("Machine state from holding register 0x0210")), //
		STATE_MACHINE(Doc.of(State.values()) //
				.text("Grid/off-grid lifecycle derived from the machine state")); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the machine-state channel.
	 *
	 * @return the channel
	 */
	public default Channel<MachineState> getMachineStateChannel() {
		return this.channel(ChannelId.MACHINE_STATE);
	}

	/**
	 * Gets the current machine state.
	 *
	 * @return the current state
	 */
	public default MachineState getMachineState() {
		return this.getMachineStateChannel().value().asEnum();
	}
}
