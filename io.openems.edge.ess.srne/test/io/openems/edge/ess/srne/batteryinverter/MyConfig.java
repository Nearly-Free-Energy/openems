package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.edge.common.startstop.StartStopConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String modbusId;
		private int modbusUnitId;
		private int maxApparentPower;
		private boolean controlEnabled;
		private int dischargeCutoffSoc = -1;
		private int stopChargeCurrent = -1;
		private int stopChargeSoc = -1;
		private int lowSocAlarm = -1;
		private int switchToLineSoc = -1;
		private int switchToBatterySoc = -1;
		private int acChargeCurrentLimit = -1;
		private int maxChargeCurrentLimit = -1;
		private int chargeWindow1Start = -1;
		private int chargeWindow1Stop = -1;
		private int dischargeWindow1Start = -1;
		private int dischargeWindow1Stop = -1;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setModbusId(String modbusId) {
			this.modbusId = modbusId;
			return this;
		}

		public Builder setModbusUnitId(int modbusUnitId) {
			this.modbusUnitId = modbusUnitId;
			return this;
		}

		public Builder setMaxApparentPower(int maxApparentPower) {
			this.maxApparentPower = maxApparentPower;
			return this;
		}

		public Builder setControlEnabled(boolean value) {
			this.controlEnabled = value;
			return this;
		}

		public Builder setDischargeCutoffSoc(int value) {
			this.dischargeCutoffSoc = value;
			return this;
		}

		public Builder setLowSocAlarm(int value) {
			this.lowSocAlarm = value;
			return this;
		}

		// JUSTIFICATION-A3: test-builder setters for the new schedule-window config
		// fields (#67); required so tests can configure them, mirroring the existing
		// setters.
		public Builder setChargeWindow1Start(int value) {
			this.chargeWindow1Start = value;
			return this;
		}

		public Builder setChargeWindow1Stop(int value) {
			this.chargeWindow1Stop = value;
			return this;
		}

		public Builder setDischargeWindow1Start(int value) {
			this.dischargeWindow1Start = value;
			return this;
		}

		public Builder setDischargeWindow1Stop(int value) {
			this.dischargeWindow1Stop = value;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Creates a configuration builder.
	 *
	 * @return the builder
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String modbus_id() {
		return this.builder.modbusId;
	}

	@Override
	public StartStopConfig startStop() {
		return StartStopConfig.AUTO;
	}

	@Override
	public int modbusUnitId() {
		return this.builder.modbusUnitId;
	}

	@Override
	public int maxApparentPower() {
		return this.builder.maxApparentPower;
	}

	@Override
	public boolean controlEnabled() {
		return this.builder.controlEnabled;
	}

	@Override
	public int dischargeCutoffSoc() {
		return this.builder.dischargeCutoffSoc;
	}

	@Override
	public int stopChargeCurrent() {
		return this.builder.stopChargeCurrent;
	}

	@Override
	public int stopChargeSoc() {
		return this.builder.stopChargeSoc;
	}

	@Override
	public int lowSocAlarm() {
		return this.builder.lowSocAlarm;
	}

	@Override
	public int switchToLineSoc() {
		return this.builder.switchToLineSoc;
	}

	@Override
	public int switchToBatterySoc() {
		return this.builder.switchToBatterySoc;
	}

	@Override
	public int acChargeCurrentLimit() {
		return this.builder.acChargeCurrentLimit;
	}

	@Override
	public int maxChargeCurrentLimit() {
		return this.builder.maxChargeCurrentLimit;
	}

	// JUSTIFICATION-A3: mandatory Config-interface implementations for the new
	// schedule-window fields (#67); MyConfig implements Config so it cannot compile
	// without them.
	@Override
	public int chargeWindow1Start() {
		return this.builder.chargeWindow1Start;
	}

	@Override
	public int chargeWindow1Stop() {
		return this.builder.chargeWindow1Stop;
	}

	@Override
	public int dischargeWindow1Start() {
		return this.builder.dischargeWindow1Start;
	}

	@Override
	public int dischargeWindow1Stop() {
		return this.builder.dischargeWindow1Stop;
	}
}
