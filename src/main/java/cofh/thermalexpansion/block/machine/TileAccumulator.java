package cofh.thermalexpansion.block.machine;

import cofh.core.CoFHProps;
import cofh.core.network.PacketCoFHBase;
import cofh.core.util.CoreUtils;
import cofh.core.util.fluid.FluidTankAdv;
import cofh.lib.util.helpers.FluidHelper;
import cofh.lib.util.helpers.ServerHelper;
import cofh.thermalexpansion.ThermalExpansion;
import cofh.thermalexpansion.block.machine.BlockMachine.Types;
import cofh.thermalexpansion.core.TEProps;
import cofh.thermalexpansion.gui.client.machine.GuiAccumulator;
import cofh.thermalexpansion.gui.container.ContainerTEBase;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.registry.GameRegistry;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

public class TileAccumulator extends TileMachineBase implements IFluidHandler {

	public static void initialize() {

		int type = BlockMachine.Types.ACCUMULATOR.ordinal();

		defaultSideConfig[type] = new SideConfig();
		defaultSideConfig[type].numConfig = 2;
		defaultSideConfig[type].slotGroups = new int[][] { {}, {} };
		defaultSideConfig[type].allowInsertionSide = new boolean[] { false, false };
		defaultSideConfig[type].allowExtractionSide = new boolean[] { false, false };
		defaultSideConfig[type].sideTex = new int[] { 0, 4 };
		defaultSideConfig[type].defaultSides = new byte[] { 1, 1, 1, 1, 1, 1 };

		defaultEnergyConfig[type] = new EnergyConfig();
		defaultEnergyConfig[type].setParamsPower(0);

		sounds[type] = CoreUtils.getSoundName(ThermalExpansion.modId, "blockMachineAccumulator");

		GameRegistry.registerTileEntity(TileAccumulator.class, "thermalexpansion.Accumulator");
	}

	public static int genRate = 25 * CoFHProps.TIME_CONSTANT;
	public static int genRatePassive = 1 * CoFHProps.TIME_CONSTANT;
	public static boolean passiveGen = false;

	static {
		String comment = "This controls how many mB/t the Accumulator generates. (Default: 25)";
		int rate = ThermalExpansion.config.get("Machine.Accumulator", "BaseRate", TileAccumulator.genRate / CoFHProps.TIME_CONSTANT, comment);

		if (rate < 1 || rate > 1000) {
			ThermalExpansion.log.info("'Machine.Accumulator.BaseRate' config value is out of acceptable range. Using default. (25)");
		} else {
			genRate = rate * CoFHProps.TIME_CONSTANT;
		}
		comment = "This controls how many mB/t the Accumulator generates without two or more adjacent source blocks, if enabled. (Default: 1)";
		rate = ThermalExpansion.config.get("Machine.Accumulator", "PassiveRate", 1, comment);

		if (rate < 1 || rate > 1000) {
			ThermalExpansion.log.info("'Machine.Accumulator.PassiveRate' config value is out of acceptable range. Using default. (1)");
		} else {
			genRatePassive = rate * CoFHProps.TIME_CONSTANT;
		}
		comment = "Set this to true to enable passive generation (less than two adjacent sources) for the Accumulator.";
		passiveGen = ThermalExpansion.config.get("Machine.Accumulator", "PassiveGeneration", false);
	}

	FluidTankAdv tank = new FluidTankAdv(TEProps.MAX_FLUID_SMALL).setLock(FluidRegistry.WATER);

	int adjacentSources = -1;
	int outputTrackerFluid;
	boolean inHell;
	FluidStack outputBuffer;

	public TileAccumulator() {

		super(Types.ACCUMULATOR);
	}

	@Override
	public void onNeighborBlockChange() {

		super.onNeighborBlockChange();
		updateAdjacentSources();
	}

	@Override
	public void update() {

		if (ServerHelper.isClientWorld(worldObj)) {
			return;
		}
		boolean curActive = isActive;
		transferOutputFluid();

		if (isActive) {
			if (timeCheck()) {
				if (adjacentSources >= 2) {
					tank.fillLocked(genRate * processMod, true);
				} else {
					if (worldObj.isRaining() && worldObj.canSeeSky(getPos())) {
						tank.fillLocked(genRate * processMod, true);
					} else if (passiveGen) {
						tank.fillLocked(genRatePassive * processMod, true);
					}
				}
			}
			if (!redstoneControlOrDisable()) {
				isActive = false;
				wasActive = true;
				tracker.markTime(worldObj);
			}
		} else if (redstoneControlOrDisable() && canStart()) {
			isActive = true;
		}
		if (adjacentSources < 0) {
			updateAdjacentSources();
		}
		updateIfChanged(curActive);
	}

	@Override
	protected boolean canStart() {

		return !inHell;
	}

	protected void updateAdjacentSources() {

		inHell = worldObj.getBiomeGenForCoords(getPos()) == Biomes.HELL;
		adjacentSources = 0;

		if (isWater(worldObj.getBlockState(getPos().down()))) {
			++adjacentSources;
		}

		if (isWater(worldObj.getBlockState(getPos().up()))) {
			++adjacentSources;
		}

		if (isWater(worldObj.getBlockState(getPos().west()))) {
			++adjacentSources;
		}

		if (isWater(worldObj.getBlockState(getPos().east()))) {
			++adjacentSources;
		}

		if (isWater(worldObj.getBlockState(getPos().north()))) {
			++adjacentSources;
		}

		if (isWater(worldObj.getBlockState(getPos().south()))) {
			++adjacentSources;
		}
	}

	private static boolean isWater(IBlockState state){
        if (state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.FLOWING_WATER){
            return state.getValue(BlockLiquid.LEVEL) == 0;
        }
        return false;
    }

	protected void transferOutputFluid() {

		if (!augmentAutoOutput) {
			return;
		}
		if (tank.getFluidAmount() <= 0) {
			return;
		}
		int side;
		outputBuffer = new FluidStack(tank.getFluid(), Math.min(tank.getFluidAmount(), RATE));
		for (int i = outputTrackerFluid + 1; i <= outputTrackerFluid + 6; i++) {
			side = i % 6;

			if (sideCache[side] == 1) {
				int toDrain = FluidHelper.insertFluidIntoAdjacentFluidHandler(this, side, outputBuffer, true);

				if (toDrain > 0) {
					tank.drain(toDrain, true);
					outputTrackerFluid = side;
					break;
				}
			}
		}
	}

	@Override
	protected void onLevelChange() {

		super.onLevelChange();

		tank.setCapacity(TEProps.MAX_FLUID_SMALL * FLUID_CAPACITY[level]);
	}

	/* GUI METHODS */
	@Override
	public Object getGuiClient(InventoryPlayer inventory) {

		return new GuiAccumulator(inventory, this);
	}

	@Override
	public Object getGuiServer(InventoryPlayer inventory) {

		return new ContainerTEBase(inventory, this);
	}

	@Override
	public FluidTankAdv getTank() {

		return tank;
	}

	@Override
	public FluidStack getTankFluid() {

		return tank.getFluid();
	}

	/* NBT METHODS */
	@Override
	public void readFromNBT(NBTTagCompound nbt) {

		super.readFromNBT(nbt);

		inHell = nbt.getBoolean("Hell");
		adjacentSources = nbt.getInteger("Sources");
		outputTrackerFluid = nbt.getInteger("Tracker");
		tank.readFromNBT(nbt);
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {

		super.writeToNBT(nbt);

		nbt.setBoolean("Hell", inHell);
		nbt.setInteger("Sources", adjacentSources);
		nbt.setInteger("Tracker", outputTrackerFluid);
		tank.writeToNBT(nbt);
        return nbt;
	}

	/* NETWORK METHODS */
	@Override
	public PacketCoFHBase getGuiPacket() {

		PacketCoFHBase payload = super.getGuiPacket();

		payload.addInt(tank.getFluidAmount());

		return payload;
	}

	@Override
	protected void handleGuiPacket(PacketCoFHBase payload) {

		super.handleGuiPacket(payload);
        if (tank.getFluid() == null){
            tank.setLock(FluidRegistry.WATER);
        }
		tank.getFluid().amount = payload.getInt();
	}

	/* IFluidHandler */
	@Override
	public int fill(EnumFacing from, FluidStack resource, boolean doFill) {

		return 0;
	}

	@Override
	public FluidStack drain(EnumFacing from, FluidStack resource, boolean doDrain) {

		if (from != null && sideCache[from.ordinal()] < 1) {
			return null;
		}
		if (resource == null || resource.getFluid() != FluidRegistry.WATER) {
			return null;
		}
		return tank.drain(resource.amount, doDrain);
	}

	@Override
	public FluidStack drain(EnumFacing from, int maxDrain, boolean doDrain) {

		if (from != null && sideCache[from.ordinal()] < 1) {
			return null;
		}
		return tank.drain(maxDrain, doDrain);
	}

	@Override
	public boolean canFill(EnumFacing from, Fluid fluid) {

		return false;
	}

	@Override
	public boolean canDrain(EnumFacing from, Fluid fluid) {

		return true;
	}

	@Override
	public FluidTankInfo[] getTankInfo(EnumFacing from) {

		return new FluidTankInfo[] { tank.getInfo() };
	}

}
