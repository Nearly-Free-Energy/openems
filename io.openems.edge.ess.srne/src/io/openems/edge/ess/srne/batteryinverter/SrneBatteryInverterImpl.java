package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.ess.srne.common.Srne;
import io.openems.edge.ess.srne.common.enums.MachineState;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Srne.BatteryInverter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("Modbus")
public class SrneBatteryInverterImpl extends AbstractOpenemsModbusComponent
		implements SrneBatteryInverter, Srne, OffGridBatteryInverter, ManagedSymmetricBatteryInverter,
		SymmetricBatteryInverter, ModbusComponent, OpenemsComponent, StartStoppable {
	private static final int READBACK_TIMEOUT_CYCLES = 30;
	private final Logger log = LoggerFactory.getLogger(SrneBatteryInverterImpl.class);

	private final AtomicReference<TargetGridMode> targetGridMode = new AtomicReference<>(TargetGridMode.GO_ON_GRID);
	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);
	private final UnsignedWordElement dischargeCutoffSocWrite = new UnsignedWordElement(0xE00F);
	private final UnsignedWordElement stopChargeCurrentWrite = new UnsignedWordElement(0xE01C);
	private final UnsignedWordElement stopChargeSocWrite = new UnsignedWordElement(0xE01D);
	private final UnsignedWordElement lowSocAlarmWrite = new UnsignedWordElement(0xE01E);
	private final UnsignedWordElement switchToLineSocWrite = new UnsignedWordElement(0xE01F);
	private final UnsignedWordElement switchToBatterySocWrite = new UnsignedWordElement(0xE020);
	private final UnsignedWordElement acChargeCurrentLimitWrite = new UnsignedWordElement(0xE205);
	private final UnsignedWordElement maxChargeCurrentLimitWrite = new UnsignedWordElement(0xE20A);
	private final SafeWriteHandler[] writeHandlers = { new SafeWriteHandler(), new SafeWriteHandler(),
			new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(),
			new SafeWriteHandler(), new SafeWriteHandler() };
	private Config config;

	@Override
	@Reference(//
			policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public SrneBatteryInverterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricBatteryInverter.ChannelId.values(), //
				ManagedSymmetricBatteryInverter.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				OffGridBatteryInverter.ChannelId.values(), //
				Srne.ChannelId.values(), //
				SrneBatteryInverter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		/*
		 * There is intentionally no @Modified method. A settings configuration update
		 * causes DS to replace this component instance, giving every setting a fresh
		 * SafeWriteHandler. VERIFIED and FAILED therefore remain terminal for one
		 * configured request and cannot trigger repeated writes in the same activation.
		 */
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());
		this._setMaxApparentPower(config.maxApparentPower());
		this.getMachineStateChannel().onSetNextValue(ignore -> this.updateLifecycle());

		/*
		 * Validated on the ASP48120SH3: SRNE battery current is positive while
		 * discharging and negative while charging. This matches the OpenEMS
		 * battery-inverter power sign convention.
		 */
		final Consumer<Value<Integer>> updateActivePower = ignore -> {
			var voltage = this.getBatteryVoltageChannel().getNextValue().get();
			var current = this.getBatteryCurrentChannel().getNextValue().get();
			if (voltage == null || current == null) {
				this._setActivePower(null);
				return;
			}
			this._setActivePower((int) Math.round(voltage * (double) current / 1_000_000D));
		};
		this.getBatteryVoltageChannel().onSetNextValue(updateActivePower);
		this.getBatteryCurrentChannel().onSetNextValue(updateActivePower);

		this.registerReadback(0, SrneBatteryInverter.ChannelId.DISCHARGE_CUTOFF_SOC);
		this.registerReadback(1, SrneBatteryInverter.ChannelId.STOP_CHARGE_CURRENT);
		this.registerReadback(2, SrneBatteryInverter.ChannelId.STOP_CHARGE_SOC);
		this.registerReadback(3, SrneBatteryInverter.ChannelId.LOW_SOC_ALARM);
		this.registerReadback(4, SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC);
		this.registerReadback(5, SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC);
		this.registerReadback(6, SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT);
		this.registerReadback(7, SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT);
	}

	private void registerReadback(int index, SrneBatteryInverter.ChannelId channelId) {
		((IntegerReadChannel) this.channel(channelId)).onSetNextValue(value -> {
			this.writeHandlers[index].verify(value.get());
			this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
		});
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private void updateLifecycle() {
		MachineState machineState = this.getMachineStateChannel().getNextValue().asEnum();
		var state = StateMachine.fromMachineState(machineState);
		this.channel(SrneBatteryInverter.ChannelId.STATE_MACHINE).setNextValue(state);
		this._setGridMode(StateMachine.toGridMode(state));
		switch (state) {
		case ON_GRID, OFF_GRID, TRANSITIONING:
			this._setInverterState(true);
			this._setStartStop(StartStop.START);
			break;
		case STOPPED, FAULT:
			this._setInverterState(false);
			this._setStartStop(StartStop.STOP);
			break;
		case UNDEFINED, STARTING:
			this._setInverterState(null);
			this._setStartStop(StartStop.UNDEFINED);
			break;
		}
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0xE00F, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_CUTOFF_SOC,
								new UnsignedWordElement(0xE00F))), //
				new FC3ReadRegistersTask(0xE01C, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.STOP_CHARGE_CURRENT, new UnsignedWordElement(0xE01C),
								SCALE_FACTOR_2), //
						m(SrneBatteryInverter.ChannelId.STOP_CHARGE_SOC, new UnsignedWordElement(0xE01D)), //
						m(SrneBatteryInverter.ChannelId.LOW_SOC_ALARM, new UnsignedWordElement(0xE01E)), //
						m(SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC, new UnsignedWordElement(0xE01F)), //
						m(SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC,
								new UnsignedWordElement(0xE020))), //
				new FC3ReadRegistersTask(0xE205, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0xE205), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xE20A, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0xE20A), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0x0101, Priority.HIGH, //
						m(SrneBatteryInverter.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(0x0101),
								SCALE_FACTOR_2), //
						m(SrneBatteryInverter.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x0102),
								SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0x0210, Priority.HIGH, //
						m(SrneBatteryInverter.ChannelId.MACHINE_STATE, new UnsignedWordElement(0x0210))), //
				new FC16WriteRegistersTask(this.writeHandlers[0]::onExecute, 0xE00F, this.dischargeCutoffSocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[1]::onExecute, 0xE01C, this.stopChargeCurrentWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[2]::onExecute, 0xE01D, this.stopChargeSocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[3]::onExecute, 0xE01E, this.lowSocAlarmWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[4]::onExecute, 0xE01F, this.switchToLineSocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[5]::onExecute, 0xE020, this.switchToBatterySocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[6]::onExecute, 0xE205,
						this.acChargeCurrentLimitWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[7]::onExecute, 0xE20A,
						this.maxChargeCurrentLimitWrite));
	}

	@Override
	public void setTargetGridMode(TargetGridMode targetGridMode) {
		this.targetGridMode.set(targetGridMode);
	}

	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		this.startStopTarget.set(value);
	}

	@Override
	public void run(Battery battery, int setActivePower, int setReactivePower) throws OpenemsNamedException {
		this.updateLifecycle();
		this.reconcileSafeSettings();
	}

	/**
	 * Continuous max charge/discharge current of the SR-SE10B (205Ah LFP) is 100A;
	 * 120A is only the 3-second peak (datasheet SRNE_SE series V1.5). Current-limit
	 * settings are capped at the continuous rating so a configured limit can never
	 * exceed what the battery allows.
	 */
	private static final int MAX_BATTERY_CURRENT_A = 100;

	private void reconcileSafeSettings() {
		for (var handler : this.writeHandlers) {
			handler.onCycle(READBACK_TIMEOUT_CYCLES);
		}
		this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
		if (this.config == null || !this.config.controlEnabled()) {
			return;
		}
		MachineState machineState = this.getMachineStateChannel().value().asEnum();
		if (machineState == null || !machineState.isVerified()) {
			return;
		}

		this.reconcile(0, SrneBatteryInverter.ChannelId.DISCHARGE_CUTOFF_SOC,
				this.config.dischargeCutoffSoc(), 0, 100, 1, this.dischargeCutoffSocWrite);
		this.reconcile(1, SrneBatteryInverter.ChannelId.STOP_CHARGE_CURRENT,
				this.config.stopChargeCurrent(), 0, MAX_BATTERY_CURRENT_A, 10, this.stopChargeCurrentWrite);
		this.reconcile(2, SrneBatteryInverter.ChannelId.STOP_CHARGE_SOC,
				this.config.stopChargeSoc(), 0, 100, 1, this.stopChargeSocWrite);
		this.reconcile(3, SrneBatteryInverter.ChannelId.LOW_SOC_ALARM,
				this.config.lowSocAlarm(), 0, 100, 1, this.lowSocAlarmWrite);
		this.reconcile(4, SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC,
				this.config.switchToLineSoc(), 0, 100, 1, this.switchToLineSocWrite);
		this.reconcile(5, SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC,
				this.config.switchToBatterySoc(), 0, 100, 1, this.switchToBatterySocWrite);
		this.reconcile(6, SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT,
				this.config.acChargeCurrentLimit(), 0, MAX_BATTERY_CURRENT_A, 10, this.acChargeCurrentLimitWrite);
		this.reconcile(7, SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT,
				this.config.maxChargeCurrentLimit(), 0, MAX_BATTERY_CURRENT_A, 10, this.maxChargeCurrentLimitWrite);
		this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
	}

	private void reconcile(int index, SrneBatteryInverter.ChannelId channelId, int configuredTarget, int min, int max,
			int rawMultiplier, UnsignedWordElement writeElement) {
		if (configuredTarget < 0) {
			return;
		}
		var handler = this.writeHandlers[index];
		Integer actual = ((IntegerReadChannel) this.channel(channelId)).value().get();
		if (configuredTarget < min || configuredTarget > max) {
			if (handler.reject()) {
				this.logWarn(this.log, "Rejected out-of-range setting [" + channelId.name() + "] value ["
						+ configuredTarget + "]; validated range is [" + min + ".." + max + "]");
			}
			return;
		}
		int target = configuredTarget;
		int channelTarget = target * (rawMultiplier == 10 ? 1_000 : 1);
		if (handler.queueIfChanged(actual, channelTarget)) {
			writeElement.setNextWriteValue(target * rawMultiplier);
		}
	}

	private SafeWriteHandler.State aggregateWriteState() {
		var result = SafeWriteHandler.State.IDLE;
		for (var handler : this.writeHandlers) {
			var state = handler.getState();
			if (writeStatePrecedence(state) > writeStatePrecedence(result)) {
				result = state;
			}
		}
		return result;
	}

	static int writeStatePrecedence(SafeWriteHandler.State state) {
		return switch (state) {
		case FAILED -> 5;
		case AWAITING_READBACK -> 4;
		case QUEUED -> 3;
		case VERIFIED -> 2;
		case IDLE -> 1;
		case UNDEFINED -> 0;
		};
	}

	@Override
	public int getPowerPrecision() {
		return 1;
	}

	@Override
	public boolean isManaged() {
		return false;
	}

	@Override
	public boolean isOffGridPossible() {
		return true;
	}

	@Override
	public String debugLog() {
		return "State:" + this.getMachineStateChannel().value().asString() //
				+ "|Grid:" + this.getGridModeChannel().value().asString() //
				+ "|P:" + this.getActivePower().asString();
	}
}
