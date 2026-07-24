package io.openems.edge.ess.srne.batteryinverter;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

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
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
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

	private final AtomicReference<TargetGridMode> targetGridMode = new AtomicReference<>(TargetGridMode.GO_ON_GRID);
	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);

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
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());
		this._setMaxApparentPower(config.maxApparentPower());
		this.getMachineStateChannel().onSetNextValue(ignore -> this.updateLifecycle());
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
				new FC3ReadRegistersTask(0x0210, Priority.HIGH, //
						m(SrneBatteryInverter.ChannelId.MACHINE_STATE, new UnsignedWordElement(0x0210)), //
						new DummyRegisterElement(0x0211, 0x021A), //
						m(SymmetricBatteryInverter.ChannelId.ACTIVE_POWER, new UnsignedWordElement(0x021B))));
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
		/*
		 * Story 18 is intentionally read-only. Story 19 will consume targetGridMode and
		 * apply the power set-points through the validated FC16 control path.
		 */
		this.updateLifecycle();
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
				+ "|Grid:" + this.getGridModeChannel().value().asString();
	}
}
