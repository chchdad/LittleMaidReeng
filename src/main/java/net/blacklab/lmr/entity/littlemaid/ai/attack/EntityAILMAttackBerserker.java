package net.blacklab.lmr.entity.littlemaid.ai.attack;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.MathHelper;

/**
 * 狂战士独立 AI (专属动画修复版：普通攻击动画 + 幕后双伤结算)
 */
public class EntityAILMAttackBerserker extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;
	protected EntityLittleMaid theMaid;
	protected EntityLivingBase entityTarget; 
	protected float moveSpeed;
	protected boolean isReroute;
	protected Path pathToTarget;
	protected int rerouteTimer;
	protected double attackRange;

	protected int actionDelayTimer = 0; 
	protected boolean pendingDash = false; 
	protected boolean isDashBuff = false; 
	protected float lastTickHealth = -1.0F;

	public EntityAILMAttackBerserker(EntityLittleMaid par1EntityLittleMaid) {
		theMaid = par1EntityLittleMaid;
		moveSpeed = 1.0F;
		isReroute = true;
		setMutexBits(3); 
	}

	private int getWeaponCooldown() {
		float attackSpeed = 4.0F; 
		net.minecraft.entity.ai.attributes.IAttributeInstance speedAttr = theMaid.getEntityAttribute(SharedMonsterAttributes.ATTACK_SPEED);
		if (speedAttr != null) {
			attackSpeed = (float) speedAttr.getAttributeValue();
		}
		return (int)(20.0F / Math.max(0.1F, attackSpeed));
	}

	@Override
	public boolean shouldExecute() {
		if (theMaid.isMaidWait()) return false;

		ItemStack mainHand = theMaid.getHeldItemMainhand();
		ItemStack offHand = theMaid.getHeldItemOffhand();
		boolean hasMainAxe = !mainHand.isEmpty() && mainHand.getItem() instanceof ItemAxe;
		boolean hasOffAxe = !offHand.isEmpty() && offHand.getItem() instanceof ItemAxe;
		
		if (!(hasMainAxe && hasOffAxe)) {
			return false;
		}

		EntityLivingBase lentity = theMaid.getAttackTarget();
		if (lentity == null && theMaid.getRevengeTarget() != null && theMaid.getRevengeTarget().isEntityAlive()) {
			theMaid.setAttackTarget(theMaid.getRevengeTarget());
			lentity = theMaid.getAttackTarget();
		}

		if (lentity == null || !lentity.isEntityAlive()) return false;
		
		entityTarget = lentity;
		pathToTarget = theMaid.getNavigator().getPathToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ);
		attackRange = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRange *= attackRange;

		if (theMaid.isFreedom() && !theMaid.isWithinHomeDistanceFromPosition(entityTarget.getPosition())) {
			return false;
		}
		
		if ((pathToTarget != null) || (theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ) <= attackRange)) {
			return true;
		}
		
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		return false;
	}

	@Override
	public void startExecuting() {
		if(entityTarget != null && !entityTarget.isDead){
			theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true);
		}
		
		if (pathToTarget != null) {
			theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		} else {
			theMaid.getNavigator().clearPath();
		}
		rerouteTimer = 0;
		theMaid.stopActiveHand(); 
		theMaid.maidAvatar.stopActiveHand();
		this.lastTickHealth = theMaid.getHealth(); 
	}

	@Override
	public boolean shouldContinueExecuting() {
		if (actionDelayTimer > 0 || pendingDash || isDashBuff) {
			if (entityTarget != null && entityTarget.isEntityAlive() && !entityTarget.isDead) {
				return true; 
			}
		}

		if (!theMaid.isFreedom() && theMaid.getMaidMasterEntity() instanceof EntityLivingBase) {
			EntityLivingBase master = (EntityLivingBase) theMaid.getMaidMasterEntity();
			boolean isMasterMoving = Math.abs(master.posX - master.prevPosX) > 0.02D || Math.abs(master.posZ - master.prevPosZ) > 0.02D;
			double maxDistSq = isMasterMoving ? 225.0D : 729.0D; 
			
			if (theMaid.getDistanceSq(master) > maxDistSq) {
				theMaid.setPositionAndUpdate(master.posX, master.posY, master.posZ);
				theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_ENDERMEN_TELEPORT, 0.5F, 1.2F);
				resetTask();
				return false;
			}
		}

		EntityLivingBase lentity = theMaid.getAttackTarget();
		if (lentity == null || entityTarget != lentity || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return false;
		}

		if (!MaidHelper.isTargetReachable(this.theMaid, lentity, 0.0D)) {
			return false;
		}
		if (!isReroute && theMaid.getNavigator().noPath()) {
			return false;
		}
		return true;
	}

	@Override
	public void resetTask() {
		entityTarget = null;
		theMaid.getNavigator().clearPath();
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		theMaid.stopActiveHand(); 
		theMaid.maidAvatar.stopActiveHand();

		actionDelayTimer = 0;
		pendingDash = false;
		isDashBuff = false;
	}

	@Override
	public void updateTask() {
		if (entityTarget == null || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return;
		}

		if (this.lastTickHealth < 0) this.lastTickHealth = theMaid.getHealth();
		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);

		// =======================================================
		// 1. 突刺命中与完美伤害核算 (Dash Buff)
		// =======================================================
		if (this.isDashBuff) {
			if (theMaid.onGround && Math.abs(theMaid.motionX) < 0.05D && Math.abs(theMaid.motionZ) < 0.05D) {
				this.isDashBuff = false;
				theMaid.hurtResistantTime = 0;
			} else {
				double dX = entityTarget.posX - theMaid.posX;
				double dZ = entityTarget.posZ - theMaid.posZ;
				double distance = Math.sqrt(dX * dX + dZ * dZ);

				theMaid.rotationYaw = (float)(Math.atan2(dZ, dX) * 180.0D / Math.PI) - 90.0F;
				theMaid.renderYawOffset = theMaid.rotationYaw;

				if (distance > 1.5D && distance < 10.0D) {
					theMaid.motionX = (dX / distance) * 0.9D;
					theMaid.motionZ = (dZ / distance) * 0.9D;
					theMaid.velocityChanged = true;
					this.lastTickHealth = theMaid.getHealth();
					return; 
				} 
				else if (distance <= 1.5D || theMaid.getEntityBoundingBox().grow(0.8D, 0.8D, 0.8D).intersects(entityTarget.getEntityBoundingBox())) {
					
					// 🌟 1. 计算主手总伤 (基础+附魔)
					float mainBaseDmg = (float)theMaid.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
					float mainEnchantDmg = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(theMaid.getHeldItemMainhand(), entityTarget.getCreatureAttribute());
					float mainTotal = mainBaseDmg + mainEnchantDmg;

					// 🌟 2. 计算副手总伤 (基础+附魔)
					float offBaseDmg = 0.0F;
					ItemStack offItem = theMaid.getHeldItemOffhand();
					if (!offItem.isEmpty()) {
						com.google.common.collect.Multimap<String, net.minecraft.entity.ai.attributes.AttributeModifier> modifiers = offItem.getAttributeModifiers(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND);
						for (net.minecraft.entity.ai.attributes.AttributeModifier mod : modifiers.get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
							offBaseDmg += (float)mod.getAmount();
						}
					}
					float offEnchantDmg = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(offItem, entityTarget.getCreatureAttribute());
					float offTotal = offBaseDmg + offEnchantDmg;

					// 🌟 3. 强制使用“普通攻击”调用，触发女仆专属动画、音效、和主手伤害计算！
					boolean isHit = theMaid.attackEntityAsMob(entityTarget);

					if (isHit) {
						// 🌟 4. 如果命中了，幕后追加副手伤害 + 跳劈的额外50%总伤！
						// 由于 attackEntityAsMob 已经造成了 mainTotal 的伤害，我们需要补上剩下的：
						float extraDamage = offTotal + ((mainTotal + offTotal) * 0.5F);
						
						// 追加真实物理伤害
						entityTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), extraDamage);
						
						// 手动扣除副手耐久
						if (!offItem.isEmpty()) {
							offItem.damageItem(1, theMaid);
						}

						// 击退与粒子特效
						if (entityTarget instanceof EntityLivingBase) {
							((EntityLivingBase)entityTarget).knockBack(theMaid, 1.5F, 
								(double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), 
								(double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
							entityTarget.velocityChanged = true;
						}

						theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
						if (theMaid.getEntityWorld() instanceof net.minecraft.world.WorldServer) {
							((net.minecraft.world.WorldServer)theMaid.getEntityWorld()).spawnParticle(
								net.minecraft.util.EnumParticleTypes.CRIT, 
								entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 
								25, 0.4D, 0.4D, 0.4D, 0.2D
							);
						}
					}

					this.isDashBuff = false;
					this.actionDelayTimer = getWeaponCooldown();
					
					theMaid.motionX = 0.0D;
					theMaid.motionY = 0.0D; 
					theMaid.motionZ = 0.0D;
					theMaid.velocityChanged = true;

					this.lastTickHealth = theMaid.getHealth();
					return; 
				} else {
					this.isDashBuff = false;
				}
			}
		}

		// =======================================================
		// 2. 突刺起步前摇
		// =======================================================
		if (actionDelayTimer > 0) {
			actionDelayTimer--;
			
			if (pendingDash) {
				double forceLookX = entityTarget.posX - theMaid.posX;
				double forceLookZ = entityTarget.posZ - theMaid.posZ;
				float strictTargetYaw = (float)(Math.atan2(forceLookZ, forceLookX) * 180.0D / Math.PI) - 90.0F;
				theMaid.rotationYaw = strictTargetYaw;
				theMaid.rotationYawHead = strictTargetYaw;
				theMaid.renderYawOffset = strictTargetYaw;
				
				if (actionDelayTimer <= 0) {
					pendingDash = false;
					
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
					theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true);
					
					theMaid.stopActiveHand(); 
					theMaid.maidAvatar.stopActiveHand(); 
					
					double dX = entityTarget.posX - theMaid.posX;
					double dZ = entityTarget.posZ - theMaid.posZ;
					double distance = Math.sqrt(dX * dX + dZ * dZ);
					
					if (distance >= 0.0001D) {
						theMaid.motionX = (dX / distance) * 1.2D; 
						theMaid.motionZ = (dZ / distance) * 1.2D;
						theMaid.motionY = 0.2D;  
						theMaid.velocityChanged = true;
					}
					
					theMaid.hurtResistantTime = 20; 
					this.isDashBuff = true; 
				}
				this.lastTickHealth = theMaid.getHealth();
				return; 
			}
		}

		// =======================================================
		// 3. 寻路追击
		// =======================================================
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				float burstSpeed = moveSpeed * 1.5F; 
				if (distToTarget < 36.0D) {
					burstSpeed = moveSpeed * 1.8F; 
				}
				
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		// =======================================================
		// 4. 斩击与测距判定
		// =======================================================
		if (this.actionDelayTimer <= 0 && !this.isDashBuff) {
			double currentDistSq = theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ);
			
			if (currentDistSq >= 25.0D && currentDistSq <= 49.0D) {
				this.pendingDash = true;
				this.actionDelayTimer = 10; 
				return;
			}

			double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
			attackRangeSq *= attackRangeSq;
			
			if (currentDistSq <= attackRangeSq) {
				double tdx = entityTarget.posX - theMaid.posX;
				double tdz = entityTarget.posZ - theMaid.posZ;
				float targetYaw = (float)(Math.atan2(tdz, tdx) * 180.0D / Math.PI) - 90.0F;
				
				theMaid.rotationYaw = targetYaw;
				theMaid.rotationYawHead = targetYaw;
				theMaid.renderYawOffset = targetYaw;

				double vdx = -Math.sin(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
				double vdz = Math.cos(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
				double ld = (tdx * vdx + tdz * vdz) / (Math.sqrt(tdx * tdx + tdz * tdz) * Math.sqrt(vdx * vdx + vdz * vdz));
				
				boolean canSlashNow = (ld >= -0.35D) && theMaid.getSwingStatusDominant().canAttack();

				if (canSlashNow) {
					boolean isHit = theMaid.attackEntityAsMob(entityTarget); 
					
					if (isHit) {
						if (!theMaid.onGround) {
							theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
							if (theMaid.getEntityWorld() instanceof net.minecraft.world.WorldServer) {
								((net.minecraft.world.WorldServer)theMaid.getEntityWorld()).spawnParticle(
									net.minecraft.util.EnumParticleTypes.CRIT, 
									entityTarget.posX, entityTarget.posY + (entityTarget.height / 2.0F), entityTarget.posZ, 
									15, 0.3D, 0.3D, 0.3D, 0.2D
								);
							}
							
							float baseAttackDamage = (float)theMaid.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
							entityTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), baseAttackDamage * 0.5F);
						} 
					}
					this.actionDelayTimer = getWeaponCooldown();
				} 
			} 
		}

		this.lastTickHealth = theMaid.getHealth();
	} 

	@Override
	public void setEnable(boolean pFlag) { fEnable = pFlag; }
	@Override
	public boolean getEnable() { return fEnable; }
}
