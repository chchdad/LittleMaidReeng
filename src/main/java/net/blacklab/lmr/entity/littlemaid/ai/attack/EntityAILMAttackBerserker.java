package net.blacklab.lmr.entity.littlemaid.ai.attack;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.util.helper.CommonHelper;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.MathHelper;

/**
 * 狂战士独立 AI (移除后撤步 + 5~7格突刺 + 双手总伤跳劈版)
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

	// ==========================================
	// 连招状态机变量 (已精简：移除 pendingBackstep 和 isGuard)
	// ==========================================
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
		// 1. 突刺命中与动作判定 (Dash Buff)
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
					
					// 🌟 核心：计算双手斧头伤害与附魔总和
					float mainBaseDmg = (float)theMaid.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
					float mainEnchantDmg = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(theMaid.getHeldItemMainhand(), entityTarget.getCreatureAttribute());
					
					float offBaseDmg = 0.0F;
					try {
						// 使用 CommonHelper 获取副手武器基础伤害 (类似源码实现[span_1](start_span)[span_1](end_span))
						offBaseDmg = (float) CommonHelper.getAttackVSEntity(theMaid.getHeldItemOffhand());
					} catch (Exception e) {}
					float offEnchantDmg = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(theMaid.getHeldItemOffhand(), entityTarget.getCreatureAttribute());
					
					float totalDamage = mainBaseDmg + mainEnchantDmg + offBaseDmg + offEnchantDmg;

					// 执行造成伤害
					entityTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), totalDamage);

					// 🌟 核心：连续两下挥臂，制造双手劈砍动画
					theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
					theMaid.swingArm(net.minecraft.util.EnumHand.OFF_HAND);

					this.isDashBuff = false;
					this.actionDelayTimer = getWeaponCooldown();
					
					theMaid.motionX = 0.0D;
					theMaid.motionY = 0.0D; 
					theMaid.motionZ = 0.0D;
					theMaid.velocityChanged = true;

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
							15, 0.3D, 0.3D, 0.3D, 0.2D
						);
					}
					this.lastTickHealth = theMaid.getHealth();
					return; 
				} else {
					this.isDashBuff = false;
				}
			}
		}

		// =======================================================
		// 2. FSM 连招 (仅保留突刺前摇，移除后撤)
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
			
			// 🌟 核心：距离在 5~7 格时 (25D ~ 49D)，直接进入突刺前摇状态
			if (currentDistSq >= 25.0D && currentDistSq <= 49.0D) {
				this.pendingDash = true;
				this.actionDelayTimer = 10; // 给 0.5 秒的小蓄力前摇，然后弹射起步
				return;
			}

			double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
			attackRangeSq *= attackRangeSq;
			
			// 普通距离内的平A
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
					// 移除了 25% 触发连招的概率，现在只靠 5-7 格的距离判定触发
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
