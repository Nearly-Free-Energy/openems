package io.openems.edge.ess.srne.backupmeter;

import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.srne.common.Srne;
import io.openems.edge.meter.api.ElectricityMeter;

public interface SrneBackupMeter extends Srne, ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		APPARENT_POWER_L1(new IntegerDoc() //
				.unit(Unit.VOLT_AMPERE));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
