package io.openems.edge.ess.srne.charger;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.ess.srne.common.Srne;

public interface SrneCharger extends Srne, EssDcCharger, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		PV1_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("PV1 (MPPT 1) input voltage; register 0x0107, 0.1 V")), //
		PV1_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("PV1 (MPPT 1) input current; register 0x0108, 0.1 A")), //
		PV1_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("PV1 (MPPT 1) input power; register 0x0109, 1 W")), //
		PV2_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("PV2 (MPPT 2) input voltage; register 0x010F, 0.1 V")), //
		PV2_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("PV2 (MPPT 2) input current; register 0x0110, 0.1 A")), //
		PV2_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("PV2 (MPPT 2) input power; register 0x0111, 1 W")), //
		/**
		 * The inverter's own PV energy counter for today. It resets at the
		 * inverter's midnight, so it is not a cumulated channel.
		 */
		PV_ENERGY_TODAY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("PV energy generated today (inverter counter); register 0xF02F, 0.1 kWh")); //

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
	 * Gets the PV1 power channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getPv1PowerChannel() {
		return this.channel(ChannelId.PV1_POWER);
	}

	/**
	 * Gets the PV2 power channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getPv2PowerChannel() {
		return this.channel(ChannelId.PV2_POWER);
	}

	/**
	 * Gets the PV1 current channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getPv1CurrentChannel() {
		return this.channel(ChannelId.PV1_CURRENT);
	}

	/**
	 * Gets the PV2 current channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getPv2CurrentChannel() {
		return this.channel(ChannelId.PV2_CURRENT);
	}

	/**
	 * Gets the PV1 voltage channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getPv1VoltageChannel() {
		return this.channel(ChannelId.PV1_VOLTAGE);
	}
}
