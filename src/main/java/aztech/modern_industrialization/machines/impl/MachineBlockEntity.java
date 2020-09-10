package aztech.modern_industrialization.machines.impl;

import alexiil.mc.lib.attributes.AttributeList;
import alexiil.mc.lib.attributes.AttributeProviderBlockEntity;
import alexiil.mc.lib.attributes.fluid.volume.FluidKey;
import alexiil.mc.lib.attributes.fluid.volume.FluidKeys;
import aztech.modern_industrialization.MIFluids;
import aztech.modern_industrialization.api.EnergyInsertable;
import aztech.modern_industrialization.inventory.ConfigurableFluidStack;
import aztech.modern_industrialization.inventory.ConfigurableInventory;
import aztech.modern_industrialization.inventory.ConfigurableItemStack;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import aztech.modern_industrialization.machines.recipe.MachineRecipeType;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.Tickable;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static alexiil.mc.lib.attributes.Simulation.ACTION;
import static alexiil.mc.lib.attributes.Simulation.SIMULATE;

// TODO: refactor
public class MachineBlockEntity extends AbstractMachineBlockEntity
        implements Tickable, ExtendedScreenHandlerFactory, MachineInventory, AttributeProviderBlockEntity {
    protected static final FluidKey STEAM_KEY = MIFluids.FLUID_STEAM.key;

    protected List<ConfigurableItemStack> itemStacks;
    protected List<ConfigurableFluidStack> fluidStacks;

    protected long storedEu = 0;
    protected long getMaxStoredEu() {
        return factory.tier.getMaxStoredEu();
    }

    protected MachineFactory factory;
    protected MachineRecipeType recipeType;
    protected MachineRecipe activeRecipe = null;
    protected Identifier delayedActiveRecipe;
    protected boolean usedAmp = false;

    protected int usedEnergy;
    protected int recipeEnergy;
    protected int recipeMaxEu;

    // Used for efficiency display in the gui.
    // TODO: recipe efficiency and efficiency progress bar
    protected int efficiencyTicks;
    protected int maxEfficiencyTicks;

    private PropertyDelegate propertyDelegate;

    public MachineBlockEntity(MachineFactory factory, MachineRecipeType recipeType) {
        super(factory.blockEntityType, Direction.NORTH);
        this.factory = factory;
        this.recipeType = recipeType;
        itemStacks = new ArrayList<>();
        for(int i = 0; i < factory.getInputSlots(); ++i) {
            itemStacks.add(ConfigurableItemStack.standardInputSlot());
        }
        for(int i = 0; i < factory.getOutputSlots(); ++i) {
            itemStacks.add(ConfigurableItemStack.standardOutputSlot());
        }
        fluidStacks = new ArrayList<>();
        for(int i = 0; i < factory.getLiquidInputSlots(); ++i) {
            if(i == 0 && factory instanceof SteamMachineFactory) {
                fluidStacks.add(ConfigurableFluidStack.lockedInputSlot(((SteamMachineFactory) factory).getSteamBucketCapacity() * 1000, STEAM_KEY));
            } else {
                fluidStacks.add(ConfigurableFluidStack.standardInputSlot(factory.getInputBucketCapacity() * 1000));
            }
        }
        for(int i = 0; i < factory.getLiquidOutputSlots(); ++i) {
            fluidStacks.add(ConfigurableFluidStack.standardOutputSlot(factory.getOutputBucketCapacity() * 1000));
        }

        this.propertyDelegate = new PropertyDelegate() {
            @Override
            public int get(int index) {
                if(index == 0) return isActive ? 1 : 0;
                else if(index == 1) return usedEnergy;
                else if(index == 2) return recipeEnergy;
                else if(index == 3) return efficiencyTicks;
                else if(index == 4) return maxEfficiencyTicks;
                else if(index == 5) return (int)storedEu;
                else return -1;
            }

            @Override
            public void set(int index, int value) {
                if(index == 0) isActive = value == 1;
                else if(index == 1) usedEnergy = value;
                else if(index == 2) recipeEnergy = value;
                else if(index == 3) efficiencyTicks = value;
                else if(index == 4) maxEfficiencyTicks = value;
                else if(index == 5) storedEu = value;
            }

            @Override
            public int size() {
                return 6;
            }
        };

    }



    @Override
    public List<ConfigurableItemStack> getItemStacks() {
        return itemStacks;
    }

    @Override
    public List<ConfigurableFluidStack> getFluidStacks() {
        return fluidStacks;
    }

    @Override
    public Text getDisplayName() {
        return new TranslatableText(factory.getTranslationKey());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
        return new MachineScreenHandler(syncId, inv, this, this.propertyDelegate, this.factory);
    }

    @Override
    public CompoundTag toTag(CompoundTag tag) {
        super.toTag(tag);
        writeToTag(tag);
        tag.putInt("usedEnergy", this.usedEnergy);
        tag.putInt("recipeEnergy", this.recipeEnergy);
        tag.putInt("recipeMaxEu", this.recipeMaxEu);
        if(activeRecipe != null) {
            tag.putString("activeRecipe", this.activeRecipe.getId().toString());
        }
        tag.putInt("efficiencyTicks", this.efficiencyTicks);
        tag.putInt("maxEfficiencyTicks", this.maxEfficiencyTicks);
        tag.putLong("storedEu", this.storedEu);
        return tag;
    }

    @Override
    public void fromTag(BlockState state, CompoundTag tag) {
        super.fromTag(state, tag);
        {
            // This is a failsafe in case the number of slots in a machine changed
            // When this happens, we destroy all items/fluids, but at least we don't crash the world.
            // TODO: find a better solution?
            List<ConfigurableItemStack> itemStackCopy = ConfigurableItemStack.copyList(itemStacks);
            List<ConfigurableFluidStack> fluidStackCopy = ConfigurableFluidStack.copyList(fluidStacks);
            readFromTag(tag);
            if (itemStackCopy.size() != itemStacks.size()) {
                itemStacks = itemStackCopy;
            }
            if (fluidStackCopy.size() != fluidStacks.size()) {
                fluidStacks = fluidStackCopy;
            }
        }
        this.usedEnergy = tag.getInt("usedEnergy");
        this.recipeEnergy = tag.getInt("recipeEnergy");
        this.recipeMaxEu = tag.getInt("recipeMaxEu");
        this.delayedActiveRecipe = tag.contains("activeRecipe") ? new Identifier(tag.getString("activeRecipe")) : null;
        this.efficiencyTicks = tag.getInt("efficiencyTicks");
        this.maxEfficiencyTicks = tag.getInt("maxEfficiencyTicks");
        this.storedEu = tag.getLong("storedEu");
    }

    public MachineFactory getFactory() {
        return factory;
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity serverPlayerEntity, PacketByteBuf packetByteBuf) {
        MachineInventories.toBuf(packetByteBuf, this);
        packetByteBuf.writeInt(propertyDelegate.size());
        packetByteBuf.writeString(factory.getID());
    }

    protected void loadDelayedActiveRecipe() {
        if(delayedActiveRecipe != null) {
            activeRecipe = recipeType.getRecipe((ServerWorld) world, delayedActiveRecipe);
            delayedActiveRecipe = null;
            if(activeRecipe == null) { // If a recipe got removed, we need to reset the efficiency and the used energy to allow the machinea to resume processing.
                efficiencyTicks = 0;
                usedEnergy = 0;
            }
        }
    }

    protected Iterable<MachineRecipe> getRecipes() {
        if(efficiencyTicks > 0) {
            return Collections.singletonList(activeRecipe);
        } else {
            return recipeType.getRecipes((ServerWorld) world);
        }
    }

    public MachineTier getTier() { return factory.tier; }

    /**
     * Try to start a recipe. Return true if success, false otherwise. If false, nothing was changed.
     */
    protected boolean tryStartRecipe(MachineRecipe recipe) {
        if (takeItemInputs(recipe, true) && takeFluidInputs(recipe, true) && putItemOutputs(recipe, true, false) && putFluidOutputs(recipe, true, false)) {
            takeItemInputs(recipe, false);
            takeFluidInputs(recipe, false);
            putItemOutputs(recipe, true, true);
            putFluidOutputs(recipe, true, true);
            return true;
        } else {
            return false;
        }
    }

    public static double getOverclock(MachineTier tier, int efficiencyTicks) {
        return tier.getBaseOverclock() * Math.pow(Math.pow(2.0, 1.0/64.0), efficiencyTicks);
    }

    public static int getRecipeMaxEu(MachineTier tier, int eu, int efficiencyTicks) {
        return Math.min((int) Math.floor(eu*getOverclock(tier, efficiencyTicks)), tier.getMaxEu());
    }

    private int getRecipeMaxEfficiencyTicks(int eu) {
        if(efficiencyTicks != 0) throw new RuntimeException("Illegal state");
        for(int ticks = 0; true; ++ticks) {
            if(getRecipeMaxEu(getTier(), eu, ticks) == getTier().getMaxEu()) return ticks;
        }
    }

    protected boolean updateActiveRecipe() {
        for (MachineRecipe recipe : getRecipes()) {
            if(recipe.eu > getTier().getMaxEu()) continue;
            if (tryStartRecipe(recipe)) {
                if(activeRecipe != recipe) {
                    maxEfficiencyTicks = getRecipeMaxEfficiencyTicks(recipe.eu);
                }
                activeRecipe = recipe;
                usedEnergy = 0;
                recipeEnergy = recipe.eu * recipe.duration;
                recipeMaxEu = getRecipeMaxEu(getTier(), recipe.eu, efficiencyTicks);
                return true;
            }
        }
        return false;
    }

    @Override
    public void tick() {
        if(world.isClient) return;
        loadDelayedActiveRecipe();

        boolean wasActive = isActive;
        usedAmp = false;

        // START RECIPE IF NECESSARY
        // usedEnergy == 0 means that no recipe is currently started
        boolean recipeStarted = false;
        if(usedEnergy == 0 && canRecipeStart()) {
            if(getEu(1, true) == 1) {
                recipeStarted = updateActiveRecipe();
            }
        }

        // PROCESS RECIPE TICK
        int eu = 0;
        boolean finishedRecipe = false; // whether the recipe finished this tick
        if(activeRecipe != null && canRecipeProgress() && (usedEnergy > 0 || recipeStarted)) {
            eu = getEu(Math.min(recipeMaxEu, recipeEnergy - usedEnergy), false);
            isActive = eu > 0;
            usedEnergy += eu;

            if(usedEnergy == recipeEnergy) {
                putItemOutputs(activeRecipe, false, false);
                putFluidOutputs(activeRecipe, false, false);
                clearLocks();
                usedEnergy = 0;
                finishedRecipe = true;
            }
        } else {
            isActive = false;
        }

        // ADD OR REMOVE EFFICIENCY TICKS
        // If we finished a recipe, we can add an efficiency tick
        if(finishedRecipe) {
            if(efficiencyTicks < maxEfficiencyTicks) ++efficiencyTicks;
        } else if(eu < recipeMaxEu) { // If we didn't use the max energy this tick and the recipe is still ongoing, remove one efficiency tick
            if(efficiencyTicks > 0) {
                efficiencyTicks--;
                if(efficiencyTicks == 0 && usedEnergy == 0) { // If the recipe is done, allow starting another one when the efficiency reaches zero
                    activeRecipe = null;
                }
            }
        }

        if(wasActive != isActive) {
            sync();
        }
        markDirty();

        autoExtract();
    }

    protected boolean canRecipeStart() {
        return true;
    }

    protected void autoExtract() {
        if(outputDirection != null) {
            if(extractItems) autoExtractItems(outputDirection, world.getBlockEntity(pos.offset(outputDirection)));
            if(extractFluids) autoExtractFluids(world, pos, outputDirection);
        }
    }

    // Must be true if canRecipeStart is true!
    protected boolean canRecipeProgress() {
        return true;
    }

    public List<ConfigurableItemStack> getItemInputStacks() {
        return itemStacks.subList(0, factory.getInputSlots());
    }
    public List<ConfigurableFluidStack> getFluidInputStacks() {
        return fluidStacks.subList(factory instanceof SteamMachineFactory ? 1 : 0, factory.getLiquidInputSlots());
    }
    public List<ConfigurableItemStack> getItemOutputStacks() {
        return itemStacks.subList(factory.getInputSlots(), itemStacks.size());
    }
    public List<ConfigurableFluidStack> getFluidOutputStacks() {
        return fluidStacks.subList(factory.getLiquidInputSlots(), fluidStacks.size());
    }

    protected boolean takeItemInputs(MachineRecipe recipe, boolean simulate) {
        List<ConfigurableItemStack> baseList = getItemInputStacks();
        List<ConfigurableItemStack> stacks = simulate ? ConfigurableItemStack.copyList(baseList) : baseList;

        boolean ok = true;
        for(MachineRecipe.ItemInput input : recipe.itemInputs) {
            if(!simulate && input.probability < 1) { // if we are not simulating, there is a chance we don't need to take this output
                if(ThreadLocalRandom.current().nextFloat() >= input.probability) {
                    continue;
                }
            }
            int remainingAmount = input.amount;
            for(ConfigurableItemStack stack : stacks) {
                if(input.matches(stack.getStack())) {
                    ItemStack taken = stack.splitStack(remainingAmount);
                    remainingAmount -= taken.getCount();
                    if(remainingAmount == 0) break;
                }
            }
            if(remainingAmount > 0) ok = false;
        }
        return ok;
    }

    protected boolean takeFluidInputs(MachineRecipe recipe, boolean simulate) {
        List<ConfigurableFluidStack> baseList = getFluidInputStacks();
        List<ConfigurableFluidStack> stacks = simulate ? ConfigurableFluidStack.copyList(baseList) : baseList;

        boolean ok = true;
        for(MachineRecipe.FluidInput input : recipe.fluidInputs) {
            if(!simulate && input.probability < 1) { // if we are not simulating, there is a chance we don't need to take this output
                if(ThreadLocalRandom.current().nextFloat() >= input.probability) {
                    continue;
                }
            }
            int remainingAmount = input.amount;
            for(ConfigurableFluidStack stack : stacks) {
                if(stack.getFluid().getRawFluid() == input.fluid) {
                    int taken = Math.min(remainingAmount, stack.getAmount());
                    stack.decrement(taken);
                    remainingAmount -= taken;
                    if(remainingAmount == 0) break;
                }
            }
            if(remainingAmount > 0) ok = false;
        }
        return ok;
    }

    protected boolean putItemOutputs(MachineRecipe recipe, boolean simulate, boolean toggleLock) {
        List<ConfigurableItemStack> baseList = getItemOutputStacks();
        List<ConfigurableItemStack> stacks = simulate ? ConfigurableItemStack.copyList(baseList) : baseList;

        List<Integer> locksToToggle = new ArrayList<>();
        List<Item> lockItems = new ArrayList<>();

        boolean ok = true;
        for(MachineRecipe.ItemOutput output : recipe.itemOutputs) {
            if(output.probability < 1) {
                if(simulate) continue; // don't check output space for probabilistic recipes
                float randFloat = ThreadLocalRandom.current().nextFloat();
                if(randFloat > output.probability) continue;
            }
            int remainingAmount = output.amount;
            // Try to insert in non-empty stacks or locked first, then also allow insertion in empty stacks.
            for(int loopRun = 0; loopRun < 2; loopRun++) {
                int stackId = 0;
                for (ConfigurableItemStack stack : stacks) {
                    stackId++;
                    ItemStack st = stack.getStack();
                    if(st.getItem() == output.item || st.isEmpty()) {
                        int ins = Math.min(remainingAmount, output.item.getMaxCount() - st.getCount());
                        if (st.isEmpty()) {
                            if ((stack.isMachineLocked() || stack.isPlayerLocked() || loopRun == 1) && stack.canInsert(new ItemStack(output.item), null)) {
                                stack.setStack(new ItemStack(output.item, ins));
                            } else {
                                ins = 0;
                            }
                        } else {
                            st.increment(ins);
                        }
                        remainingAmount -= ins;
                        if(ins > 0) {
                            locksToToggle.add(stackId-1);
                            lockItems.add(output.item);
                        }
                        if (remainingAmount == 0) break;
                    }
                }
            }
            if(remainingAmount > 0) ok = false;
        }

        if(toggleLock) {
            for(int i = 0; i < locksToToggle.size(); i++) {
                baseList.get(locksToToggle.get(i)).enableMachineLock(lockItems.get(i));
            }
        }
        return ok;
    }

    protected boolean putFluidOutputs(MachineRecipe recipe, boolean simulate, boolean toggleLock) {
        List<ConfigurableFluidStack> baseList = getFluidOutputStacks();
        List<ConfigurableFluidStack> stacks = simulate ? ConfigurableFluidStack.copyList(baseList) : baseList;

        List<Integer> locksToToggle = new ArrayList<>();
        List<FluidKey> lockFluids = new ArrayList<>();

        boolean ok = true;
        for(MachineRecipe.FluidOutput output : recipe.fluidOutputs) {
            if(output.probability < 1) {
                if(simulate) continue; // don't check output space for probabilistic recipes
                float randFloat = ThreadLocalRandom.current().nextFloat();
                if(randFloat > output.probability) continue;
            }
            FluidKey key = FluidKeys.get(output.fluid);
            int remainingAmount = ConfigurableInventory.internalInsert(stacks, key, output.amount, simulate ? SIMULATE : ACTION, s -> true, index -> {
                locksToToggle.add(index);
                lockFluids.add(key);
            }, () -> {
                if(!simulate) markDirty();
            });
            if(remainingAmount > 0) ok = false;
        }

        if(toggleLock) {
            for(int i = 0; i < locksToToggle.size(); i++) {
                baseList.get(locksToToggle.get(i)).enableMachineLock(lockFluids.get(i));
            }
        }
        return ok;
    }

    protected void clearLocks() {
        for(ConfigurableItemStack stack : getItemOutputStacks()) {
            if (stack.isMachineLocked()) stack.disableMachineLock();
        }
        for(ConfigurableFluidStack stack : getFluidOutputStacks()) {
            if (stack.isMachineLocked()) stack.disableMachineLock();
        }
    }

    protected List<ConfigurableFluidStack> getSteamInputStacks() {
        return fluidStacks.subList(0, 1);
    }

    protected int getEu(int maxEu, boolean simulate) {
        if(factory instanceof SteamMachineFactory) {
            int totalRem = 0;
            for(ConfigurableFluidStack stack : getSteamInputStacks()) {
                if(stack.getFluid() == STEAM_KEY) {
                    int amount = stack.getAmount();
                    int rem = Math.min(maxEu, amount);
                    if (!simulate) {
                        stack.decrement(rem);
                    }
                    maxEu -= rem;
                    totalRem += rem;
                }
            }
            return totalRem;
        } else {
            int ext = (int) Math.min(storedEu, maxEu);
            if(!simulate) {
                storedEu -= ext;
            }
            return ext;
        }
    }

    @Override
    public void setItemExtract(boolean extract) {
        extractItems = extract;
        if(!world.isClient) sync();
        markDirty();
    }

    @Override
    public void setFluidExtract(boolean extract) {
        extractFluids = extract;
        if(!world.isClient) sync();
        markDirty();
    }

    @Override
    public boolean getItemExtract() {
        return extractItems;
    }

    @Override
    public boolean getFluidExtract() {
        return extractFluids;
    }

    @Override
    public void addAllAttributes(AttributeList<?> to) {
        if(factory.tier.isElectric()) {
            to.offer((EnergyInsertable) (packet, simulation) -> {
                if(usedAmp) return false;
                if(factory.tier.getMaxEu() >= packet && storedEu + packet <= getMaxStoredEu()) {
                    if(simulation.isAction()) {
                        storedEu += packet;
                        usedAmp = true;
                        markDirty();
                    }
                    return true;
                }
                return false;
            });
        }
    }
}