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
	public void addEntityMode(EntityAITasks pDefaultMove, EntityAITasks pDefaultTargeting) {
		EntityAITasks[] ltasks = new EntityAITasks[2];
		
		// 像加模组一样，把我们的车万高级 AI 注入到底层移动容器中，优先级设为 5
		pDefaultMove.addTask(5, new net.blacklab.lmr.entity.littlemaid.ai.EntityAILMRFarmer(owner, 0.6D));
		
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

		public static class checkBlockBlackListManager {
		private final int graceTime = 40;
		private final int allResetTimeTick = 12000;
		private Map<BlockPos, Integer> checkBlockBlackList = new HashMap<>();
		private int resetCountTimer = allResetTimeTick;
		
		public boolean isBlackList(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			if (checkBlockBlackList.containsKey(pos)) {
				Integer count = checkBlockBlackList.get(pos);
				if (count <= 0) return true;
			}
			return false;
		}
		
		public void setCountDown(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			if (!checkBlockBlackList.containsKey(pos)) {
				checkBlockBlackList.put(pos, graceTime);
			}
			if (checkBlockBlackList.containsKey(pos)) {
				Integer count = checkBlockBlackList.get(pos);
				count--;
				checkBlockBlackList.put(pos, count);
				if (count <= 0) checkBlockBlackList.put(pos, 0);
			}
		}
		
		public void clearPos(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			if (checkBlockBlackList.containsKey(pos)) checkBlockBlackList.remove(pos);
		}
		
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
