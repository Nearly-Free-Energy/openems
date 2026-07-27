package io.openems.edge.ess.srne.gridmeter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;

import org.junit.jupiter.api.Test;

import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.meter.api.ElectricityMeter;

public class SrneGridMeterImplTest {

	@Test
	public void testReadValidatedGridRegisters() throws Exception {
		new ComponentTest(new SrneGridMeterImpl()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(0x0213, //
								/* VOLTAGE_L1: 230.1 V */ 2_301, //
								/* CURRENT_L1: 4.2 A */ 42, //
								/* FREQUENCY: 50.00 Hz */ 5_000) //
						.withRegisters(0x022A, //
								/* VOLTAGE_L2: 229.8 V */ 2_298, //
								/* VOLTAGE_L3: 231.0 V */ 2_310) //
						.withRegisters(0x0238, //
								/* CURRENT_L2: 5.1 A */ 51, //
								/* CURRENT_L3: 6.3 A */ 63, //
								/* ACTIVE_POWER_L1: 100 W */ 100, //
								/* ACTIVE_POWER_L2: 200 W */ 200, //
								/* ACTIVE_POWER_L3: 300 W */ 300)) //
				.activate(MyConfig.create() //
						.setId("meter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.build()) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L1, 230_100) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L2, 229_800) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L3, 231_000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE, 230_300) //
						.output(ElectricityMeter.ChannelId.CURRENT_L1, 4_200) //
						.output(ElectricityMeter.ChannelId.CURRENT_L2, 5_100) //
						.output(ElectricityMeter.ChannelId.CURRENT_L3, 6_300) //
						.output(ElectricityMeter.ChannelId.CURRENT, 15_600) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, 100) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, 200) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, 300) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, 600) //
						.output(ElectricityMeter.ChannelId.FREQUENCY, 50_000)) //
				.deactivate();
	}

	@Test
	public void testMeterType() {
		var sut = new SrneGridMeterImpl();
		org.junit.jupiter.api.Assertions.assertEquals(MeterType.GRID, sut.getMeterType());
	}
}
