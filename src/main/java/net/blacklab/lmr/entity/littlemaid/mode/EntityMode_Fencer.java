package net.blacklab.lmr.entity.littlemaid.mode;

import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.MathHelper;
import net.blacklab.lmr.LittleMaidReengaged;
import net.blacklab.lmr.achievements.AchievementsLMRE;
import net.blacklab.lmr.achievements.AchievementsLMRE.AC;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.target.EntityAILMHurtByTarget;
import net.blacklab.lmr.entity.littlemaid.ai.target.EntityAILMNearestAttackableTarget;
import net.blacklab.lmr.entity.littlemaid.trigger.ModeTriggerRegisterHelper;
import net.blacklab.lmr.inventory.InventoryLittleMaid;
import net.blacklab.lmr.util.Counter;
import net.blacklab.lmr.util.helper.CommonHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;

import net.minecraft.entity.ai.EntityAIOwnerHurtByTarget;
import net.minecraft.entity.ai.EntityAIOwnerHurtTarget;
import net.minecraft.entity.monster.IMob;

public class EntityMode_Fencer extends EntityModeBase {

	public static final String mmode_Fencer			= "Fencer";
	public static final String mmode_Bloodsucker	= "Bloodsucker";

	public static final String mtrigger_Sword 	= "Fencer:Sword";
	public static final String mtrigger_Axe = "Bloodsucker:Axe";
	
	protected Counter ticksCharge;
	protected static final UUID CHARGING_BOOST_UUID = UUID.nameUUIDFromBytes(LittleMaidReengaged.MODID.concat(":fencer_charge_boost").getBytes());
	protected static final AttributeModifier CHARGING_BOOST_MODIFIER = new AttributeModifier(CHARGING_BOOST_UUID, LittleMaidReengaged.MODID.concat(":fencer_charge_boost"), 0.2d, 0);

	protected static final int CHARGE_COUNTER_MAX_VALUE = 60;
	public EntityMode_Fencer(EntityLittleMaid pEntity) {
		super(pEntity);
		isAnytimeUpdate = true;
		ticksCharge = new Counter(-20*30, CHARGE_COUNTER_MAX_VALUE, -20*30);
	}

	@Override
	public int priority() {
		return 3000;
	}

	@Override
	public void init() {
		ModeTriggerRegisterHelper.register(mmode_Fencer, mtrigger_Sword);
		ModeTriggerRegisterHelper.register(mmode_Bloodsucker, mtrigger_Axe);
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

		//  带有攻击次数与伤害双阈值评估的集火 AI
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

				// 如果换了攻击目标，计数器清零重新评估
				if (target != currentTarget) {
					currentTarget = target;
					hitCount = 0;
					lastAttackTick = 0;
				}

				// 捕捉实际攻击动作，防止每 tick 循环重复计次
				int currentTick = owner.getMaidMasterEntity().getLastAttackedEntityTime();
				if (currentTick != lastAttackTick) {
					hitCount++;
					lastAttackTick = currentTick;
				}

				float missingHealth = target.getMaxHealth() - target.getHealth();
				
				// 伤害达 10 点，或者虽然没到 10 点但是敲了 6 次以上
				return missingHealth >= 10.0F || hitCount >= 6;
			}
		});

		ltasks[1].addTask(3, new EntityAILMHurtByTarget(owner, true));
		ltasks[1].addTask(4, new EntityAILMNearestAttackableTarget<EntityLivingBase>(owner, EntityLivingBase.class, 0, true));
		owner.addMaidMode(mmode_Fencer, ltasks);


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

		ltasks2[1].addTask(3, new EntityAILMHurtByTarget(owner, true));
		ltasks2[1].addTask(4, new EntityAILMNearestAttackableTarget<EntityLivingBase>(owner, EntityLivingBase.class, 0, true));
		owner.addMaidMode(mmode_Bloodsucker, ltasks2);
	}

	@Override
	public boolean changeMode(EntityPlayer pentityplayer) {
		ItemStack litemstack = owner.getHandSlotForModeChange();
		if (!litemstack.isEmpty()) {
			if (isTriggerItem(mmode_Fencer, litemstack)) {
				owner.setMaidMode(mmode_Fencer);
				AchievementsLMRE.grantAC(pentityplayer, AC.Fencer);
				if (litemstack.getItem() instanceof ItemSpade && pentityplayer != null) {
					AchievementsLMRE.grantAC(pentityplayer, AC.Buster);
				}
				return true;
			} else  if (isTriggerItem(mmode_Bloodsucker, litemstack)) {
				owner.setMaidMode(mmode_Bloodsucker);
				AchievementsLMRE.grantAC(pentityplayer, AC.RandomKiller);
				if (litemstack.getItem() instanceof ItemSpade && pentityplayer != null) {
					AchievementsLMRE.grantAC(pentityplayer, AC.Buster);
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean setMode(String pMode) {
		switch (pMode) {
		case mmode_Fencer :
			owner.setBloodsuck(false);
			owner.aiAttack.isGuard = true;
			return true;
		case mmode_Bloodsucker :
			owner.setBloodsuck(true);
			return true;
		}
		return false;
	}

	@Override
	public int getNextEquipItem(String pMode) {
		if (isTriggerItem(pMode, owner.getHandSlotForModeChange())) {
			return InventoryLittleMaid.handInventoryOffset;
		}

		int li;
		double ld = 0;
		double lld;
		ItemStack litemstack;

		switch (pMode) {
		case mmode_Fencer :
			for (li = 0; li < owner.maidInventory.getSizeInventory() - 1; li++) {
				litemstack = owner.maidInventory.getStackInSlot(li);
				if (litemstack.isEmpty()) continue;
				if (isTriggerItem(pMode, litemstack)) {
					return li;
				}
				lld = 1;
				try {
					lld = CommonHelper.getAttackVSEntity(litemstack);
				}
				catch (Exception e) {}
				if (lld > ld) {
					ld = lld;
				}
			}
			break;
		case mmode_Bloodsucker :
			for (li = 0; li < owner.maidInventory.getSizeInventory(); li++) {
				litemstack = owner.maidInventory.getStackInSlot(li);
				if (litemstack.isEmpty()) continue;
				if (isTriggerItem(pMode, litemstack)) {
					return li;
				}
				lld = 1;
				try {
					lld = CommonHelper.getAttackVSEntity(litemstack);
				}
				catch (Exception e) {}
				if (lld > ld) {
					ld = lld;
				}
			}
			break;
		}
		return -1;
	}

	@Override
	protected boolean isTriggerItem(String pMode, ItemStack par1ItemStack) {
		if (par1ItemStack.isEmpty()) return false;
		switch (pMode) {
		case mmode_Fencer:
			return owner.getModeTrigger().isTriggerable(mtrigger_Sword, par1ItemStack, ItemSword.class);
		case mmode_Bloodsucker:
			boolean ret1 = owner.getModeTrigger().isTriggerable(mtrigger_Axe, par1ItemStack, ItemAxe.class);
			boolean ret2 = owner.getModeTrigger().isTriggerable(mtrigger_Axe, owner.getHeldItemOffhand(), ItemAxe.class);
			return ret1 && ret2;
		}
		return super.isTriggerItem(pMode, par1ItemStack);
	}

	@Override
	public boolean checkItemStack(ItemStack pItemStack) {
		return pItemStack.getItem() instanceof ItemSword || pItemStack.getItem() instanceof ItemAxe;
	}
	
	@Override
	public boolean isSearchEntity() {
		return owner.getMaidModeString().equals(mmode_Fencer);
	}
	
	@Override
	public boolean checkEntity(String pMode, Entity pEntity) {
		if (!owner.isFreedom() && owner.getMaidMasterEntity() != null &&
				owner.getMaidMasterEntity().getDistanceSq(pEntity) >= getLimitRangeSqOnFollow()) {
			return false;
		}
		if (pEntity instanceof EntityCreeper) {
			if (owner.getMaidMasterEntity() == null ? true : !owner.getMaidMasterEntity().equals(((EntityCreeper) pEntity).getAttackTarget())) {
				return false;
			}
		}
		return !owner.getIFF(pEntity);
	}
	
	@Override
    public void updateAITick(String pMode) {
        super.updateAITick(pMode);
    }

	@Override
	public double getDistanceToSearchTargets() {
		if (owner.isFreedom()) return 18d;
		return super.getDistanceToSearchTargets();
	}

	@Override
	public double getLimitRangeSqOnFollow() {
		return 18 * 18;
	}

	@Override
	public double getFreedomTrackingRangeSq() {
		return 25 * 25;
	}
	
	@Override
	public float attackEntityFrom(DamageSource damageSource, float amount) {
		return 0;
	}
}
