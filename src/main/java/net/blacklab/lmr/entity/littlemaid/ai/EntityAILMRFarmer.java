package net.blacklab.lmr.entity.littlemaid.ai;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.mode.EntityMode_Farmer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIMoveToBlock;
import net.minecraft.entity.player.EntityPlayer;
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
    private int actionCooldown = 0;
    
    private double lastPosX, lastPosY, lastPosZ;
    private int checkStuckTimer = 0;
    private int realStuckCount = 0;
    
    private BlockPos blacklistedTarget = null;
    private long blacklistEndTime = 0;
    
    private boolean isPatrolling = false;
    private boolean ignoreColleagues = false;

    private double lastOwnerX, lastOwnerY, lastOwnerZ;
    private boolean isOwnerMoving = false;
    private long lastCheckTime = 0;
    private int ownerStopTicks = 0;

    // 巡逻防扎堆死锁
    private long nextPatrolYieldTime = 0;
    private int consecutivePatrolYields = 0;
    private long lastPatrolYieldTime = 0;

    // Tick Slicing状态变量
    private int currentScanRing = 0;
    private BlockPos currentScanCenter = null;
    private static final int RING_STEP = 6; // 每次扫 6 格宽度的同心圆环
    private static final int MAX_RING = 24;

    public EntityAILMRFarmer(EntityLittleMaid entityMaid, double speedIn) {
        super(entityMaid, speedIn, 16);
        this.maid = entityMaid;
        this.moveSpeed = speedIn;
        this.setMutexBits(3);
    }

    private boolean checkOwnerMoving(EntityPlayer player) {
        if (player == null) return false;
        long currentTime = maid.getEntityWorld().getTotalWorldTime();
        if (currentTime == lastCheckTime) return isOwnerMoving;

        double dX = player.posX - this.lastOwnerX;
        double dZ = player.posZ - this.lastOwnerZ;
        this.lastOwnerX = player.posX;
        this.lastOwnerY = player.posY;
        this.lastOwnerZ = player.posZ;
        lastCheckTime = currentTime;

        if (dX * dX + dZ * dZ > 0.005D) {
            ownerStopTicks = 0;
            isOwnerMoving = true;
        } else {
            ownerStopTicks++;
            isOwnerMoving = ownerStopTicks < 10;
        }
        return isOwnerMoving;
    }
    
    private boolean searchNextTargetSpliced() {
        if (this.currentScanRing == 0 || this.currentScanCenter == null) {
            this.currentScanCenter = new BlockPos(maid);
        }

        int startR = this.currentScanRing;
        int endR = Math.min(startR + RING_STEP - 1, MAX_RING); 

        List<BlockPos> validTargets = new ArrayList<>();
        World world = maid.getEntityWorld();
        EntityPlayer owner = (maid.getOwner() instanceof EntityPlayer) ? (EntityPlayer) maid.getOwner() : null;

        // 逐层向外扩散扫描，每一 Tick 只扫描一个特定宽度的圆环
        for (int x = -endR; x <= endR; x++) {
            for (int z = -endR; z <= endR; z++) {
                // 如果落在内圈（上一帧已经扫过了），直接跳过
                if (Math.max(Math.abs(x), Math.abs(z)) < startR) continue;
                
                for (int y = -2; y <= 2; y++) {
                    BlockPos pos = this.currentScanCenter.add(x, y, z);
                    
                    if (!world.isBlockLoaded(pos)) continue;

                    if (owner != null && !maid.isFreedom() && owner.getDistanceSqToCenter(pos) > 625.0D) {
                        continue;
                    }
                    if (this.blacklistedTarget != null && this.blacklistedTarget.equals(pos)) {
                        if (world.getTotalWorldTime() < this.blacklistEndTime) continue;
                        else this.blacklistedTarget = null;
                    }

                    if (this.shouldMoveTo(world, pos)) {
                        validTargets.add(pos);
                    }
                }
            }
        }

        // 指向下一个环的起点
        this.currentScanRing = endR + 1;

        if (!validTargets.isEmpty()) {
            this.isPatrolling = false;
            // 同一个环内的再算一次精确距离排序
            validTargets.sort(Comparator.comparingDouble(p -> p.distanceSq(this.currentScanCenter)));
            
            int pathfindAttempts = 0;
            for (BlockPos target : validTargets) {
                if (maid.getDistanceSqToCenter(target) < 4.5D) {
                    this.destinationBlock = target;
                    maid.getNavigator().tryMoveToXYZ(target.getX() + 0.5D, target.getY() + 1, target.getZ() + 0.5D, this.moveSpeed);
                    return true;
                }
                
                if (pathfindAttempts >= 2) break; // 防止在一帧内过度寻路
                pathfindAttempts++;
                
                net.minecraft.pathfinding.Path path = maid.getNavigator().getPathToPos(target.up());
                if (path != null) {
                    net.minecraft.pathfinding.PathPoint endPoint = path.getFinalPathPoint();
                    if (endPoint != null) {
                        double pathErrorDist = target.distanceSq(endPoint.x, endPoint.y, endPoint.z);
                        if (pathErrorDist <= 4.0D) {
                            this.destinationBlock = target;
                            maid.getNavigator().setPath(path, this.moveSpeed);
                            return true;
                        }
                    }
                }
            }
        }

        // 如果整个 24 格都扫完了也没找到作物，那就触发“随机采样”找空地溜达
        if (this.currentScanRing > MAX_RING) {
            // 不再遍历 5445 个方块，仅随机抽样 15 次寻找溜达目标
            for (int i = 0; i < 15; i++) {
                int rx = maid.getRNG().nextInt(33) - 16;
                int ry = maid.getRNG().nextInt(5) - 2;
                int rz = maid.getRNG().nextInt(33) - 16;
                BlockPos pos = this.currentScanCenter.add(rx, ry, rz);
                
                if (!world.isBlockLoaded(pos)) continue;
                
                Block block = world.getBlockState(pos).getBlock();
                if (block == Blocks.FARMLAND || block instanceof BlockCrops || block == Blocks.REEDS) {
                    BlockPos target = pos.add(maid.getRNG().nextInt(5) - 2, 0, maid.getRNG().nextInt(5) - 2);
                    net.minecraft.pathfinding.Path path = maid.getNavigator().getPathToPos(target.up());
                    if (path != null) {
                        net.minecraft.pathfinding.PathPoint endPoint = path.getFinalPathPoint();
                        if (endPoint != null && target.distanceSq(endPoint.x, endPoint.y, endPoint.z) <= 9.0D) {
                            this.destinationBlock = target;
                            this.isPatrolling = true; 
                            maid.getNavigator().setPath(path, this.moveSpeed * 0.7D); 
                            return true;
                        }
                    }
                }
            }
        }
        
        // 这一帧没找到结果，返回 false 以让出 CPU，下一帧继续扫下一个环
        return false;
    }

    @Override
    public boolean shouldExecute() {
        if (!EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) || maid.isMaidWait() || maid.getCurrentEquippedItem().isEmpty()) {
            this.currentScanRing = 0; // 状态中断时重置雷达
            return false;
        }
        
        if (!maid.isFreedom() && maid.getOwner() instanceof EntityPlayer) {
            EntityPlayer owner = (EntityPlayer) maid.getOwner();
            boolean isMoving = checkOwnerMoving(owner);
            double distSq = maid.getDistanceSq(owner);
            
            if (distSq > 1024.0D) { this.currentScanRing = 0; return false; } 
            if (distSq > 64.0D && isMoving) { this.currentScanRing = 0; return false; } 
        }

        if (this.customScanDelay > 0) {
            this.customScanDelay--;
            return false;
        }

        // 分帧扫描
        boolean found = searchNextTargetSpliced();
        if (found) {
            this.currentScanRing = 0; // 找到目标，重置雷达
            return true;
        } else {
            // 如果雷达扫满了最大圈还是没结果，说明真的没活干，进入 1 秒的彻底待机冷却
            if (this.currentScanRing > MAX_RING) {
                this.currentScanRing = 0;
                this.customScanDelay = 20; 
            }
            return false;
        }
    }

    @Override
    public void startExecuting() {
        this.actionCompleted = false; 
        this.actionCooldown = 0; 
        this.lastPosX = maid.posX;
        this.lastPosY = maid.posY;
        this.lastPosZ = maid.posZ;
        this.checkStuckTimer = 0;
        this.realStuckCount = 0;
        this.isPatrolling = false; 
        this.ignoreColleagues = false; 
        
        EntityPlayer owner = null;
        if (maid.getOwner() instanceof EntityPlayer) {
            owner = (EntityPlayer) maid.getOwner();
        }
        if (owner != null) {
            this.lastOwnerX = owner.posX;
            this.lastOwnerY = owner.posY;
            this.lastOwnerZ = owner.posZ;
        }
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (actionCompleted || !EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) || maid.isMaidWait()) {
            return false;
        }
        
        if (!maid.isFreedom() && maid.getOwner() instanceof EntityPlayer) {
            EntityPlayer owner = (EntityPlayer) maid.getOwner();
            boolean isMoving = checkOwnerMoving(owner);
            double distSq = maid.getDistanceSq(owner);
            
            if (distSq > 1024.0D) return false;
            if (distSq > 64.0D && isMoving) return false;
        }
        
        if (this.actionCooldown > 0) return true;
        if (this.isPatrolling) return true; 
        
        return this.shouldMoveTo(maid.getEntityWorld(), this.destinationBlock);
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
        if (this.isPatrolling && (maid.getEntityWorld().getTotalWorldTime() + maid.getEntityId()) % 20 == 0) {
            BlockPos center = new BlockPos(maid);
            World world = maid.getEntityWorld();
            boolean foundWork = false;
            
            // 将闲逛时的余光雷达从半径 16 缩减为 5
            for (int x = -5; x <= 5; x++) {
                for (int y = -2; y <= 2; y++) {
                    for (int z = -5; z <= 5; z++) {
                        BlockPos pos = center.add(x, y, z);
                        
                        if (!world.isBlockLoaded(pos)) continue;
                        
                        IBlockState state = world.getBlockState(pos);
                        Block block = state.getBlock();
                        
                        boolean isHarvestable = (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) || 
                                                (block == Blocks.REEDS && world.getBlockState(pos.down()).getBlock() == Blocks.REEDS);
                                                
                        if (isHarvestable) {
                            if (this.blacklistedTarget == null || !this.blacklistedTarget.equals(pos) || world.getTotalWorldTime() >= this.blacklistEndTime) {
                                foundWork = true;
                                break;
                            }
                        }
                    }
                    if (foundWork) break;
                }
                if (foundWork) break;
            }
            
            if (foundWork) {
                this.isPatrolling = false;
                this.actionCompleted = true; 
                maid.getNavigator().clearPath(); 
                this.actionCooldown = 0; 
                this.ignoreColleagues = false; 
                return; 
            }
        }

        if (this.actionCooldown > 0) {
            this.actionCooldown--;
            if (this.actionCooldown == 0) {
                // 拔掉 updateTask 中的同步锁死扫描！
                // 结束当前任务，将控制权交还给 shouldExecute 引擎，由它用平滑的分帧雷达去干活
                this.currentScanRing = 0;
                this.actionCompleted = true; 
            }
            return; 
        }

        double myDistToTarget = maid.getDistanceSqToCenter(this.destinationBlock);

        // 干活避让 + 巡逻防扎堆 死锁解决系统
        if (!this.ignoreColleagues && (this.isPatrolling || myDistToTarget > 9.0D)) {
            java.util.List<EntityLittleMaid> nearbyMaids = maid.getEntityWorld().getEntitiesWithinAABB(
                EntityLittleMaid.class, 
                maid.getEntityBoundingBox().grow(2.0D, 1.0D, 2.0D)
            );
            
            for (EntityLittleMaid otherMaid : nearbyMaids) {
                if (otherMaid != maid && otherMaid.getMaidModeString().equals(EntityMode_Farmer.mmode_Farmer)) {
                    
                    if (this.isPatrolling) {
                        long currentTime = maid.getEntityWorld().getTotalWorldTime();
                        
                        if (currentTime < this.nextPatrolYieldTime) {
                            continue; 
                        }
                        
                        if (currentTime - this.lastPatrolYieldTime > 200) {
                            this.consecutivePatrolYields = 0;
                        }
                        
                        this.consecutivePatrolYields++;
                        this.lastPatrolYieldTime = currentTime;
                        
                        if (this.consecutivePatrolYields >= 3) {
                            this.nextPatrolYieldTime = currentTime + 600;
                            this.consecutivePatrolYields = 0;
                            continue; 
                        }

                        this.actionCompleted = true; 
                        maid.getNavigator().clearPath(); 
                        this.actionCooldown = 20 + maid.getRNG().nextInt(20); 
                        return; 
                    } else {
                        double otherDistToTarget = otherMaid.getDistanceSqToCenter(this.destinationBlock);
                        boolean shouldYield = (otherDistToTarget < myDistToTarget) || 
                                              (Math.abs(otherDistToTarget - myDistToTarget) < 0.1D && otherMaid.getEntityId() < maid.getEntityId());

                        if (shouldYield) {
                            this.blacklistedTarget = this.destinationBlock;
                            this.blacklistEndTime = maid.getEntityWorld().getTotalWorldTime() + 60;
                            
                            this.actionCompleted = true; 
                            maid.getNavigator().clearPath(); 
                            this.actionCooldown = 5; 
                            
                            this.ignoreColleagues = true; 
                            return; 
                        }
                    }
                }
            }
        }

        if (myDistToTarget > 16.0D) { 
            maid.getNavigator().setSpeed(1.2D); 
        } else {            
            maid.getNavigator().setSpeed(1.0D); 
        }
        
        if (myDistToTarget < 4.5D) {
            if (this.isPatrolling) {
                maid.getNavigator().clearPath();
                this.actionCooldown = 40 + maid.getRNG().nextInt(40);
            } else {
                executeAction();
                maid.getNavigator().clearPath();
                
                this.actionCooldown = 5 + maid.getRNG().nextInt(10);
                this.ignoreColleagues = false; 
            }
        } else {
            this.checkStuckTimer++;
            if (this.checkStuckTimer >= 20) { 
                double dX = maid.posX - this.lastPosX;
                double dZ = maid.posZ - this.lastPosZ;
                double movedHorizontalSq = (dX * dX) + (dZ * dZ);
                
                if (movedHorizontalSq < 0.05D) {
                    this.realStuckCount++;
                } else {
                    this.realStuckCount = 0;
                }
                
                this.lastPosX = maid.posX;
                this.lastPosY = maid.posY; 
                this.lastPosZ = maid.posZ;
                this.checkStuckTimer = 0;
                
                if (this.realStuckCount >= 3) {
                    boolean hasFence = false;
                    for (int fx = -1; fx <= 1; fx++) {
                        for (int fz = -1; fz <= 1; fz++) {
                            BlockPos checkPos = new BlockPos(maid).add(fx, 0, fz);
                            if (!maid.getEntityWorld().isBlockLoaded(checkPos)) continue;
                            
                            Block b = maid.getEntityWorld().getBlockState(checkPos).getBlock();
                            if (b instanceof net.minecraft.block.BlockFence || 
                                b instanceof net.minecraft.block.BlockWall || 
                                b instanceof net.minecraft.block.BlockFenceGate) {
                                hasFence = true; 
                                break;
                            }
                        }
                        if (hasFence) break;
                    }

                    if (hasFence) {
                        if (!this.isPatrolling) {
                            this.blacklistedTarget = this.destinationBlock;
                            this.blacklistEndTime = maid.getEntityWorld().getTotalWorldTime() + 200;
                            this.actionCompleted = true; 
                            this.customScanDelay = 20; 
                        }
                        maid.getNavigator().clearPath();
                        this.actionCooldown = 5;
                        this.ignoreColleagues = false; 
                    } else {
                        if (this.isPatrolling) {
                            maid.getNavigator().clearPath();
                            this.actionCooldown = 20;
                        } else {
                            executeAction(); 
                            maid.getNavigator().clearPath();
                            
                            this.actionCooldown = 5 + maid.getRNG().nextInt(10);
                            this.ignoreColleagues = false; 
                        }
                    }
                    return; 
                }
            }

            if (!this.isPatrolling) {
                this.maid.getLookHelper().setLookPosition(this.destinationBlock.getX() + 0.5D, this.destinationBlock.getY() + 1, this.destinationBlock.getZ() + 0.5D, 10.0F, this.maid.getVerticalFaceSpeed());
            }
            
            if (maid.getNavigator().noPath()) {
                maid.getNavigator().tryMoveToXYZ(this.destinationBlock.getX() + 0.5D, this.destinationBlock.getY() + 1, this.destinationBlock.getZ() + 0.5D, this.moveSpeed);
            }
        }
    }

    private void executeAction() {
        World world = maid.getEntityWorld();
        BlockPos pos = this.destinationBlock;
        
        if (!world.isBlockLoaded(pos)) return;
        
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        ItemStack curStack = maid.getCurrentEquippedItem();

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
        else if (isUnfarmedLand(world, pos)) {
            if (curStack.onItemUse(maid.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F) == EnumActionResult.SUCCESS) {
                maid.setSwing(10, EnumSound.FARMER_FARM, false);
                curStack.damageItem(1, maid.maidAvatar); 
            }
        }
    }

    private int getHadSeedIndex() {
        ItemStack firstSlot = maid.maidInventory.getStackInSlot(0);
        boolean isRandomMode = !firstSlot.isEmpty() && firstSlot.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.CHEST);

        if (isRandomMode) {
            List<Integer> availableSeedSlots = new ArrayList<>();
            for (int i = 1; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack pStack = maid.maidInventory.getStackInSlot(i);
                if (!pStack.isEmpty() && pStack.getItem() instanceof IPlantable) {
                    availableSeedSlots.add(i);
                }
            }
            if (!availableSeedSlots.isEmpty()) {
                return availableSeedSlots.get(maid.getRNG().nextInt(availableSeedSlots.size()));
            }
        } else {
            for (int i = 0; i < maid.maidInventory.getSizeInventory(); i++) {
                ItemStack pStack = maid.maidInventory.getStackInSlot(i);
                if (!pStack.isEmpty() && pStack.getItem() instanceof IPlantable) return i;
            }
        }
        return -1;
    }

    private boolean isBlockWatered(World world, BlockPos pos) {
        int radius = 4;
        for (BlockPos.MutableBlockPos mutablePos : BlockPos.getAllInBoxMutable(pos.add(-radius, 0, -radius), pos.add(radius, 1, radius))) {
            if (world.isBlockLoaded(mutablePos) && world.getBlockState(mutablePos).getMaterial() == Material.WATER) {
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
            BlockPos offsetPos = pos.offset(facing);
            if (world.isBlockLoaded(offsetPos) && world.getBlockState(offsetPos).getBlock() == Blocks.FARMLAND) {
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
