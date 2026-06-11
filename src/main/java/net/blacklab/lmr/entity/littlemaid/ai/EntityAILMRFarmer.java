package net.blacklab.lmr.entity.littlemaid.ai;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.mode.EntityMode_Farmer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIMoveToBlock;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.IPlantable;
import firis.lmlib.api.constant.EnumSound;

public class EntityAILMRFarmer extends EntityAIMoveToBlock {
    private final EntityLittleMaid maid;
    private int actionDelay = 0;

    public EntityAILMRFarmer(EntityLittleMaid entityMaid, double speedIn) {
        // 原版的 AI 寻路，半径设为 16
        super(entityMaid, speedIn, 16);
        this.maid = entityMaid;
        // 【核心】：设置 Mutex 位为 3 (1 | 2)。这会告诉系统：“我在种地时，禁止移动 AI 和看人 AI 介入！”
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        // 只有在 Farmer 模式下，且不在待命，且主手有东西才执行
        if (!EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) || maid.isMaidWait() || maid.getCurrentEquippedItem().isEmpty()) {
            return false;
        }
        
        // 防走丢：距离主人超过 12 格，停止寻路方块，让护卫 AI 接管
        if (maid.getOwner() != null && maid.getDistanceSq(maid.getOwner()) > 144.0D) {
            return false;
        }

        if (this.runDelay > 0) {
            this.runDelay--;
            return false;
        }
        this.runDelay = 10 + this.maid.getRNG().nextInt(20);

        return this.searchForDestination(); // 底层自动遍历寻找
    }

    @Override
    public boolean shouldContinueExecuting() {
        return super.shouldContinueExecuting() && EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) && !maid.isMaidWait();
    }

    @Override
    protected boolean shouldMoveTo(World worldIn, BlockPos pos) {
        // 判断当前扫描到的 pos 是否需要干活
        IBlockState state = worldIn.getBlockState(pos);
        Block block = state.getBlock();
        
        ItemStack offhandItem = maid.maidAvatar.getHeldItemOffhand();
        boolean isPeaceful = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT_SEEDS);

        // 甘蔗
        if (block == Blocks.REEDS && worldIn.getBlockState(pos.down()).getBlock() == Blocks.REEDS) return true;
        
        // 成熟庄稼
        if (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) return true;
        
        // 骨粉催熟
        if (block instanceof BlockCrops && !((BlockCrops) block).isMaxAge(state)) {
            for (int i = 0; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack stack = maid.maidInventory.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() == Items.DYE && stack.getMetadata() == 15) return true;
            }
        }
        
        // 空农田
        if (block == Blocks.FARMLAND && worldIn.isAirBlock(pos.up())) {
            int seedIndex = getHadSeedIndex();
            if (seedIndex != -1) {
                ItemStack seedStack = maid.maidInventory.getStackInSlot(seedIndex);
                if (isFarmedLand(worldIn, pos, seedStack)) return true;
            }
        }
        
        // 开垦荒地
        if (!isPeaceful && isUnfarmedLand(worldIn, pos)) return true;

        return false;
    }

    @Override
    public void updateTask() {
        super.updateTask();
        // 走到目标上方的逻辑
        if (this.getIsAboveDestination()) {
            if (actionDelay > 0) {
                actionDelay--;
                return;
            }
            executeAction();
            this.actionDelay = 10; // 执行完后冷却半秒
        } else {
            // 头转向目标
            this.maid.getLookHelper().setLookPosition(this.destinationBlock.getX() + 0.5D, this.destinationBlock.getY() + 1, this.destinationBlock.getZ() + 0.5D, 10.0F, this.maid.getVerticalFaceSpeed());
        }
    }

    private void executeAction() {
        World world = maid.getEntityWorld();
        BlockPos pos = this.destinationBlock;
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        ItemStack curStack = maid.getCurrentEquippedItem();
        
        ItemStack offhandItem = maid.maidAvatar.getHeldItemOffhand();
        boolean isPeaceful = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT_SEEDS);

        if (block == Blocks.REEDS && world.getBlockState(pos.down()).getBlock() == Blocks.REEDS) {
            world.destroyBlock(pos, true);
            maid.setSwing(10, EnumSound.FARMER_HARVEST, false);
        }
        else if (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) {
            world.destroyBlock(pos, true);
            maid.setSwing(10, EnumSound.FARMER_HARVEST, false);
        }
        else if (block instanceof BlockCrops && !((BlockCrops) block).isMaxAge(state)) {
            for (int i = 0; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack stackInSlot = maid.maidInventory.getStackInSlot(i);
                if (!stackInSlot.isEmpty() && stackInSlot.getItem() == Items.DYE && stackInSlot.getMetadata() == 15) {
                    if (ItemDye.applyBonemeal(stackInSlot, world, pos, maid.maidAvatar, EnumHand.MAIN_HAND)) {
                        maid.setSwing(10, EnumSound.NULL, false);
                        world.playEvent(2005, pos, 0); 
                        break;
                    }
                }
            }
        }
        else if (block == Blocks.FARMLAND && world.isAirBlock(pos.up())) {
            int seedIndex = getHadSeedIndex();
            if (seedIndex != -1) {
                ItemStack seedStack = maid.maidInventory.getStackInSlot(seedIndex);
                if (isFarmedLand(world, pos, seedStack)) {
                    int svCurrentIdx = maid.getDataWatchCurrentItem();
                    maid.maidInventory.setCurrentItemIndex(seedIndex);
                    seedStack.onItemUse(maid.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F);
                    maid.maidInventory.setCurrentItemIndex(svCurrentIdx);
                    maid.setSwing(10, EnumSound.FARMER_PLANT, false);
                    if (seedStack.getCount() <= 0) maid.maidInventory.setInventorySlotContents(seedIndex, ItemStack.EMPTY);
                }
            }
        }
        else if (!isPeaceful && isUnfarmedLand(world, pos)) {
            if (curStack.onItemUse(maid.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F) == EnumActionResult.SUCCESS) {
                maid.setSwing(10, EnumSound.FARMER_FARM, false);
                curStack.damageItem(1, maid.maidAvatar); 
            }
        }
    }

    // 各种判定辅助方法
    private int getHadSeedIndex(){
        for (int i=0; i < maid.maidInventory.getSizeInventory(); i++) {
            ItemStack pStack = maid.maidInventory.getStackInSlot(i);
            if (!pStack.isEmpty() && pStack.getItem() instanceof IPlantable) return i;
        }
        return -1;
    }

    private boolean isUnfarmedLand(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        if (block != Blocks.GRASS && block != Blocks.DIRT) return false;
        if (!world.isAirBlock(pos.up())) return false;
        for (EnumFacing facing : EnumFacing.HORIZONTALS) {
            if (world.getBlockState(pos.offset(facing)).getBlock() == Blocks.FARMLAND) return true;
        }
        return false;
    }

    private boolean isFarmedLand(World world, BlockPos pos, ItemStack seedStack) {
        if (seedStack.isEmpty() || !(seedStack.getItem() instanceof IPlantable)) return false;
        IBlockState soil = world.getBlockState(pos); 
        IPlantable seed = (IPlantable) seedStack.getItem();
        return soil.getBlock().canSustainPlant(soil, world, pos, EnumFacing.UP, seed);
    }
}
