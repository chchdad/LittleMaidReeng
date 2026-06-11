package net.blacklab.lmr.entity.littlemaid.ai;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.mode.EntityMode_Farmer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.material.Material;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class EntityAILMRFarmer extends EntityAIMoveToBlock {
    private final EntityLittleMaid maid;
    private final double moveSpeed;
    
    private int customScanDelay = 0;
    private boolean actionCompleted = false;
    
    // 【物理防卡死系统变量】
    private double lastPosX, lastPosY, lastPosZ;
    private int checkStuckTimer = 0;
    private int realStuckCount = 0;

    public EntityAILMRFarmer(EntityLittleMaid entityMaid, double speedIn) {
        super(entityMaid, speedIn, 16);
        this.maid = entityMaid;
        this.moveSpeed = speedIn;
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (!EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) || maid.isMaidWait() || maid.getCurrentEquippedItem().isEmpty()) {
            return false;
        }
        
        if (maid.getOwner() != null && maid.getDistanceSq(maid.getOwner()) > 144.0D) {
            return false;
        }

        if (this.customScanDelay > 0) {
            this.customScanDelay--;
            return false;
        }

        List<BlockPos> validTargets = new ArrayList<>();
        BlockPos center = new BlockPos(maid);
        World world = maid.getEntityWorld();
        int range = 16;

        for (int x = -range; x <= range; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (this.shouldMoveTo(world, pos)) {
                        validTargets.add(pos);
                    }
                }
            }
        }

        if (!validTargets.isEmpty()) {
            validTargets.sort(Comparator.comparingDouble(p -> p.distanceSq(center)));
            
            for (BlockPos target : validTargets) {
                if (maid.getNavigator().getPathToPos(target.up()) != null || maid.getDistanceSqToCenter(target) < 4.5D) {
                    this.destinationBlock = target;
                    this.customScanDelay = 10 + maid.getRNG().nextInt(20);
                    System.out.println("[LMR-FARM-DEBUG] 3D雷达锁定目标: " + target);
                    
                    // 启动时默认给高速档
                    maid.getNavigator().tryMoveToXYZ(target.getX() + 0.5D, target.getY() + 1, target.getZ() + 0.5D, this.moveSpeed);
                    return true;
                }
            }
        }

        this.customScanDelay = 20;
        return false;
    }

    @Override
    public void startExecuting() {
        super.startExecuting();
        this.actionCompleted = false; 
        
        this.lastPosX = maid.posX;
        this.lastPosY = maid.posY;
        this.lastPosZ = maid.posZ;
        this.checkStuckTimer = 0;
        this.realStuckCount = 0;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return !actionCompleted && super.shouldContinueExecuting() && EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) && !maid.isMaidWait();
    }

    @Override
    protected boolean shouldMoveTo(World worldIn, BlockPos pos) {
        IBlockState state = worldIn.getBlockState(pos);
        Block block = state.getBlock();

        if (block == Blocks.REEDS && worldIn.getBlockState(pos.down()).getBlock() == Blocks.REEDS) return true;
        if (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) return true;
        if (block instanceof BlockCrops && !((BlockCrops) block).isMaxAge(state)) {
            for (int i = 0; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack stack = maid.maidInventory.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() == Items.DYE && stack.getMetadata() == 15) return true;
            }
        }
        if (block == Blocks.FARMLAND && worldIn.isAirBlock(pos.up())) {
            int seedIndex = getHadSeedIndex();
            if (seedIndex != -1) {
                ItemStack seedStack = maid.maidInventory.getStackInSlot(seedIndex);
                if (isFarmedLand(worldIn, pos, seedStack)) return true;
            }
        }
        
        if (isUnfarmedLand(worldIn, pos)) return true;

        return false;
    }

    @Override
    public void updateTask() {
        super.updateTask();
        
        double dist = maid.getDistanceSqToCenter(this.destinationBlock);

        // 【智能二挡变速箱】：防鬼步滑行
        if (dist > 16.0D) { // 16.0D 就是 4 格距离的平方
            maid.getNavigator().setSpeed(1.2D); // 大于 4 格，1.2 倍速冲刺！
        } else {            
            maid.getNavigator().setSpeed(1.0D); // 进入 4 格内，平滑降速到 1.0 标准步速微调
        }
        
        // 走到 4.5 范围内执行
        if (dist < 4.5D) {
            System.out.println("[LMR-FARM-DEBUG] 抵达目标 " + this.destinationBlock + "，正常执行！");
            executeAction();
            this.actionCompleted = true;
            this.customScanDelay = 5; 
            this.realStuckCount = 0; // 重置
        } else {
            // 【硬核物理防卡死检测】
            this.checkStuckTimer++;
            if (this.checkStuckTimer >= 20) { // 每 1 秒检查一次位移
                
                // 【核心修改】：彻底无视 Y 轴跳跃，只算 X 和 Z 的水平直线距离！
                double dX = maid.posX - this.lastPosX;
                double dZ = maid.posZ - this.lastPosZ;
                double movedHorizontalSq = (dX * dX) + (dZ * dZ);
                
                // 如果水平移动微乎其微 (距离不到 0.22 格)，说明在原地跳脚死磕
                if (movedHorizontalSq < 0.05D) {
                    this.realStuckCount++;
                    System.out.println("[LMR-FARM-DEBUG] 发现原地跳脚，卡死警告等级: " + this.realStuckCount);
                } else {
                    // 如果在走动，清零卡死警告
                    this.realStuckCount = 0;
                }
                
                this.lastPosX = maid.posX;
                this.lastPosY = maid.posY; // 虽然更新 Y，但不参与计算
                this.lastPosZ = maid.posZ;
                this.checkStuckTimer = 0;
                
                // 连续 3 秒水平方向没走出半步
                if (this.realStuckCount >= 3) {
                    System.out.println("[LMR-FARM-DEBUG] 水平位移监测确认卡死！启动长臂猿原力模式！");
                    executeAction();
                    this.actionCompleted = true;
                    this.customScanDelay = 5; 
                    return; 
                }
            }

            this.maid.getLookHelper().setLookPosition(this.destinationBlock.getX() + 0.5D, this.destinationBlock.getY() + 1, this.destinationBlock.getZ() + 0.5D, 10.0F, this.maid.getVerticalFaceSpeed());
        }
    }

    private void executeAction() {
        World world = maid.getEntityWorld();
        BlockPos pos = this.destinationBlock;
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        ItemStack curStack = maid.getCurrentEquippedItem();

        if (block == Blocks.REEDS && world.getBlockState(pos.down()).getBlock() == Blocks.REEDS) {
            world.destroyBlock(pos, true);
            maid.setSwing(10, EnumSound.FARMER_HARVEST, false);
            System.out.println("[LMR-FARM-DEBUG] 动作：收甘蔗");
        }
        else if (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) {
            world.destroyBlock(pos, true);
            maid.setSwing(10, EnumSound.FARMER_HARVEST, false);
            System.out.println("[LMR-FARM-DEBUG] 动作：收割成熟庄稼");
        }
        else if (block instanceof BlockCrops && !((BlockCrops) block).isMaxAge(state)) {
            for (int i = 0; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack stackInSlot = maid.maidInventory.getStackInSlot(i);
                if (!stackInSlot.isEmpty() && stackInSlot.getItem() == Items.DYE && stackInSlot.getMetadata() == 15) {
                    if (ItemDye.applyBonemeal(stackInSlot, world, pos, maid.maidAvatar, EnumHand.MAIN_HAND)) {
                        maid.setSwing(10, EnumSound.NULL, false);
                        world.playEvent(2005, pos, 0); 
                        System.out.println("[LMR-FARM-DEBUG] 动作：骨粉催熟");
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
                    System.out.println("[LMR-FARM-DEBUG] 动作：播种");
                }
            }
        }
        else if (isUnfarmedLand(world, pos)) {
            if (curStack.onItemUse(maid.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F) == EnumActionResult.SUCCESS) {
                maid.setSwing(10, EnumSound.FARMER_FARM, false);
                curStack.damageItem(1, maid.maidAvatar); 
                System.out.println("[LMR-FARM-DEBUG] 动作：开垦荒地");
            }
        }
    }

    private int getHadSeedIndex() {
        // 1. 获取背包第一格 (Slot 0) 的物品，判断它是不是箱子
        ItemStack firstSlot = maid.maidInventory.getStackInSlot(0);
        boolean isRandomMode = !firstSlot.isEmpty() && firstSlot.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.CHEST);

        if (isRandomMode) {
            // ====== 模式 A：盲盒模式 (随机抽取种子) ======
            List<Integer> availableSeedSlots = new ArrayList<>();
            // 从下标 1 开始遍历，跳过第 0 格当开关的箱子
            for (int i = 1; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack pStack = maid.maidInventory.getStackInSlot(i);
                if (!pStack.isEmpty() && pStack.getItem() instanceof IPlantable) {
                    availableSeedSlots.add(i);
                }
            }
            // 如果找到至少一种种子，利用随机数返回其中一个格子的下标
            if (!availableSeedSlots.isEmpty()) {
                return availableSeedSlots.get(maid.getRNG().nextInt(availableSeedSlots.size()));
            }
        } else {
            // ====== 模式 B：强迫症模式 (从左到右单一种植) ======
            for (int i = 0; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack pStack = maid.maidInventory.getStackInSlot(i);
                // 遇到第一个种子就直接返回，实现连片单一种植
                if (!pStack.isEmpty() && pStack.getItem() instanceof IPlantable) return i;
            }
        }
        
        // 如果背包里完全没种子，返回 -1
        return -1;
    }


    private boolean isBlockWatered(World world, BlockPos pos) {
        int radius = 4;
        for (BlockPos.MutableBlockPos mutablePos : BlockPos.getAllInBoxMutable(pos.add(-radius, 0, -radius), pos.add(radius, 1, radius))) {
            if (world.getBlockState(mutablePos).getMaterial() == Material.WATER) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnfarmedLand(World world, BlockPos pos) {
        ItemStack offhandItem = maid.maidAvatar.getHeldItemOffhand();
        boolean isPeaceful = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT);
        if (isPeaceful) return false;

        Block block = world.getBlockState(pos).getBlock();
        if (block != Blocks.GRASS && block != Blocks.DIRT) return false;
        if (!world.isAirBlock(pos.up())) return false;
        if (!isBlockWatered(world, pos)) return false;

        boolean hasAdjacentFarmland = false;
        for (EnumFacing facing : EnumFacing.HORIZONTALS) {
            if (world.getBlockState(pos.offset(facing)).getBlock() == Blocks.FARMLAND) {
                hasAdjacentFarmland = true;
                break;
            }
        }
        if (!hasAdjacentFarmland) return false;

        return true;
    }

    private boolean isFarmedLand(World world, BlockPos pos, ItemStack seedStack) {
        if (seedStack.isEmpty() || !(seedStack.getItem() instanceof IPlantable)) return false;
        IBlockState soil = world.getBlockState(pos); 
        IPlantable seed = (IPlantable) seedStack.getItem();
        return soil.getBlock().canSustainPlant(soil, world, pos, EnumFacing.UP, seed);
    }
                }
