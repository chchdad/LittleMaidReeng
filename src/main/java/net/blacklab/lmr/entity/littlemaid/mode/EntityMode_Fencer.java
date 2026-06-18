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
	
	protected int dismountTimer = 0;
	protected int dismountCooldown = 0;

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

		ltasks[1].addTask(1, new net.minecraft.entity.ai.EntityAITarget(owner, false) {
			private EntityLivingBase attacker;
			@Override
			public boolean shouldExecute() {
				if (owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase newAttacker = owner.getMaidMasterEntity().getRevengeTarget();
				if (newAttacker == null || !newAttacker.isEntityAlive() || owner.getIFF(newAttacker) || !(newAttacker instanceof IMob)) {
					return false;
				}
				this.attacker = newAttacker;
				return true;
			}
			@Override
			public void startExecuting() {
				super.startExecuting();
				owner.setAttackTarget(this.attacker);
			}
		});

		ltasks[1].addTask(2, new net.minecraft.entity.ai.EntityAITarget(owner, false) {
			private java.util.Map<Integer, Integer> hitCountMap = new java.util.HashMap<>();
			private int lastAttackTick = 0;
			private EntityLivingBase targetToAttack;

			@Override
			public boolean shouldExecute() {
				if (owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase target = owner.getMaidMasterEntity().getLastAttackedEntity();
				
				if (target == null || !target.isEntityAlive()) return false;

				int currentTick = owner.getMaidMasterEntity().getLastAttackedEntityTime();
				if (currentTick != lastAttackTick) {
					lastAttackTick = currentTick;
					int id = target.getEntityId();
					hitCountMap.put(id, hitCountMap.getOrDefault(id, 0) + 1);
				}

				int hits = hitCountMap.getOrDefault(target.getEntityId(), 0);
				float missingHealth = target.getMaxHealth() - target.getHealth();
				boolean isFriendly = owner.getIFF(target);

				// 如果是友军（如村民），必须连续打 6 次才能证明是“蓄意谋杀”，不因高伤害误伤
				if (isFriendly) {
					if (hits >= 6) {
						this.targetToAttack = target;
						return true;
					}
					return false;
				} else {
					// 正常怪物：满足伤害或者次数任意一条即可
					if (missingHealth >= 10.0F || hits >= 6) {
						this.targetToAttack = target;
						return true;
					}
					return false;
				}
			}

			@Override
			public void startExecuting() {
				super.startExecuting();
				owner.setAttackTarget(this.targetToAttack);
			}
		});

		ltasks[1].addTask(3, new EntityAILMHurtByTarget(owner, true));
		ltasks[1].addTask(4, new EntityAILMNearestAttackableTarget<EntityLivingBase>(owner, EntityLivingBase.class, 0, true));
		owner.addMaidMode(mmode_Fencer, ltasks);


		EntityAITasks[] ltasks2 = new EntityAITasks[2];
		ltasks2[0] = pDefaultMove;
		ltasks2[1] = new EntityAITasks(owner.aiProfiler);

		ltasks2[1].addTask(1, new net.minecraft.entity.ai.EntityAITarget(owner, false) {
			private EntityLivingBase attacker;
			@Override
			public boolean shouldExecute() {
				if (owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase newAttacker = owner.getMaidMasterEntity().getRevengeTarget();
				if (newAttacker == null || !newAttacker.isEntityAlive() || owner.getIFF(newAttacker) || !(newAttacker instanceof IMob)) {
					return false;
				}
				this.attacker = newAttacker;
				return true;
			}
			@Override
			public void startExecuting() {
				super.startExecuting();
				owner.setAttackTarget(this.attacker);
			}
		});
		
		ltasks2[1].addTask(2, new net.minecraft.entity.ai.EntityAITarget(owner, false) {
			private java.util.Map<Integer, Integer> hitCountMap = new java.util.HashMap<>();
			private int lastAttackTick = 0;
			private EntityLivingBase targetToAttack;

			@Override
			public boolean shouldExecute() {
				if (owner.getMaidMasterEntity() == null) return false;
				EntityLivingBase target = owner.getMaidMasterEntity().getLastAttackedEntity();
				
				if (target == null || !target.isEntityAlive()) return false;

				int currentTick = owner.getMaidMasterEntity().getLastAttackedEntityTime();
				if (currentTick != lastAttackTick) {
					lastAttackTick = currentTick;
					int id = target.getEntityId();
					hitCountMap.put(id, hitCountMap.getOrDefault(id, 0) + 1);
				}

				int hits = hitCountMap.getOrDefault(target.getEntityId(), 0);
				float missingHealth = target.getMaxHealth() - target.getHealth();
				boolean isFriendly = owner.getIFF(target);

				if (isFriendly) {
					if (hits >= 6) {
						this.targetToAttack = target;
						return true;
					}
					return false;
				} else {
					if (missingHealth >= 10.0F || hits >= 6) {
						this.targetToAttack = target;
						return true;
					}
					return false;
				}
			}

			@Override
			public void startExecuting() {
				super.startExecuting();
				owner.setAttackTarget(this.targetToAttack);
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
			boolean attackingOwner = owner.getMaidMasterEntity() != null && owner.getMaidMasterEntity().equals(((EntityCreeper) pEntity).getAttackTarget());
			boolean attackingMaid = pEntity.equals(owner.getRevengeTarget()) || owner.equals(((EntityCreeper)pEntity).getAttackTarget());
			if (!attackingOwner && !attackingMaid) {
				return false;
			}
		}
		return !owner.getIFF(pEntity);
	}
	
	@Override
    public void updateAITick(String pMode) {
        super.updateAITick(pMode);
        
        if (this.dismountCooldown > 0) {
            this.dismountCooldown--;
        }
        
        if (owner.isRiding()) {
            Entity mount = owner.getRidingEntity();
            if (mount instanceof EntityLivingBase) {
                boolean isSafeMount = false;
                
                if (mount instanceof net.minecraft.entity.passive.EntityTameable) {
                    UUID tameOwner = ((net.minecraft.entity.passive.EntityTameable)mount).getOwnerId();
                    if (tameOwner != null && tameOwner.equals(owner.getMaidMasterUUID())) isSafeMount = true;
                } else if (mount instanceof net.minecraft.entity.passive.AbstractHorse) {
                    UUID horseOwner = ((net.minecraft.entity.passive.AbstractHorse)mount).getOwnerUniqueId();
                    if (horseOwner != null && horseOwner.equals(owner.getMaidMasterUUID())) isSafeMount = true;
                }
                
                if (!isSafeMount) {
                    if (this.dismountCooldown <= 0) {
                        this.dismountTimer++;
                        if (this.dismountTimer >= 3) {
                            owner.dismountRidingEntity();
                            this.dismountTimer = 0;
                            this.dismountCooldown = 10; 
                        }
                    }
                } else {
                    this.dismountTimer = 0; 
                }
            } else {
                this.dismountTimer = 0; 
            }
        } else {
            this.dismountTimer = 0; 
        }
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
