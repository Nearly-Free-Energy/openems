package io.openems.edge.ess.srne.backupmeter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;

import org.junit.jupiter.api.Test;

import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.meter.api.ElectricityMeter;

public class SrneBackupMeterImplTest {

	@Test
	public void testReadValidatedBackupRegisters() throws Exception {
		new ComponentTest(new SrneBackupMeterImpl()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(0x0216, //
								/* VOLTAGE_L1: 230.2 V */ 2_302, //
								/* CURRENT_L1: 3.1 A */ 31, //
								/* FREQUENCY: 49.99 Hz */ 4_999) //
						.withRegisters(0x022C, //
								/* VOLTAGE_L2: 230.0 V */ 2_300, //
								/* VOLTAGE_L3: 229.8 V */ 2_298, //
								/* CURRENT_L2: 2.8 A */ 28, //
								/* CURRENT_L3: 3.4 A */ 34) //
						.withRegisters(0x021B, //
								/* ACTIVE_POWER_L1 */ 600, //
								/* APPARENT_POWER_L1 */ 750) //
						.withRegisters(0x0232, //
								/* ACTIVE_POWER_L2 */ 500, //
								/* ACTIVE_POWER_L3 */ 400, //
								/* REACTIVE_POWER_L2 */ 120, //
								/* REACTIVE_POWER_L3 */ 90)) //
				.activate(MyConfig.create() //
						.setId("meter2") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.build()) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L1, 230_200) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L2, 230_000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L3, 229_800) //
						.output(ElectricityMeter.ChannelId.VOLTAGE, 230_000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L1, 3_100) //
						.output(ElectricityMeter.ChannelId.CURRENT_L2, 2_800) //
						.output(ElectricityMeter.ChannelId.CURRENT_L3, 3_400) //
						.output(ElectricityMeter.ChannelId.CURRENT, 9_300) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, 600) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, 500) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, 400) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, 1_500) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, 450) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, 120) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, 90) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER, 660) //
						.output(ElectricityMeter.ChannelId.FREQUENCY, 49_990)) //
				.deactivate();
	}

	@Test
	public void testMeterType() {
		var sut = new SrneBackupMeterImpl();
		org.junit.jupiter.api.Assertions.assertEquals(MeterType.CONSUMPTION_METERED, sut.getMeterType());
	}
}
