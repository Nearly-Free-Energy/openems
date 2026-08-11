package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.ess.srne.common.Srne;
import io.openems.edge.ess.srne.common.enums.MachineState;

public interface SrneBatteryInverter extends Srne, OffGridBatteryInverter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT)), //
		BATTERY_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE)), //
		MACHINE_STATE(Doc.of(MachineState.values()) //
				.text("Machine state from holding register 0x0210")), //
		STATE_MACHINE(Doc.of(State.values()) //
				.text("Grid/off-grid lifecycle derived from the machine state")), //
		DISCHARGE_CUTOFF_SOC(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT)), //
		STOP_CHARGE_CURRENT(Doc.of(OpenemsType.INTEGER).unit(Unit.MILLIAMPERE)), //
		STOP_CHARGE_SOC(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT)), //
		LOW_SOC_ALARM(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT)), //
		SWITCH_TO_LINE_SOC(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT)), //
		SWITCH_TO_BATTERY_SOC(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT)), //
		AC_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.MILLIAMPERE)), //
		MAX_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.MILLIAMPERE)), //
		// TOU schedule windows: raw value is encoded hour*256+minute (e.g. 18:00
		// = 4608). Used for time-of-use arbitrage (charge off-peak, discharge peak).
		CHARGE_WINDOW_1_START(Doc.of(OpenemsType.INTEGER) //
				.text("Charge window 1 start, encoded hour*256+min (0xE026)")), //
		CHARGE_WINDOW_1_STOP(Doc.of(OpenemsType.INTEGER) //
				.text("Charge window 1 stop, encoded hour*256+min (0xE027)")), //
		DISCHARGE_WINDOW_1_START(Doc.of(OpenemsType.INTEGER) //
				.text("Discharge window 1 start, encoded hour*256+min (0xE02D)")), //
		DISCHARGE_WINDOW_1_STOP(Doc.of(OpenemsType.INTEGER) //
				.text("Discharge window 1 stop, encoded hour*256+min (0xE02E)")), //
		// Schedule enable flags: 0 disabled, 1 enabled. Written last, only after the
		// matching window pair is read-back verified (0xE02C charge, 0xE033 discharge).
		CHARGE_SCHEDULE_ENABLE(Doc.of(OpenemsType.INTEGER) //
				.text("Charge time-schedule enable, 0/1 (0xE02C)")), //
		DISCHARGE_SCHEDULE_ENABLE(Doc.of(OpenemsType.INTEGER) //
				.text("Discharge time-schedule enable, 0/1 (0xE033)")), //
		// Inverter real-time clock (read-only). Each register is high*256+low; a wrong
		// clock makes every schedule window fire at the wrong wall-clock time, so
		// commissioning must verify these before enabling any schedule.
		RTC_YEAR_MONTH(Doc.of(OpenemsType.INTEGER) //
				.text("Inverter RTC year*256+month (0xE034)")), //
		RTC_DAY_HOUR(Doc.of(OpenemsType.INTEGER) //
				.text("Inverter RTC day*256+hour (0xE035)")), //
		RTC_MINUTE_SECOND(Doc.of(OpenemsType.INTEGER) //
				.text("Inverter RTC minute*256+second (0xE036)")), //
		SAFE_WRITE_STATE(Doc.of(SafeWriteHandler.State.values()) //
				.text("Aggregate state of the guarded settings write operation")); //

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

	/**
	 * Gets the battery-voltage channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getBatteryVoltageChannel() {
		return this.channel(ChannelId.BATTERY_VOLTAGE);
	}

	/**
	 * Gets the battery-current channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getBatteryCurrentChannel() {
		return this.channel(ChannelId.BATTERY_CURRENT);
	}
}
