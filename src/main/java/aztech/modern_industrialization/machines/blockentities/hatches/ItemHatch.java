/*
 * MIT License
 *
 * Copyright (c) 2020 Azercoco & Technici4n
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package aztech.modern_industrialization.machines.blockentities.hatches;

import aztech.modern_industrialization.inventory.ConfigurableItemStack;
import aztech.modern_industrialization.inventory.MIInventory;
import aztech.modern_industrialization.machines.components.OrientationComponent;
import aztech.modern_industrialization.machines.components.sync.AutoExtract;
import aztech.modern_industrialization.machines.gui.MachineGuiParameters;
import aztech.modern_industrialization.machines.multiblocks.HatchBlockEntity;
import aztech.modern_industrialization.machines.multiblocks.HatchType;
import java.util.List;
import net.minecraft.block.entity.BlockEntityType;

public class ItemHatch extends HatchBlockEntity {
    public ItemHatch(BlockEntityType<?> type, MachineGuiParameters guiParams, boolean input, boolean upgradesToSteel, MIInventory inventory) {
        super(type, guiParams, new OrientationComponent.Params(true, true, false));

        this.input = input;
        this.upgradesToSteel = upgradesToSteel;
        this.inventory = inventory;

        registerComponents(inventory);
        registerClientComponent(new AutoExtract.Server(orientation, input));
    }

    private final boolean input;
    private final boolean upgradesToSteel;
    private final MIInventory inventory;

    @Override
    public HatchType getHatchType() {
        return input ? HatchType.ITEM_INPUT : HatchType.ITEM_OUTPUT;
    }

    @Override
    public boolean upgradesToSteel() {
        return upgradesToSteel;
    }

    @Override
    public MIInventory getInventory() {
        return inventory;
    }

    @Override
    public void appendItemInputs(List<ConfigurableItemStack> list) {
        if (input) {
            list.addAll(inventory.itemStacks);
        }
    }

    @Override
    public void appendItemOutputs(List<ConfigurableItemStack> list) {
        if (!input) {
            list.addAll(inventory.itemStacks);
        }
    }

    @Override
    protected void tickTransfer() {
        if (orientation.extractItems) {
            if (input) {
                inventory.autoInsertItems(world, pos, orientation.outputDirection);
            } else {
                inventory.autoExtractItems(world, pos, orientation.outputDirection);
            }
        }
    }
}