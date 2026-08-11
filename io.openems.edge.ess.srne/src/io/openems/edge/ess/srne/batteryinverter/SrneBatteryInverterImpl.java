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
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
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
import io.openems.edge.common.event.EdgeEventConstants;
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
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
@GenerateTargetsFromReferences("Modbus")
public class SrneBatteryInverterImpl extends AbstractOpenemsModbusComponent
		implements SrneBatteryInverter, Srne, OffGridBatteryInverter, ManagedSymmetricBatteryInverter,
		SymmetricBatteryInverter, ModbusComponent, OpenemsComponent, StartStoppable, EventHandler {
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
	// JUSTIFICATION-A3: output priority (E204, source transfer) + BMS-comms enable
	// (E215) added to the guarded write allow-list (#71) so SBU mode + BMS-SoC switching
	// are settable from OpenEMS. Same scalar guarded path as the SoC/current settings;
	// the exact value->mode enum is unverified in the manuals and is confirmed on the
	// unit at commissioning, so the ranges here are bounded only.
	private final UnsignedWordElement outputPriorityWrite = new UnsignedWordElement(0xE204);
	private final UnsignedWordElement bmsCommunicationWrite = new UnsignedWordElement(0xE215);
	private final SafeWriteHandler[] writeHandlers = { new SafeWriteHandler(), new SafeWriteHandler(),
			new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(),
			new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler() };
	/*
	 * TOU schedule windows for arbitrage. Each window is a coherent (start, stop,
	 * enable) unit: the contiguous start/stop pair (charge 0xE026/0xE027, discharge
	 * 0xE02D/0xE02E, encoded hour*256+min) is written as one atomic FC16 block and
	 * read-back verified; only then is the enable flag (charge 0xE02C, discharge
	 * 0xE033) written. A failure never arms the schedule. See ScheduleWindow.
	 */
	private final ScheduleWindow chargeWindow = new ScheduleWindow(0xE026, 0xE02C, "CHARGE");
	private final ScheduleWindow dischargeWindow = new ScheduleWindow(0xE02D, 0xE033, "DISCHARGE");
	private Config config;
	// One-shot audit flag: log at most once that the output-priority write is held
	// pending its prerequisites (reconcile runs every cycle).
	private boolean outputPriorityHeldLogged;

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
		 * SafeWriteHandler / ScheduleWindow. VERIFIED, DONE and FAILED therefore remain
		 * terminal for one configured request and cannot trigger repeated writes in the
		 * same activation.
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
		this.registerReadback(8, SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY);
		this.registerReadback(9, SrneBatteryInverter.ChannelId.BMS_COMMUNICATION);
		this.registerWindowReadback(this.chargeWindow, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.CHARGE_SCHEDULE_ENABLE);
		this.registerWindowReadback(this.dischargeWindow, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE);
	}

	private void registerReadback(int index, SrneBatteryInverter.ChannelId channelId) {
		((IntegerReadChannel) this.channel(channelId)).onSetNextValue(value -> {
			var handler = this.writeHandlers[index];
			var previousState = handler.getState();
			handler.verify(value.get());
			if (previousState != handler.getState()) {
				this.logInfo(this.log, "Settings write [" + channelId.name() + "] state [" + handler.getState()
						+ "] after readback [" + value.get() + "]");
			}
			this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
		});
	}

	// JUSTIFICATION-A3: coherent-window read-back wiring for the TOU schedule
	// feature (#67, PR #24). The window start/stop/enable each verify a distinct
	// register of one ScheduleWindow, so a single indexed registerReadback cannot
	// express it; this mirrors that helper for the three-register unit.
	private void registerWindowReadback(ScheduleWindow window, SrneBatteryInverter.ChannelId startChannel,
			SrneBatteryInverter.ChannelId stopChannel, SrneBatteryInverter.ChannelId enableChannel) {
		((IntegerReadChannel) this.channel(startChannel)).onSetNextValue(value -> {
			var previousState = window.getState();
			window.verifyStart(value.get());
			this.logWindowTransition(window, startChannel, previousState, value.get());
		});
		((IntegerReadChannel) this.channel(stopChannel)).onSetNextValue(value -> {
			var previousState = window.getState();
			window.verifyStop(value.get());
			this.logWindowTransition(window, stopChannel, previousState, value.get());
		});
		((IntegerReadChannel) this.channel(enableChannel)).onSetNextValue(value -> {
			var previousState = window.getState();
			window.verifyEnable(value.get());
			this.logWindowTransition(window, enableChannel, previousState, value.get());
		});
	}

	private void logWindowTransition(ScheduleWindow window, SrneBatteryInverter.ChannelId channelId,
			ScheduleWindow.State previousState, Integer readback) {
		if (previousState != window.getState()) {
			this.logInfo(this.log, "Schedule write [" + channelId.name() + "] state [" + window.getState()
					+ "] after readback [" + readback + "]");
		}
		this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.reconcileSafeSettings();
		}
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
				new FC3ReadRegistersTask(0xE204, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY, new UnsignedWordElement(0xE204)), //
						m(SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0xE205), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xE20A, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0xE20A), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xE215, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.BMS_COMMUNICATION, new UnsignedWordElement(0xE215))), //
				new FC3ReadRegistersTask(0xE026, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_START, new UnsignedWordElement(0xE026)), //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP, new UnsignedWordElement(0xE027))), //
				new FC3ReadRegistersTask(0xE02C, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.CHARGE_SCHEDULE_ENABLE, new UnsignedWordElement(0xE02C)), //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_START, new UnsignedWordElement(0xE02D)), //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_STOP, new UnsignedWordElement(0xE02E))), //
				new FC3ReadRegistersTask(0xE033, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE, new UnsignedWordElement(0xE033)), //
						// 0xE034-0xE036 is the inverter real-time clock (year/month, day/hour,
						// minute/second, each encoded high*256+low). Read-only here so
						// commissioning can verify the clock before any schedule is trusted.
						m(SrneBatteryInverter.ChannelId.RTC_YEAR_MONTH, new UnsignedWordElement(0xE034)), //
						m(SrneBatteryInverter.ChannelId.RTC_DAY_HOUR, new UnsignedWordElement(0xE035)), //
						m(SrneBatteryInverter.ChannelId.RTC_MINUTE_SECOND, new UnsignedWordElement(0xE036))), //
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
						this.maxChargeCurrentLimitWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[8]::onExecute, 0xE204, this.outputPriorityWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[9]::onExecute, 0xE215, this.bmsCommunicationWrite), //
				// Atomic (start, stop) block write per window, then a separate enable write.
				new FC16WriteRegistersTask(this.chargeWindow::onWindowExecute, 0xE026,
						this.chargeWindow.startWriteElement(), this.chargeWindow.stopWriteElement()), //
				new FC16WriteRegistersTask(this.chargeWindow::onEnableExecute, 0xE02C,
						this.chargeWindow.enableWriteElement()), //
				new FC16WriteRegistersTask(this.dischargeWindow::onWindowExecute, 0xE02D,
						this.dischargeWindow.startWriteElement(), this.dischargeWindow.stopWriteElement()), //
				new FC16WriteRegistersTask(this.dischargeWindow::onEnableExecute, 0xE033,
						this.dischargeWindow.enableWriteElement()));
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
		this.chargeWindow.onCycle(READBACK_TIMEOUT_CYCLES);
		this.dischargeWindow.onCycle(READBACK_TIMEOUT_CYCLES);
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
		this.reconcile(6, SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT,
				this.config.acChargeCurrentLimit(), 0, MAX_BATTERY_CURRENT_A, 10, this.acChargeCurrentLimitWrite);
		this.reconcile(7, SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT,
				this.config.maxChargeCurrentLimit(), 0, MAX_BATTERY_CURRENT_A, 10, this.maxChargeCurrentLimitWrite);
		// Source transfer: the reserve band (E01F/E020), BMS mode (E215) and output
		// priority (E204) are reconciled together as one coherent, validated operation.
		this.reconcileSourceTransfer();
		this.reconcileWindow(this.chargeWindow, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.CHARGE_SCHEDULE_ENABLE, //
				this.config.chargeWindow1Start(), this.config.chargeWindow1Stop(), this.config.chargeScheduleEnable());
		this.reconcileWindow(this.dischargeWindow, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE, //
				this.config.dischargeWindow1Start(), this.config.dischargeWindow1Stop(),
				this.config.dischargeScheduleEnable());
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
			this.logInfo(this.log, "Queued one-shot settings write [" + channelId.name() + "] from [" + actual
					+ "] to [" + channelTarget + "]");
			writeElement.setNextWriteValue(target * rawMultiplier);
		}
	}

	// JUSTIFICATION-A3: guarded reconcile for output priority (#71). Output priority =
	// SBU transfers the load onto the battery - it is effectively the "arm" for battery
	// discharge. Mirroring the schedule feature's verified-then-enable, it is written
	// only once its safety prerequisites (the reserve-floor / return SoC band and BMS
	// comms) have read-back verified, so a failed or still-pending floor write can never
	// arm battery discharge against a wrong reserve. Cannot use the flat reconcile()
	// because of this cross-setting gate.
	// JUSTIFICATION-A3: coherent source-transfer reconcile (#71, PR #25 review). Output
	// priority = SBU transfers the load onto the battery; its reserve band (E01F/E020)
	// and BMS mode (E215) are safety prerequisites. When an arm is requested the WHOLE
	// set is validated (present, individually bounded, coherent) BEFORE any of it is
	// written, so a rejected arm never leaves a bad reserve band or partial writes on the
	// live device. When no arm is requested the band and BMS remain independent settings.
	private void reconcileSourceTransfer() {
		var line = this.config.switchToLineSoc();
		var battery = this.config.switchToBatterySoc();
		var bms = this.config.bmsCommunication();
		var arm = this.config.outputPriority();
		if (arm < 0) {
			// A bad reserve band is dangerous whenever the unit is in SBU (its normal
			// backup posture, settable from the panel), so an incoherent band is never
			// written even when OpenEMS is not arming: reject both thresholds if both are
			// configured and inverted. BMS remains an independent write.
			if (line >= 0 && battery >= 0 && line >= battery) {
				this.rejectBand(line, battery);
			} else {
				this.reconcile(4, SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC, line, 0, 100, 1,
						this.switchToLineSocWrite);
				this.reconcile(5, SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC, battery, 0, 100, 1,
						this.switchToBatterySocWrite);
			}
			this.reconcile(9, SrneBatteryInverter.ChannelId.BMS_COMMUNICATION, bms, 0, 2, 1,
					this.bmsCommunicationWrite);
			return;
		}
		// Arming: validate the whole set up front and write NOTHING on failure.
		if (arm > 3) {
			this.rejectOutputPriority("output priority [" + arm + "] out of range 0..3");
			return;
		}
		if (line < 0 || battery < 0 || bms < 0) {
			this.rejectOutputPriority(
					"requires switchToLineSoc, switchToBatterySoc and bmsCommunication to all be configured");
			return;
		}
		if (line > 100 || battery > 100 || bms > 2) {
			this.rejectOutputPriority("reserve band / BMS out of range (SoC 0..100, BMS 0..2)");
			return;
		}
		if (line >= battery) {
			this.rejectOutputPriority("reserve band invalid: switchToLineSoc [" + line
					+ "] must be below switchToBatterySoc [" + battery + "]");
			return;
		}
		// Whole set valid: reconcile the band + BMS, then arm once all three are settled.
		this.reconcile(4, SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC, line, 0, 100, 1,
				this.switchToLineSocWrite);
		this.reconcile(5, SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC, battery, 0, 100, 1,
				this.switchToBatterySocWrite);
		this.reconcile(9, SrneBatteryInverter.ChannelId.BMS_COMMUNICATION, bms, 0, 2, 1, this.bmsCommunicationWrite);
		var prerequisitesSettled = isPrerequisiteSettled(line, this.writeHandlers[4].getState(),
				this.readValue(SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC))
				&& isPrerequisiteSettled(battery, this.writeHandlers[5].getState(),
						this.readValue(SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC))
				&& isPrerequisiteSettled(bms, this.writeHandlers[9].getState(),
						this.readValue(SrneBatteryInverter.ChannelId.BMS_COMMUNICATION));
		if (!prerequisitesSettled) {
			if (!this.outputPriorityHeldLogged) {
				this.logInfo(this.log, "Output-priority (SBU) write held pending its SoC-band / BMS prerequisites");
				this.outputPriorityHeldLogged = true;
			}
			return;
		}
		this.reconcile(8, SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY, arm, 0, 3, 1, this.outputPriorityWrite);
	}

	// Package-private for tests: lets a test assert that a reserve-band / BMS write did
	// NOT queue on a rejected arm (state stays IDLE), not just that the aggregate failed.
	SafeWriteHandler.State writeHandlerStateForTest(int index) {
		return this.writeHandlers[index].getState();
	}

	private void rejectOutputPriority(String reason) {
		if (this.writeHandlers[8].reject()) {
			this.logWarn(this.log, "Rejected output-priority arm: " + reason);
		}
	}

	private void rejectBand(int line, int battery) {
		var rejectedLine = this.writeHandlers[4].reject();
		var rejectedBattery = this.writeHandlers[5].reject();
		if (rejectedLine || rejectedBattery) {
			this.logWarn(this.log, "Rejected reserve-band write: switchToLineSoc [" + line
					+ "] must be below switchToBatterySoc [" + battery + "]");
		}
	}

	private Integer readValue(SrneBatteryInverter.ChannelId channelId) {
		return ((IntegerReadChannel) this.channel(channelId)).value().get();
	}

	/**
	 * Whether a configured output-priority prerequisite is settled and it is therefore
	 * safe to arm battery-priority output. Settled means the handler is at rest - either
	 * IDLE (already correct on the device, so it never queued) or VERIFIED (written and
	 * confirmed this activation) - AND the device CURRENTLY reads the configured target.
	 * A queued, awaiting or failed write is never settled even if the read momentarily
	 * matches, and a verified value that has since drifted (or is unknown) is not settled
	 * either. Callers guarantee the target is configured (&gt;= 0).
	 *
	 * <p>Requires unscaled prerequisites (rawMultiplier 1) so the raw channel read and
	 * the config target are the same space; all current prerequisites (E01F/E020/E215)
	 * are unscaled. A scaled register must not be added as a prerequisite without also
	 * scaling this comparison.
	 *
	 * @param configuredTarget the prerequisite's configured target
	 * @param state            the prerequisite write handler's current state
	 * @param actual           the prerequisite register's current read value, or null
	 * @return true if it is safe to proceed with the output-priority write
	 */
	static boolean isPrerequisiteSettled(int configuredTarget, SafeWriteHandler.State state, Integer actual) {
		if (state != SafeWriteHandler.State.IDLE && state != SafeWriteHandler.State.VERIFIED) {
			return false;
		}
		return actual != null && actual == configuredTarget;
	}

	// JUSTIFICATION-A3: guarded reconcile for a coherent TOU schedule window (#67,
	// PR #24). Reads the three device registers for the window and hands them, with
	// the configured targets, to the ScheduleWindow state machine which validates
	// the pair, writes it atomically and only then enables. Not a wrapper around
	// the scalar reconcile(): the pair validation + verified-then-enable ordering
	// cannot be expressed by the single-register path.
	private void reconcileWindow(ScheduleWindow window, SrneBatteryInverter.ChannelId startChannel,
			SrneBatteryInverter.ChannelId stopChannel, SrneBatteryInverter.ChannelId enableChannel, int cfgStart,
			int cfgStop, int cfgEnable) {
		Integer actualStart = ((IntegerReadChannel) this.channel(startChannel)).value().get();
		Integer actualStop = ((IntegerReadChannel) this.channel(stopChannel)).value().get();
		Integer actualEnable = ((IntegerReadChannel) this.channel(enableChannel)).value().get();
		var message = window.reconcile(actualStart, actualStop, actualEnable, cfgStart, cfgStop, cfgEnable);
		if (message != null) {
			if (window.getState() == ScheduleWindow.State.FAILED) {
				this.logWarn(this.log, message);
			} else {
				this.logInfo(this.log, message);
			}
		}
	}

	// JUSTIFICATION-A3: thin static delegates kept so existing unit tests and any
	// callers resolve time validation/formatting through the component; the
	// implementation now lives in ScheduleWindow with the rest of the schedule
	// logic.
	/**
	 * Whether a raw schedule value is a valid encoded time (hour*256+min, hour
	 * 0..23, minute 0..59).
	 *
	 * @param encoded the raw register value
	 * @return true if it decodes to a valid time
	 */
	static boolean isValidEncodedTime(int encoded) {
		return ScheduleWindow.isValidEncodedTime(encoded);
	}

	/**
	 * Formats an encoded schedule time (hour*256+min) as HH:mm for logging.
	 *
	 * @param encoded the raw register value
	 * @return the HH:mm string
	 */
	static String formatEncodedTime(int encoded) {
		return ScheduleWindow.formatEncodedTime(encoded);
	}

	private SafeWriteHandler.State aggregateWriteState() {
		var result = SafeWriteHandler.State.IDLE;
		for (var handler : this.writeHandlers) {
			var state = handler.getState();
			if (writeStatePrecedence(state) > writeStatePrecedence(result)) {
				result = state;
			}
		}
		for (var windowState : new SafeWriteHandler.State[] {
				scheduleWindowAsWriteState(this.chargeWindow.getState()),
				scheduleWindowAsWriteState(this.dischargeWindow.getState()) }) {
			if (writeStatePrecedence(windowState) > writeStatePrecedence(result)) {
				result = windowState;
			}
		}
		return result;
	}

	/**
	 * Projects a {@link ScheduleWindow.State} onto the shared
	 * {@link SafeWriteHandler.State} scale so both write paths aggregate into the
	 * single {@code SAFE_WRITE_STATE} channel.
	 *
	 * @param state the schedule-window state
	 * @return the equivalent settings-write state
	 */
	static SafeWriteHandler.State scheduleWindowAsWriteState(ScheduleWindow.State state) {
		return switch (state) {
		case FAILED -> SafeWriteHandler.State.FAILED;
		// The intermediate *_VERIFIED states are in-progress (more of the coherent
		// operation is still pending), so they must not read as the terminal VERIFIED.
		case DISABLE_AWAITING_READBACK, WINDOW_AWAITING_READBACK, ENABLE_AWAITING_READBACK, DISABLE_VERIFIED,
				WINDOW_VERIFIED ->
			SafeWriteHandler.State.AWAITING_READBACK;
		case DISABLE_QUEUED, WINDOW_QUEUED, ENABLE_QUEUED -> SafeWriteHandler.State.QUEUED;
		case DONE -> SafeWriteHandler.State.VERIFIED;
		case IDLE -> SafeWriteHandler.State.IDLE;
		case UNDEFINED -> SafeWriteHandler.State.UNDEFINED;
		};
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
