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
    
    // 【动作硬直冷却器】
    private int actionCooldown = 0;
    
    // 【物理防卡死系统变量】
    private double lastPosX, lastPosY, lastPosZ;
    private int checkStuckTimer = 0;
    private int realStuckCount = 0;
    
    //  【洗牌与避让系统状态】
    private boolean isEvading = false;

    // ====== 真实移动监测系统 ======
    private double lastOwnerX, lastOwnerY, lastOwnerZ;
    private boolean isOwnerMoving = false;
    private long lastCheckTime = 0;
    private int ownerStopTicks = 0;

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

    private boolean searchNextTarget() {
        List<BlockPos> validTargets = new ArrayList<>();
        BlockPos center = new BlockPos(maid);
        World world = maid.getEntityWorld();
        
        // 24格搜索半径，兼顾大农场与性能
        int range = 24; 
        
        EntityPlayer owner = null;
        if (maid.getOwner() instanceof EntityPlayer) {
            owner = (EntityPlayer) maid.getOwner();
        }

        boolean limitReached = false;
        for (int x = -range; x <= range; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = center.add(x, y, z);
                    
                    // 自由模式彻底无视主人距离限制
                    if (owner != null && !maid.isFreedom() && owner.getDistanceSqToCenter(pos) > 625.0D) {
                        continue; 
                    }
                    
                    if (this.shouldMoveTo(world, pos)) {
                        validTargets.add(pos);
                        // 【算力保险 A单次收集目标超过 30 个强制截断，防 CPU 爆炸
                        if (validTargets.size() >= 30) {
                            limitReached = true;
                            break;
                        }
                    }
                }
                if (limitReached) break;
            }
            if (limitReached) break;
        }

        if (!validTargets.isEmpty()) {
            // 如果刚刚谦让过或卡死过，随机抽选新农田
            if (this.isEvading) {
                java.util.Collections.shuffle(validTargets);
                this.isEvading = false; 
                System.out.println("[LMR-FARM-DEBUG] 触发洗牌：在收集到的 30 个目标中随机挑选新农田！");
            } else {
                validTargets.sort(Comparator.comparingDouble(p -> p.distanceSq(center)));
            }
            
            // 【算力保险 B】：控制 A* 寻路次数
            int pathfindAttempts = 0;
            
            for (BlockPos target : validTargets) {
                if (maid.getDistanceSqToCenter(target) < 4.5D) {
                    this.destinationBlock = target;
                    maid.getNavigator().tryMoveToXYZ(target.getX() + 0.5D, target.getY() + 1, target.getZ() + 0.5D, this.moveSpeed);
                    return true;
                }
                
                // 每次最多允许 2 次长距离复杂寻路，算不出来就强制休息！
                if (pathfindAttempts >= 2) {
                    System.out.println("[LMR-FARM-DEBUG]  寻路算力达到单 Tick 上限，下个回合再算...");
                    break;
                }
                pathfindAttempts++;
                
                // 【寻路死胡同预判系统】
                net.minecraft.pathfinding.Path path = maid.getNavigator().getPathToPos(target.up());
                if (path != null) {
                    net.minecraft.pathfinding.PathPoint endPoint = path.getFinalPathPoint();
                    if (endPoint != null) {
                        double pathErrorDist = target.distanceSq(endPoint.x, endPoint.y, endPoint.z);
                        if (pathErrorDist <= 4.0D) {
                            this.destinationBlock = target;
                            maid.getNavigator().setPath(path, this.moveSpeed);
                            return true;
                        } else {
                            System.out.println("[LMR-FARM-DEBUG] 预判死胡同！目标被阻挡，跳过: " + target);
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean shouldExecute() {
        if (!EntityMode_Farmer.mmode_Farmer.equals(maid.getMaidModeString()) || maid.isMaidWait() || maid.getCurrentEquippedItem().isEmpty()) {
            return false;
        }
        
        // 自由模式下，绝不因为主人的移动而罢工
        if (!maid.isFreedom() && maid.getOwner() instanceof EntityPlayer) {
            EntityPlayer owner = (EntityPlayer) maid.getOwner();
            boolean isMoving = checkOwnerMoving(owner);
            double distSq = maid.getDistanceSq(owner);
            
            if (distSq > 1024.0D) return false; 
            if (distSq > 64.0D && isMoving) return false; 
        }

        if (this.customScanDelay > 0) {
            this.customScanDelay--;
            return false;
        }

        if (searchNextTarget()) {
            return true;
        }

        this.customScanDelay = 20; 
        return false;
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
        this.isEvading = false;
        
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
        if (this.actionCooldown > 0) {
            this.actionCooldown--;
            if (this.actionCooldown == 0) {
                if (searchNextTarget()) {
                    this.realStuckCount = 0;
                    this.checkStuckTimer = 0;
                    this.lastPosX = maid.posX;
                    this.lastPosY = maid.posY;
                    this.lastPosZ = maid.posZ;
                } else {
                    this.actionCompleted = true;
                    this.customScanDelay = 20; 
                }
            }
            return; 
        }

        double myDistToTarget = maid.getDistanceSqToCenter(this.destinationBlock);

        // 只在距离目标大于 3 格 (9.0D) 时才触发礼仪，防止贴脸发呆！
        if (myDistToTarget > 9.0D) {
            java.util.List<EntityLittleMaid> nearbyMaids = maid.getEntityWorld().getEntitiesWithinAABB(
                EntityLittleMaid.class, 
                maid.getEntityBoundingBox().grow(2.0D, 1.0D, 2.0D) 
            );
            
            for (EntityLittleMaid otherMaid : nearbyMaids) {
                if (otherMaid != maid && otherMaid.getMaidModeString().equals(EntityMode_Farmer.mmode_Farmer)) {
                    double otherDistToTarget = otherMaid.getDistanceSqToCenter(this.destinationBlock);
                    
                    boolean shouldYield = (otherDistToTarget < myDistToTarget) || 
                                          (Math.abs(otherDistToTarget - myDistToTarget) < 0.1D && otherMaid.getEntityId() < maid.getEntityId());

                    if (shouldYield) {
                        // ⏱️ 极短的错位停顿 (3~5 Tick)，并且下次直接洗牌目标，告别死锁！
                        this.actionCompleted = true; 
                        maid.getNavigator().clearPath(); 
                        this.actionCooldown = 3 + maid.getRNG().nextInt(3); 
                        this.isEvading = true; 
                        return; 
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
            executeAction();
            maid.getNavigator().clearPath();
            this.actionCooldown = 12;
            
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
                
                // 【双重环境甄别防卡死系统】
                if (this.realStuckCount >= 3) {
                    boolean hasFence = false;
                    for (int fx = -1; fx <= 1; fx++) {
                        for (int fz = -1; fz <= 1; fz++) {
                            Block b = maid.getEntityWorld().getBlockState(new BlockPos(maid).add(fx, 0, fz)).getBlock();
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
                        // 【隔离区退避】旁边有栅栏，不强行长臂猿，果断换地块
                        System.out.println("[LMR-FARM-DEBUG] 确认被栅栏卡死！放弃隔空收割，准备洗牌远遁！");
                        this.actionCompleted = true; 
                        maid.getNavigator().clearPath();
                        this.customScanDelay = 40; 
                        this.actionCooldown = 5;
                        this.isEvading = true; 
                    } else {
                        // 【小土坡/普通卡死】没栅栏，保留长臂猿能力，强行隔空挥锄头
                        System.out.println("[LMR-FARM-DEBUG] 普通地形卡死，启动长臂猿模式强行收割！");
                        executeAction(); 
                        maid.getNavigator().clearPath();
                        this.actionCooldown = 12;
                    }
                    return; 
                }
            }

            this.maid.getLookHelper().setLookPosition(this.destinationBlock.getX() + 0.5D, this.destinationBlock.getY() + 1, this.destinationBlock.getZ() + 0.5D, 10.0F, this.maid.getVerticalFaceSpeed());
            
            if (maid.getNavigator().noPath()) {
                maid.getNavigator().tryMoveToXYZ(this.destinationBlock.getX() + 0.5D, this.destinationBlock.getY() + 1, this.destinationBlock.getZ() + 0.5D, this.moveSpeed);
            }
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
