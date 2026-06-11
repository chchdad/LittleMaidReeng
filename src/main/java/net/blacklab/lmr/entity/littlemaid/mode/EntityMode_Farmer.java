package net.blacklab.lmr.entity.littlemaid.mode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import firis.lmlib.api.constant.EnumSound;
import net.minecraft.world.World;
import net.minecraft.init.Items;
import net.minecraft.item.ItemDye;
import net.blacklab.lmr.achievements.AchievementsLMRE;
import net.blacklab.lmr.achievements.AchievementsLMRE.AC;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.trigger.ModeTriggerRegisterHelper;
import net.blacklab.lmr.inventory.InventoryLittleMaid;
import net.blacklab.lmr.util.helper.VectorUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.IPlantable;

/**
 * メイド農家。付近の農地に移動し耕作可能であれば耕す。
 * @author Verclene
 *
 */
public class EntityMode_Farmer extends EntityModeBase {

	public static final String mmode_Farmer		= "Farmer";
	public static final String mtrigger_Hoe		= "Farmer:Hoe";
	public static final String mtrigger_Seed 	= "Farmer:Seed";
	public static final int WATER_RADIUS = 4;
	private int clearCount = 0;
	private BlockPos perceivedTarget = null;
	private int actionCooldown = 0;


	public EntityMode_Farmer(EntityLittleMaid pEntity) {
		super(pEntity);
		// TODO 自動生成されたコンストラクター・スタブ
	}

	@Override
	public void init() {
		ModeTriggerRegisterHelper.register(mmode_Farmer, mtrigger_Hoe);
		//メイドモードをFarmerSeedで種を設定できるように想定
		ModeTriggerRegisterHelper.register(mmode_Farmer + "Seed", mtrigger_Seed);
	}

	@Override
	public int priority() {
		// TODO 自動生成されたメソッド・スタブ
		return 6300;
	}

	@Override
	public void addEntityMode(EntityAITasks pDefaultMove,
			EntityAITasks pDefaultTargeting) {
		// TODO 自動生成されたメソッド・スタブ
		EntityAITasks[] ltasks = new EntityAITasks[2];
		ltasks[0] = pDefaultMove;
		ltasks[1] = pDefaultTargeting;

		owner.addMaidMode(mmode_Farmer, ltasks);
	}

	@Override
	public boolean changeMode(EntityPlayer pentityplayer) {
		ItemStack litemstack = owner.getHandSlotForModeChange();
		if (!litemstack.isEmpty()) {
			if (isTriggerItem(mmode_Farmer, litemstack)) {
				owner.setMaidMode(mmode_Farmer);
				//進捗
				AchievementsLMRE.grantAC(pentityplayer, AC.Farmer);
				return true;
			}
		}
		return false;
	}
	
	@Override
	protected boolean isTriggerItem(String pMode, ItemStack par1ItemStack) {
		if (par1ItemStack.isEmpty()) {
			return false;
		}
		switch (pMode) {
		case mmode_Farmer:
			return owner.getModeTrigger().isTriggerable(mtrigger_Hoe, par1ItemStack, ItemHoe.class);
		}
		return super.isTriggerItem(pMode, par1ItemStack);
	}
	
	/**
	 * 種アイテムのトリガー判断処理
	 * @param itemStack
	 * @return
	 */
	private boolean isTriggerItemSeed(ItemStack stack) {
		return owner.getModeTrigger().isTriggerable(mtrigger_Seed, stack, IPlantable.class);
	}

	@Override
	public boolean setMode(String pMode) {
		// TODO 自動生成されたメソッド・スタブ
		switch (pMode) {
		case mmode_Farmer :
			owner.setBloodsuck(false);
			owner.aiAttack.setEnable(false);
			owner.aiShooting.setEnable(false);
			return true;
		}

		return false;
	}

	@Override
	public int getNextEquipItem(String pMode) {
		int li;
		if ((li = super.getNextEquipItem(pMode)) >= 0) {
			return InventoryLittleMaid.handInventoryOffset;
		}

		ItemStack litemstack;

		// モードに応じた識別判定、速度優先
		switch (pMode) {
		case mmode_Farmer :
			for (li = 0; li < owner.maidInventory.getSizeInventory() - 1; li++) {
				litemstack = owner.maidInventory.getStackInSlot(li);
				if (litemstack.isEmpty()) continue;

				// クワ
				if (isTriggerItem(mmode_Farmer, litemstack)) {
					return li;
				}
			}
			break;
		}

		return -1;
	}

	@Override
	public boolean checkItemStack(ItemStack pItemStack) {
		if(pItemStack.isEmpty()) return false;
		return true;//UtilModeFarmer.isHoe(owner, pItemStack)||UtilModeFarmer.isSeed(pItemStack.getItem())||UtilModeFarmer.isCrop(pItemStack.getItem());
	}

	@Override
	public boolean isSearchBlock() {
		// 把寻路权还给底层护卫 AI，只要跑远了，她瞬间就能粘过来
		return false;
	}

	@Override
	public boolean shouldBlock(String pMode) {
		return !owner.getCurrentEquippedItem().isEmpty();
	}

	/**
	 * 一定時間後に範囲対象外とするブロックを管理する
	 */
	private checkBlockBlackListManager checkBlockManager = new checkBlockBlackListManager();
	
	@Override
	public boolean checkBlock(String pMode, int px, int py, int pz) {

		//処理対象外の場合は強制でfalse
		if (checkBlockManager.isBlackList(px, py, pz)) return false;

		boolean ret = checkBlockProc(pMode, px, py, pz);
		
		//処理対象外のカウントダウン
		if (ret) {
			checkBlockManager.setCountDown(px, py, pz);
		}
		
		return ret;
		
	}
	
	private boolean checkBlockProc(String pMode, int px, int py, int pz) {
		if (!super.checkBlock(pMode, px, py, pz)) return false;
		if(!VectorUtil.canMoveThrough(owner, 0.9D, px + 0.5D, py + 1.9D, pz + 0.5D, py==MathHelper.floor(owner.posY-1D), true, false)) return false;

		BlockPos pos = new BlockPos(px, py, pz);
		World world = owner.getEntityWorld();
		IBlockState state = world.getBlockState(pos);
		Block block = state.getBlock();

		// 1. 收甘蔗特判
		if (block == Blocks.REEDS && world.getBlockState(pos.down()).getBlock() == Blocks.REEDS) return true;

		// 2. 收割成熟庄稼特判
		if (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) return true;

		// 3. 骨粉催熟特判：有未成熟的庄稼，且背包里有骨粉
		if (block instanceof BlockCrops && !((BlockCrops) block).isMaxAge(state)) {
			for (int i = 0; i < owner.maidInventory.getSizeInventory(); i++) {
				ItemStack stack = owner.maidInventory.getStackInSlot(i);
				if (!stack.isEmpty() && stack.getItem() == Items.DYE && stack.getMetadata() == 15) return true;
			}
		}

		// 4. 空农田特判
		if (block == Blocks.FARMLAND) {
			int seedIndex = getHadSeedIndex();
			if (seedIndex != -1) {
				ItemStack seedStack = owner.maidInventory.getStackInSlot(seedIndex);
				if (isFarmedLand(px, py, pz, seedStack)) return true;
			}
		}

		// 5. 开垦荒地特判 (受副手模式限制)
		ItemStack offhandItem = owner.maidAvatar.getHeldItemOffhand();
		boolean isPeacefulFarmer = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT_SEEDS);
		if (!isPeacefulFarmer && isUnfarmedLand(px, py, pz)) return true;

		return false;
	}


	@Override
	public boolean executeBlock(String pMode, int px, int py, int pz) {
		boolean ret = false;
		ItemStack curStack = owner.getCurrentEquippedItem();
		
		if (pMode.equals(mmode_Farmer)) {
			if(owner.getAIMoveSpeed() > 0.5F) owner.setAIMoveSpeed(0.5F);
			if(owner.maidInventory.getFirstEmptyStack() < 0){
				owner.setMaidMode(EntityMode_Basic.mmode_FarmPorter);
			}
		}

		BlockPos pos = new BlockPos(px, py, pz);
		World world = owner.getEntityWorld();
		IBlockState state = world.getBlockState(pos);
		Block block = state.getBlock();

		ItemStack offhandItem = owner.maidAvatar.getHeldItemOffhand();
		boolean isPeacefulFarmer = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT_SEEDS);

		// 动作 A：收甘蔗 (免损)
		if (block == Blocks.REEDS && world.getBlockState(pos.down()).getBlock() == Blocks.REEDS) {
			world.destroyBlock(pos, true);
			owner.setSwing(10, EnumSound.FARMER_HARVEST, false);
			owner.addMaidExperience(4f);
			ret = true;
		}
		// 动作 B：收成熟庄稼 (免损)
		else if (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) {
			world.destroyBlock(pos, true);
			owner.setSwing(10, EnumSound.FARMER_HARVEST, false);
			owner.addMaidExperience(4f);
			ret = true;
		}
		// 动作 C：骨粉催熟
		else if (block instanceof BlockCrops && !((BlockCrops) block).isMaxAge(state)) {
			for (int i = 0; i < owner.maidInventory.getSizeInventory(); i++) {
				ItemStack stackInSlot = owner.maidInventory.getStackInSlot(i);
				if (!stackInSlot.isEmpty() && stackInSlot.getItem() == Items.DYE && stackInSlot.getMetadata() == 15) {
					if (ItemDye.applyBonemeal(stackInSlot, world, pos, owner.maidAvatar, EnumHand.MAIN_HAND)) {
						owner.setSwing(10, EnumSound.NULL, false);
						world.playEvent(2005, pos, 0); // 播放绿星粒子
						ret = true;
						break;
					}
				}
			}
		}
		// 动作 D：空农田播种 (免损)
		else if (block == Blocks.FARMLAND) {
			int seedIndex = getHadSeedIndex();
			if (seedIndex != -1) {
				ItemStack seedStack = owner.maidInventory.getStackInSlot(seedIndex);
				if (isFarmedLand(px, py, pz, seedStack)) {
					int svCurrentIdx = owner.getDataWatchCurrentItem();
					owner.maidInventory.setCurrentItemIndex(seedIndex);
					
					seedStack.onItemUse(owner.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F);
					
					owner.maidInventory.setCurrentItemIndex(svCurrentIdx);
					owner.setSwing(10, EnumSound.FARMER_PLANT, false);
					if (seedStack.getCount() <= 0) owner.maidInventory.setInventorySlotContents(seedIndex, ItemStack.EMPTY);
					ret = true;
				}
			}
		}
		// 动作 E：开垦荒地 (受副手限制，唯一扣耐久的地方)
		else if (!isPeacefulFarmer && isUnfarmedLand(px, py, pz)) {
			if (curStack.onItemUse(owner.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F) == EnumActionResult.SUCCESS) {
				owner.setSwing(10, EnumSound.FARMER_FARM, false);
				curStack.damageItem(1, owner.maidAvatar); // 精准扣除耐久
				if (curStack.getCount() <= 0) {
					owner.maidInventory.setInventoryCurrentSlotContents(ItemStack.EMPTY);
					owner.getNextEquipItem();
				}
				ret = true;
			}
		}

		if (ret) {
			this.checkBlockManager.clearPos(px, py, pz);
			return true;
		}
		return false;
	}


	@Override
	public void onUpdate(String pMode) {
		// TODO 自動生成されたメソッド・スタブ
		if(pMode.equals(mmode_Farmer) && ++clearCount >= 300 && owner.getNavigator().noPath()){
			try{
//				if(!owner.isWorking()){
				if(!owner.jobController.isWorking()){
					if(owner.aiCollectItem.shouldExecute()) owner.aiCollectItem.updateTask();
				}
			}catch(NullPointerException e){}
			clearCount=0;
		}
		//一定時間ごとにリセット
		if (pMode.equals(mmode_Farmer)) {
			this.checkBlockManager.reset();
		}
		//運びモードへの切り替え判定
		if (pMode.equals(mmode_Farmer) && owner.ticksExisted % 200 == 0) {
			if(owner.getAIMoveSpeed() > 0.5F) owner.setAIMoveSpeed(0.5F);
			if(owner.maidInventory.getFirstEmptyStack() < 0){
				owner.setMaidMode(EntityMode_Basic.mmode_FarmPorter);
			}
		}
	}

	@Override
	public void updateAITick(String pMode) {
		if (!pMode.equals(mmode_Farmer)) return;

		// 1. 防走丢强制打断机制：如果距离主人超过 12 格 (平方144)，立刻清空感知，让底层 AI 追人
		net.minecraft.entity.EntityLivingBase master = owner.getOwner();
		if (master != null && owner.getDistanceSq(master) > 144.0D) {
			perceivedTarget = null;
			return; 
		}

		// 冷却控制，防止动作鬼畜
		if (actionCooldown > 0) {
			actionCooldown--;
			return;
		}

		World world = owner.getEntityWorld();

		// 2. 【展开感知域】：每 10 Tick 释放一次雷达，直接获取最近目标
		if (perceivedTarget == null && owner.ticksExisted % 10 == 0) {
			List<BlockPos> radar = new ArrayList<>();
			ItemStack offhandItem = owner.maidAvatar.getHeldItemOffhand();
			boolean isPeacefulFarmer = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT_SEEDS);
			int range = 16;

			// 瞬间感知周围 16x16 范围内的一切
			for (int x = -range; x <= range; x++) {
				for (int y = -2; y <= 2; y++) {
					for (int z = -range; z <= range; z++) {
						BlockPos targetPos = new BlockPos(owner.posX + x, owner.posY + y, owner.posZ + z);
						IBlockState state = world.getBlockState(targetPos);
						Block block = state.getBlock();

						// 提取符合条件的方块
						if (block == Blocks.REEDS && world.getBlockState(targetPos.down()).getBlock() == Blocks.REEDS) {
							radar.add(targetPos);
						} else if (block instanceof BlockCrops) {
							if (((BlockCrops) block).isMaxAge(state)) {
								radar.add(targetPos); // 成熟了，感知到
							} else {
								// 未成熟的话，只有包里有骨粉才感知它，否则无视
								for (int i = 0; i < owner.maidInventory.getSizeInventory(); i++) {
									ItemStack stack = owner.maidInventory.getStackInSlot(i);
									if (!stack.isEmpty() && stack.getItem() == Items.DYE && stack.getMetadata() == 15) {
										radar.add(targetPos); break;
									}
								}
							}
						} else if (block == Blocks.FARMLAND) {
							int seedIndex = getHadSeedIndex();
							if (seedIndex != -1 && isFarmedLand(targetPos.getX(), targetPos.getY(), targetPos.getZ(), owner.maidInventory.getStackInSlot(seedIndex))) {
								radar.add(targetPos); // 空地能种，感知到
							}
						} else if (!isPeacefulFarmer && isUnfarmedLand(targetPos.getX(), targetPos.getY(), targetPos.getZ())) {
							radar.add(targetPos); // 荒地能开垦，感知到
						}
					}
				}
			}

			// 永远锁定感知域内离脚下最近的那个
			if (!radar.isEmpty()) {
				radar.sort(Comparator.comparingDouble(p -> p.distanceSq(owner.posX, owner.posY, owner.posZ)));
				perceivedTarget = radar.get(0);
			}
		}

		// 3. 【行动阶段】：走向感知到的目标并执行
		if (perceivedTarget != null) {
			double dist = owner.getDistanceSqToCenter(perceivedTarget);

			if (dist > 4.5D) {
				// 距离大于2格，命令寻路系统走过去
				owner.getNavigator().tryMoveToXYZ(perceivedTarget.getX(), perceivedTarget.getY(), perceivedTarget.getZ(), 0.6D);
				
				// 如果被障碍物卡住，果断放弃这个目标，下一次重新感知
				if (owner.getNavigator().noPath() && dist > 5.0D) {
					perceivedTarget = null;
					actionCooldown = 20; 
				}
			} else {
				// 距离够近，立刻停下并执行手部操作！
				owner.getNavigator().clearPath();
				executePerceivedAction(world, perceivedTarget); // 调用下面的新方法
				perceivedTarget = null; // 目标处理完毕，清空脑海
				actionCooldown = 10; // 休息半秒，模拟干活后摇
			}
		}
	}


	protected int getHadSeedIndex(){
		for (int i=0; i < owner.maidInventory.getSizeInventory(); i++) {
			ItemStack pStack;
			if (!(pStack = owner.maidInventory.getStackInSlot(i)).isEmpty() &&
					this.isTriggerItemSeed(pStack)) {
				return i;
			}
		}
		return -1;
	}
		private void executePerceivedAction(World world, BlockPos pos) {
		IBlockState state = world.getBlockState(pos);
		Block block = state.getBlock();
		ItemStack curStack = owner.getCurrentEquippedItem();
		ItemStack offhandItem = owner.maidAvatar.getHeldItemOffhand();
		boolean isPeacefulFarmer = (!offhandItem.isEmpty() && offhandItem.getItem() == Items.WHEAT_SEEDS);

		// 动作 A：收甘蔗
		if (block == Blocks.REEDS && world.getBlockState(pos.down()).getBlock() == Blocks.REEDS) {
			world.destroyBlock(pos, true);
			owner.setSwing(10, EnumSound.FARMER_HARVEST, false);
			owner.addMaidExperience(4f);
			return;
		}
		
		// 动作 B：收成熟庄稼
		if (block instanceof BlockCrops && ((BlockCrops) block).isMaxAge(state)) {
			world.destroyBlock(pos, true);
			owner.setSwing(10, EnumSound.FARMER_HARVEST, false);
			owner.addMaidExperience(4f);
			return;
		}

		// 动作 C：骨粉催熟
		if (block instanceof BlockCrops && !((BlockCrops) block).isMaxAge(state)) {
			for (int i = 0; i < owner.maidInventory.getSizeInventory(); i++) {
				ItemStack stackInSlot = owner.maidInventory.getStackInSlot(i);
				if (!stackInSlot.isEmpty() && stackInSlot.getItem() == Items.DYE && stackInSlot.getMetadata() == 15) {
					if (ItemDye.applyBonemeal(stackInSlot, world, pos, owner.maidAvatar, EnumHand.MAIN_HAND)) {
						owner.setSwing(10, EnumSound.NULL, false);
						world.playEvent(2005, pos, 0); 
						return;
					}
				}
			}
		}

		// 动作 D：空农田播种
		if (block == Blocks.FARMLAND) {
			int seedIndex = getHadSeedIndex();
			if (seedIndex != -1) {
				ItemStack seedStack = owner.maidInventory.getStackInSlot(seedIndex);
				if (isFarmedLand(pos.getX(), pos.getY(), pos.getZ(), seedStack)) { // 修复了种地判定Bug
					int svCurrentIdx = owner.getDataWatchCurrentItem();
					owner.maidInventory.setCurrentItemIndex(seedIndex);
					
					seedStack.onItemUse(owner.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F);
					
					owner.maidInventory.setCurrentItemIndex(svCurrentIdx);
					owner.setSwing(10, EnumSound.FARMER_PLANT, false);
					if (seedStack.getCount() <= 0) owner.maidInventory.setInventorySlotContents(seedIndex, ItemStack.EMPTY);
					return;
				}
			}
		}

		// 动作 E：开垦荒地
		if (!isPeacefulFarmer && isUnfarmedLand(pos.getX(), pos.getY(), pos.getZ())) {
			if (curStack.onItemUse(owner.maidAvatar, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5F, 1.0F, 0.5F) == EnumActionResult.SUCCESS) {
				owner.setSwing(10, EnumSound.FARMER_FARM, false);
				curStack.damageItem(1, owner.maidAvatar); 
				if (curStack.getCount() <= 0) {
					owner.maidInventory.setInventoryCurrentSlotContents(ItemStack.EMPTY);
					owner.getNextEquipItem();
				}
			}
		}
	}


	// 替换原来的 isUnfarmedLand (防卡死与野外乱开垦)
	protected boolean isUnfarmedLand(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		World world = owner.getEntityWorld();
		Block block = world.getBlockState(pos).getBlock();
		
		// 必须是草方块或泥土
		if (block != Blocks.GRASS && block != Blocks.DIRT) {
			return false;
		}
		
		// 【修复关键点】如果要开垦这块地，它的正上方必须是空气！防止对着障碍物死磕。
		if (!world.isAirBlock(pos.up())) {
			return false;
		}

		// 严格的主权校验：四周必须有现成的农田才允许开垦
		for (EnumFacing facing : EnumFacing.HORIZONTALS) {
			if (world.getBlockState(pos.offset(facing)).getBlock() == Blocks.FARMLAND) {
				return true;
			}
		}
		return false;
	}


		// 替换原来的 isFarmedLand (修复种地判定)
	protected boolean isFarmedLand(int x, int y, int z, ItemStack seedStack) {
		if (seedStack.isEmpty() || !(seedStack.getItem() instanceof IPlantable)) return false;
		BlockPos pos = new BlockPos(x, y, z);
		World world = owner.getEntityWorld();
		
		// 【修复关键点】直接获取当前的方块(耕地)，而不是 pos.down()
		IBlockState soil = world.getBlockState(pos); 
		IPlantable seed = (IPlantable) seedStack.getItem();
		
		// 问游戏引擎：这块耕地(soil)能种这颗种子吗？
		return soil.getBlock().canSustainPlant(soil, world, pos, EnumFacing.UP, seed);
	}


	protected boolean isCropGrown(int x, int y, int z){
		BlockPos position = new BlockPos(x, y, z);
		IBlockState state = owner.getEntityWorld().getBlockState(position);
		Block block = state.getBlock();

		if(block instanceof BlockCrops){
			// Max age -> Cannot glow(#34)
			return !((BlockCrops)block).canGrow(owner.getEntityWorld(), position, state, owner.getEntityWorld().isRemote);
		}
		return false;
	}

	@SuppressWarnings("rawtypes")
	protected boolean isBlockWatered(int x, int y, int z){
		// 雨天時は検索範囲を制限
		//boolean flag = owner.getEntityWorld().isRaining();
		BlockPos pos = new BlockPos(x,y,z);
		Iterator iterator = BlockPos.getAllInBoxMutable(pos.add(-WATER_RADIUS, 0, -WATER_RADIUS),
				pos.add(WATER_RADIUS, 1, WATER_RADIUS)).iterator();
		BlockPos.MutableBlockPos mutableblockpos;

		//IBlockState iState;
		do
		{
			if (!iterator.hasNext())
			{
				return false;
			}

			mutableblockpos = (BlockPos.MutableBlockPos)iterator.next();
		}
		//while ((iState = owner.getEntityWorld().getBlockState(mutableblockpos)).getMaterial() != Material.WATER);
		while ((owner.getEntityWorld().getBlockState(mutableblockpos)).getMaterial() != Material.WATER);

		return true;
	}
	
	
	/**
	 * ブロック操作系の職業メイドさんでブラックリストブロックを管理する
	 */
	public static class checkBlockBlackListManager {
		
		//作業対象外とするまでの時間（Tickではないみたい）
		private final int graceTime = 40;
		
		//作業対象外座標のリセット時間(600秒)
		private final int allResetTimeTick = 12000;
		
		/**
		 * 一定時間にリセットする
		 */
		private Map<BlockPos, Integer> checkBlockBlackList = new HashMap<>();
		
		/**
		 * ブラックリストをリセットタイマー
		 */
		private int resetCountTimer = allResetTimeTick;
		
		/**
		 * 対象の座標が処理対象外か確認する
		 */
		public boolean isBlackList(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			if (checkBlockBlackList.containsKey(pos)) {
				Integer count = checkBlockBlackList.get(pos);
				if (count <= 0) {
					return true;
				}
			}
			return false;
		}
		
		/**
		 * ブラックリスト用のカウントダウン
		 * 0になるとisBlackListがtrueになる
		 */
		public void setCountDown(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			if (!checkBlockBlackList.containsKey(pos)) {
				checkBlockBlackList.put(pos, graceTime);
			}
			if (checkBlockBlackList.containsKey(pos)) {
				Integer count = checkBlockBlackList.get(pos);
				count--;
				checkBlockBlackList.put(pos, count);
				if (count <= 0) {
					checkBlockBlackList.put(pos, 0);
				}
			}
		}
		
		/**
		 * 実行した場合に一旦クリアする
		 */
		public void clearPos(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			if (checkBlockBlackList.containsKey(pos)) {
				checkBlockBlackList.remove(pos);
			}
		}
		
		/**
		 * 初期化する
		 */
		public void reset() {
			this.resetCountTimer--;
			if (0 >= resetCountTimer) {
				checkBlockBlackList.clear();
				resetCountTimer = allResetTimeTick;
			}
		}
	}
	
	@Override
	public boolean isCancelPutChestItemStack(String pMode, ItemStack stack, int slotIndedx) {
		
		String mode = pMode;
		if (EntityMode_Basic.mmode_FarmPorter.equals(pMode)) {
			mode = mmode_Farmer;
		}
		
		//農家メイドさんの場合の判定
		if (mmode_Farmer.equals(mode)) {
			
			//13番目のスロットまで
			//種はのこす
			if(slotIndedx < 9 && this.isTriggerItemSeed(stack)) {
				return true;
			}
		}
		
		return this.isTriggerItem(mode, stack);
	}
}
