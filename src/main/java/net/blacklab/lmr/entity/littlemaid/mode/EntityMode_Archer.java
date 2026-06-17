package net.blacklab.lmr.entity.littlemaid.mode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.blacklab.lmr.achievements.AchievementsLMRE;
import net.blacklab.lmr.achievements.AchievementsLMRE.AC;
import net.blacklab.lmr.config.LMRConfig;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.target.EntityAILMHurtByTarget;
import net.blacklab.lmr.entity.littlemaid.ai.target.EntityAILMNearestAttackableTarget;
import net.blacklab.lmr.entity.littlemaid.trigger.ModeTrigger;
import net.blacklab.lmr.entity.littlemaid.trigger.ModeTriggerRegisterHelper;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArrow;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFlintAndSteel;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import net.minecraft.entity.ai.EntityAIOwnerHurtByTarget;
import net.minecraft.entity.ai.EntityAIOwnerHurtTarget;
import net.minecraft.entity.monster.IMob;

public class EntityMode_Archer extends EntityModeBase {

	public static final String mmode_Archer			= "Archer";
	public static final String mmode_Blazingstar	= "Blazingstar";
	
	public static final String mtrigger_Bow			= "Archer:Bow";
	public static final String mtrigger_Arrow		= "Archer:Arrow";
	
	public static final List<Class <? extends Item>> arrowClassList = initArrowClassList();
	
	public static List<Class <? extends Item>> initArrowClassList() {
		List<Class <? extends Item>> list = new ArrayList<>();
		list.add(ItemArrow.class);
		return list;
	}

	@Override
	public int priority() {
		return 3200;
	}

	public EntityMode_Archer(EntityLittleMaid pEntity) {
		super(pEntity);
		isAnytimeUpdate = true;
	}

	@Override
	public void init() {
		ModeTriggerRegisterHelper.register(mmode_Archer, mtrigger_Bow);
		ModeTrigger.registerTrigger(mtrigger_Arrow, new HashMap<>());
	}

	@Override
	public void addEntityMode(EntityAITasks pDefaultMove, EntityAITasks pDefaultTargeting) {
		EntityAITasks[] ltasks = new EntityAITasks[2];
		ltasks[0] = pDefaultMove;
		ltasks[1] = new EntityAITasks(owner.aiProfiler);

		ltasks[1].addTask(1, new EntityAIOwnerHurtByTarget(owner) {
			@Override
			public boolean shouldExecute() {
				if (!super.shouldExecute() || owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase target = owner.getMaidMasterEntity().getRevengeTarget();
				return target != null && !owner.getIFF(target) && (target instanceof IMob);
			}
		});
		
		// 带有双重阈值评估的集火 AI (射手同样适用)
		ltasks[1].addTask(2, new EntityAIOwnerHurtTarget(owner) {
			private EntityLivingBase currentTarget = null;
			private int hitCount = 0;
			private int lastAttackTick = 0;

			@Override
			public boolean shouldExecute() {
				if (!super.shouldExecute() || owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase target = owner.getMaidMasterEntity().getLastAttackedEntity();
				
				if (target == null || owner.getIFF(target)) {
					currentTarget = null;
					return false;
				}

				if (target != currentTarget) {
					currentTarget = target;
					hitCount = 0;
					lastAttackTick = 0;
				}

				int currentTick = owner.getMaidMasterEntity().getLastAttackedEntityTime();
				if (currentTick != lastAttackTick) {
					hitCount++;
					lastAttackTick = currentTick;
				}

				float missingHealth = target.getMaxHealth() - target.getHealth();
				return missingHealth >= 10.0F || hitCount >= 6;
			}
		});

		ltasks[1].addTask(3, new EntityAILMHurtByTarget(owner, true));
		ltasks[1].addTask(4, new EntityAILMNearestAttackableTarget<EntityLivingBase>(owner, EntityLivingBase.class, 0, true));
		owner.addMaidMode(mmode_Archer, ltasks);


		EntityAITasks[] ltasks2 = new EntityAITasks[2];
		ltasks2[0] = pDefaultMove;
		ltasks2[1] = new EntityAITasks(owner.aiProfiler);

		ltasks2[1].addTask(1, new EntityAIOwnerHurtByTarget(owner) {
			@Override
			public boolean shouldExecute() {
				if (!super.shouldExecute() || owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase target = owner.getMaidMasterEntity().getRevengeTarget();
				return target != null && !owner.getIFF(target) && (target instanceof IMob);
			}
		});
		ltasks2[1].addTask(2, new EntityAIOwnerHurtTarget(owner) {
			private EntityLivingBase currentTarget = null;
			private int hitCount = 0;
			private int lastAttackTick = 0;

			@Override
			public boolean shouldExecute() {
				if (!super.shouldExecute() || owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase target = owner.getMaidMasterEntity().getLastAttackedEntity();
				
				if (target == null || owner.getIFF(target)) {
					currentTarget = null;
					return false;
				}

				if (target != currentTarget) {
					currentTarget = target;
					hitCount = 0;
					lastAttackTick = 0;
				}

				int currentTick = owner.getMaidMasterEntity().getLastAttackedEntityTime();
				if (currentTick != lastAttackTick) {
					hitCount++;
					lastAttackTick = currentTick;
				}

				float missingHealth = target.getMaxHealth() - target.getHealth();
				return missingHealth >= 10.0F || hitCount >= 6;
			}
		});

		ltasks[1].addTask(3, new EntityAILMHurtByTarget(owner, true));
		ltasks[1].addTask(4, new EntityAILMNearestAttackableTarget<EntityLivingBase>(owner, EntityLivingBase.class, 0, true));
		owner.addMaidMode(mmode_Blazingstar, ltasks2);
	}

	@Override
	public boolean changeMode(EntityPlayer pentityplayer) {
		ItemStack litemstack = owner.getHandSlotForModeChange();
		if (!litemstack.isEmpty()) {
			Item item = litemstack.getItem();
			if (owner.getModeTrigger().isTriggerable(mtrigger_Bow, item, item instanceof ItemBow)) {
				if (owner.maidInventory.getInventorySlotContainItem(ItemFlintAndSteel.class) > -1) {
					owner.setMaidMode(mmode_Blazingstar);
					AchievementsLMRE.grantAC(pentityplayer, AC.BlazingStar);
				} else {
					owner.setMaidMode(mmode_Archer);
					AchievementsLMRE.grantAC(pentityplayer, AC.Archer);
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean setMode(String pMode) {
		switch (pMode) {
		case mmode_Archer :
			owner.aiAttack.setEnable(true);
			owner.aiShooting.setEnable(true);
			owner.setBloodsuck(false);
			return true;
		case mmode_Blazingstar :
			owner.aiAttack.setEnable(false);
			owner.aiShooting.setEnable(true);
			owner.setBloodsuck(true);
			return true;
		}
		return false;
	}

	@Override
	public int getNextEquipItem(String pMode) {
		int li;
		if ((li = super.getNextEquipItem(pMode)) >= 0) return li;
		ItemStack litemstack;
		switch (pMode) {
		case mmode_Archer :
		case mmode_Blazingstar :
			for (li = 0; li < owner.maidInventory.getSizeInventory() - 1; li++) {
				litemstack = owner.maidInventory.getStackInSlot(li);
				if (litemstack.isEmpty()) continue;
				if (isTriggerItem(pMode, litemstack)) {
					return li;
				}
			}
			break;
		}
		return -1;
	}

	@Override
	protected boolean isTriggerItem(String pMode, ItemStack par1ItemStack) {
		if (par1ItemStack.isEmpty()) return false;
		Item item = par1ItemStack.getItem();
		return owner.getModeTrigger().isTriggerable(mtrigger_Bow, item, item instanceof ItemBow);
	}

	@Override
	public boolean checkItemStack(ItemStack pItemStack) {
		if (pItemStack.isEmpty()) return false;
		Item item = pItemStack.getItem();
		return owner.getModeTrigger().isTriggerable(mtrigger_Bow, item, item instanceof ItemBow);
	}

	@Override
	public boolean isSearchEntity() {
		return true;
	}

	@Override
	public boolean checkEntity(String pMode, Entity pEntity) {
		if (!isInventoryArrowItem()) return false;
		if (!MaidHelper.isTargetReachable(owner, pEntity, 18 * 18)) return false;
		return !owner.getIFF(pEntity);
	}

	@Override
	public void onUpdate(String pMode) {
		switch (pMode) {
		case mmode_Archer:
		case mmode_Blazingstar:
			this.getWeaponStatus();
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void updateAITick(String pMode) {
		if (!isInventoryArrowItem()) {
			owner.setAttackTarget(null);
		}
		switch (pMode) {
		case mmode_Archer:
			break;
		case mmode_Blazingstar:
			World lworld = owner.getEntityWorld();
			List<Entity> llist = lworld.getEntitiesWithinAABB(Entity.class, owner.getEntityBoundingBox().grow(16D, 16D, 16D));
			for (int li = 0; li < llist.size(); li++) {
				Entity lentity = llist.get(li);
				if (lentity.isEntityAlive() && lentity.isBurning() && owner.getRNG().nextFloat() > 0.9F) {
					int lx = (int)owner.posX;
					int ly = (int)owner.posY;
					int lz = (int)owner.posZ;
					IBlockState iState;
					if (lworld.isAirBlock(new BlockPos(lx, ly, lz)) || (iState = lworld.getBlockState(new BlockPos(lx, ly, lz))).getBlock().getMaterial(iState).getCanBurn()) {
						lworld.playSound(lx + 0.5D, ly + 0.5D, lz + 0.5D, SoundEvent.REGISTRY.getObject(new ResourceLocation("item.firecharge.use")), SoundCategory.NEUTRAL, 1.0F, owner.getRNG().nextFloat() * 0.4F + 0.8F, false);
						lworld.setBlockState(new BlockPos(lx, ly, lz), Blocks.FIRE.getDefaultState());
					}
				}
			}
		}
	}

	@Override
	public double getDistanceToSearchTargets() {
		return 24d;
	}

	@Override
	public double getLimitRangeSqOnFollow() {
		return 16 * 16;
	}

	@Override
	public double getFreedomTrackingRangeSq() {
		return 21 * 21;
	}
	
	private boolean isInventoryArrowItem() {
		for (Class<? extends Item> classItem : arrowClassList) {
			if (!(owner.maidInventory.getInventorySlotContainItem(classItem) < 0)) return true;
		}
		for (int j = 0; j < owner.maidInventory.getSizeInventory(); j++) {
			if (LMRConfig.isCfgArrowItemStack(owner.maidInventory.getStackInSlot(j))) return true;
		}
		return false;
	}
	
	@Override
	public boolean attackEntityAsMob(String pMode, Entity targetEntity) {
		if (!mmode_Archer.equals(pMode)) return false;
		float knockBackLevel = 2.5F;
		if (targetEntity instanceof EntityLivingBase) {
            ((EntityLivingBase)targetEntity).knockBack(
            		this.owner, 
            		(float)knockBackLevel * 0.5F, 
            		(double)MathHelper.sin(this.owner.rotationYaw * 0.017453292F), 
            		(double)(-MathHelper.cos(this.owner.rotationYaw * 0.017453292F)));
        } else {
            targetEntity.addVelocity(
            		(double)(-MathHelper.sin(this.owner.rotationYaw * 0.017453292F) * (float)knockBackLevel * 0.5F), 
            		0.1D, 
            		(double)(MathHelper.cos(this.owner.rotationYaw * 0.017453292F) * (float)knockBackLevel * 0.5F));
        }
		this.owner.playSound("entity.player.attack.knockback");
		this.owner.setAttackTarget(null);
		return true;
	}
	
	public boolean weaponFullAuto;
	public boolean weaponReload;

	public void getWeaponStatus() {
		weaponFullAuto = false;
		weaponReload = false;
		ItemStack is = this.owner.maidInventory.getCurrentItem();
		if (is.isEmpty()) return;
		try {
			Method me = is.getItem().getClass().getMethod("isWeaponReload", ItemStack.class, EntityPlayer.class);
			weaponReload = (Boolean)me.invoke(is.getItem(), is, this.owner.maidAvatar);
		}catch (Exception e) {}
		try {
			Method me = is.getItem().getClass().getMethod("isWeaponFullAuto", ItemStack.class);
			weaponFullAuto = (Boolean)me.invoke(is.getItem(), is);
		}catch (Exception e) {}
	}
}
