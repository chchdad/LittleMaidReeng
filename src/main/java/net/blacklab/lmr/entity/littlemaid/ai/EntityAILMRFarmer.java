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
    
    // 我们自己接管的短冷却器，0.5秒到1.5秒扫一次
    private int customScanDelay = 0;
    // 动作是否完成的标志
    private boolean actionCompleted = false;

    public EntityAILMRFarmer(EntityLittleMaid entityMaid, double speedIn) {
        super(entityMaid, speedIn, 16);
        this.maid = entityMaid;
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

        // 接管并覆盖原版的脑残 200 Tick 冷却！
        if (this.customScanDelay > 0) {
            this.customScanDelay--;
            return false;
        }

        // 强行把原版冷却清零，逼迫它立刻调用底层搜索逻辑
        this.runDelay = 0;
        boolean foundTarget = super.shouldExecute();
        
        // 搜索完后，进入我们自己的短冷却（10到30 Tick）
        this.customScanDelay = 10 + this.maid.getRNG().nextInt(20);

        if (foundTarget) {
            System.out.println("[LMR-FARM-DEBUG] 雷达锁定目标方块: " + this.destinationBlock);
        }

        return foundTarget;
    }

    @Override
    public void startExecuting() {
        super.startExecuting();
        this.actionCompleted = false; // 任务开始，标志复位
    }

    @Override
    public boolean shouldContinueExecuting() {
        // 如果干完活了（actionCompleted 为 true），立刻中止当前寻路，准备找下一个目标！
        return !actionCompleted && super.shouldContinueExecuting() && EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) && !maid.isMaidWait();
    }

    @Override
    protected boolean shouldMoveTo(World worldIn, BlockPos pos) {
        IBlockState state = worldIn.getBlockState(pos);
        Block block = state.getBlock();
        ItemStack offhandItem = maid.maidAvatar.getHeldItemOffhand();
        boolean isPeaceful = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT_SEEDS);

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
        if (!isPeaceful && isUnfarmedLand(worldIn, pos)) return true;

        return false;
    }

    @Override
    public void updateTask() {
        super.updateTask();
        
        // 【核心修复】：放宽距离判定！只要距离在 4.5 范围内（大约两格出头），就算走到了！
        double dist = maid.getDistanceSqToCenter(this.destinationBlock);
        
        if (dist < 4.5D) {
            System.out.println("[LMR-FARM-DEBUG] 抵达目标 " + this.destinationBlock + "，距离 " + dist + "，开始执行动作！");
            
            // 执行破坏、种植等逻辑
            executeAction();
            
            // 动作完毕，标记为完成，逼迫 AI 瞬间结束当前周期
            this.actionCompleted = true;
            this.customScanDelay = 5; // 干完活只休息 5 Tick 就接着扫下一个
        } else {
            // 如果还没走到，头看向目标
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
        else if (!isPeaceful && isUnfarmedLand(world, pos)) {
            if (curStack.onItemUse(maid.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F) == EnumActionResult.SUCCESS) {
                maid.setSwing(10, EnumSound.FARMER_FARM, false);
                curStack.damageItem(1, maid.maidAvatar); 
                System.out.println("[LMR-FARM-DEBUG] 动作：开垦荒地");
            }
        }
    }

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
